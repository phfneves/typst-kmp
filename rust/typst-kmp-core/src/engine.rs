//! The compilation engine: fonts, VFS mutations and the compile entry point.

use typst::comemo::Track;
use typst::diag::{Severity as TypstSeverity, SourceDiagnostic, Warned};
use typst::engine::Sink;
use typst::foundations::{
    Bytes, Context, Datetime, Dict, IntoValue, LocatableSelector, Scope, Smart,
};
use typst::introspection::{EmptyIntrospector, Introspector};
use typst::layout::Abs;
use typst::routines::SpanMode;
use typst::syntax::package::PackageSpec;
use typst::syntax::{DiagSpan, Span, SyntaxMode, VirtualRoot};
use typst::text::{Font, FontBook};
use typst::utils::LazyHash;
use typst::{Library, LibraryExt, World, WorldExt};
use typst_layout::PagedDocument;
use typst_pdf::{PdfOptions, PdfStandard, PdfStandards};
use typst_render::RenderOptions;
use typst_svg::SvgOptions;

use crate::pkg;
use crate::protocol::{
    CompileRequest, CompileResponse, Diagnostic, EngineConfig, FileEntry, FontFamily, Inspection,
    Missing, OutputMeta, OutputSpec, Severity, TracePoint,
};
use crate::vfs::{format_path, parse_path, Vfs};
use crate::world::KmpWorld;

/// Result of a compilation: the JSON envelope plus the raw output blobs it describes.
pub struct CompileOutcome {
    pub response: CompileResponse,
    pub blobs: Vec<Vec<u8>>,
}

/// How many compilations a memoised result may go unused before it is evicted.
const CACHE_MAX_AGE: usize = 10;

pub struct TypstEngine {
    fonts: Vec<Font>,
    book: LazyHash<FontBook>,
    vfs: Vfs,
}

impl TypstEngine {
    pub fn new(config: EngineConfig) -> Self {
        let mut fonts = Vec::new();
        if config.embed_default_fonts {
            fonts.extend(embedded_fonts());
        }
        let book = LazyHash::new(FontBook::from_fonts(fonts.iter()));
        Self {
            fonts,
            book,
            vfs: Vfs::new(),
        }
    }

    /// Registers every face contained in a font file. Returns how many were added.
    pub fn add_font(&mut self, data: Vec<u8>) -> usize {
        let before = self.fonts.len();
        self.fonts.extend(Font::iter(Bytes::new(data)));
        self.book = LazyHash::new(FontBook::from_fonts(self.fonts.iter()));
        self.fonts.len() - before
    }

    pub fn vfs_put(&mut self, path: &str, bytes: Vec<u8>) -> Result<(), String> {
        let id = parse_path(path)?;
        self.vfs.insert(id, Bytes::new(bytes));
        Ok(())
    }

    /// Removes one file, project or package. Returns whether it was there.
    pub fn vfs_remove(&mut self, path: &str) -> Result<bool, String> {
        Ok(self.vfs.remove(parse_path(path)?))
    }

    /// Removes every project file, keeping packages and fonts. Returns how many were removed.
    pub fn vfs_clear_files(&mut self) -> usize {
        self.vfs.clear_project()
    }

    /// Removes every package. Returns how many packages were removed.
    pub fn vfs_clear_packages(&mut self) -> usize {
        self.vfs.clear_packages()
    }

    /// Unpacks a `.tar.gz` package archive into the VFS under `@<namespace>/<name>:<version>/`.
    pub fn vfs_put_package(&mut self, spec: &str, archive: &[u8]) -> Result<usize, String> {
        let spec: PackageSpec = spec
            .parse()
            .map_err(|err| format!("invalid package spec {spec}: {err}"))?;
        let entries = pkg::unpack(archive)?;
        let count = entries.len();
        for (relative, bytes) in entries {
            let id = parse_path(&format!(
                "@{}/{}:{}/{}",
                spec.namespace,
                spec.name,
                spec.version,
                relative.trim_start_matches('/')
            ))?;
            self.vfs.insert(id, Bytes::new(bytes));
        }
        self.vfs.mark_package_loaded(&spec);
        Ok(count)
    }

    /// Lists what the engine holds: its files outside packages, its packages and its fonts.
    pub fn inspect(&self) -> Inspection {
        let mut files: Vec<FileEntry> = self
            .vfs
            .entries()
            .filter(|(id, _)| matches!(id.get().root(), VirtualRoot::Project))
            .map(|(id, size)| FileEntry {
                path: format_path(id),
                size,
            })
            .collect();
        files.sort_by(|a, b| a.path.cmp(&b.path));
        let mut packages: Vec<String> = self.vfs.package_specs().map(str::to_string).collect();
        packages.sort();
        let mut fonts: Vec<FontFamily> = self
            .book
            .families()
            .map(|(name, faces)| FontFamily {
                name: name.to_string(),
                faces: faces.count(),
            })
            .collect();
        fonts.sort_by(|a, b| a.name.cmp(&b.name));
        Inspection {
            files,
            packages,
            fonts,
        }
    }

    /// Compiles `request`. `files` holds the bytes of [`CompileRequest::files`], in the same
    /// order; they are visible to this compilation only and never touch the engine's VFS.
    pub fn compile(
        &self,
        request: CompileRequest,
        files: Vec<Vec<u8>>,
    ) -> Result<CompileOutcome, String> {
        if request.files.len() != files.len() {
            return Err(format!(
                "compile request names {} files but {} were supplied",
                request.files.len(),
                files.len()
            ));
        }
        let mut overlay = Vfs::new();
        for (path, bytes) in request.files.iter().zip(files) {
            overlay.insert(parse_path(path)?, Bytes::new(bytes));
        }
        let outcome = self.compile_uncached(request, &overlay);
        // Typst memoises layout and evaluation in a process-wide cache that only shrinks when
        // asked to. Drop whatever went unused for a few compilations, as `typst watch` does, so
        // a long-lived engine does not grow without bound.
        typst::comemo::evict(CACHE_MAX_AGE);
        outcome
    }

    fn compile_uncached(
        &self,
        request: CompileRequest,
        overlay: &Vfs,
    ) -> Result<CompileOutcome, String> {
        let main = parse_path(&request.main)?;
        if !overlay.contains(main) && !self.vfs.contains(main) {
            // Report this like any other miss so the Kotlin resolution loop can fetch it.
            return Ok(CompileOutcome {
                response: CompileResponse {
                    ok: false,
                    outputs: Vec::new(),
                    diagnostics: Vec::new(),
                    missing: vec![Missing::File {
                        path: format_path(main),
                    }],
                },
                blobs: Vec::new(),
            });
        }

        let inputs = Dict::from_iter(
            request
                .inputs
                .iter()
                .map(|(key, value)| (key.as_str().into(), value.clone().into_value())),
        );
        let library = Library::builder().with_inputs(inputs).build();
        let today = request.now.and_then(|spec| {
            Datetime::from_ymd_hms(
                spec.year,
                spec.month,
                spec.day,
                spec.hour,
                spec.minute,
                spec.second,
            )
        });

        let world = KmpWorld::new(
            library,
            &self.book,
            &self.fonts,
            &self.vfs,
            overlay,
            main,
            today,
        );

        let Warned { output, warnings } = typst::compile::<PagedDocument>(&world);

        let mut diagnostics: Vec<Diagnostic> = warnings
            .iter()
            .map(|diag| convert_diagnostic(&world, diag))
            .collect();

        match output {
            Err(errors) => {
                diagnostics.extend(errors.iter().map(|diag| convert_diagnostic(&world, diag)));
                Ok(CompileOutcome {
                    response: CompileResponse {
                        ok: false,
                        outputs: Vec::new(),
                        diagnostics,
                        missing: world.into_misses(),
                    },
                    blobs: Vec::new(),
                })
            }
            Ok(document) => {
                let mut blobs = Vec::new();
                let mut outputs = Vec::new();
                for spec in &request.outputs {
                    let start = blobs.len();
                    let kind = export(&world, &document, spec, &mut blobs)?;
                    outputs.push(OutputMeta {
                        kind,
                        blob_start: start,
                        blob_count: blobs.len() - start,
                    });
                }
                Ok(CompileOutcome {
                    response: CompileResponse {
                        ok: true,
                        outputs,
                        diagnostics,
                        missing: world.into_misses(),
                    },
                    blobs,
                })
            }
        }
    }
}

fn export(
    world: &KmpWorld<'_>,
    document: &PagedDocument,
    spec: &OutputSpec,
    blobs: &mut Vec<Vec<u8>>,
) -> Result<String, String> {
    match spec {
        OutputSpec::Pdf {
            ident,
            creator,
            standards,
            pretty,
        } => {
            let parsed = standards
                .iter()
                .map(|name| parse_pdf_standard(name))
                .collect::<Result<Vec<_>, _>>()?;
            let standards = PdfStandards::new(&parsed)
                .map_err(|err| format!("invalid PDF standard combination: {}", err.message()))?;
            let options = PdfOptions {
                ident: match ident {
                    Some(value) => Smart::Custom(value.clone()),
                    None => Smart::Auto,
                },
                creator: match creator {
                    Some(value) => Smart::Custom(Some(value.clone())),
                    None => Smart::Auto,
                },
                timestamp: None,
                page_ranges: None,
                standards,
                tagged: false,
                pretty: *pretty,
            };
            let bytes = typst_pdf::pdf(document, &options)
                .map_err(|errors| join_messages("PDF export failed", &errors))?;
            blobs.push(bytes);
            Ok("pdf".to_string())
        }
        OutputSpec::Svg { merged, pretty } => {
            let options = SvgOptions {
                render_bleed: false,
                pretty: *pretty,
            };
            if *merged {
                blobs.push(typst_svg::svg_merged(document, &options, Abs::zero()).into_bytes());
            } else {
                for page in document.pages() {
                    blobs.push(typst_svg::svg(page, &options).into_bytes());
                }
            }
            Ok("svg".to_string())
        }
        OutputSpec::Png {
            pixel_per_pt,
            merged,
        } => {
            let options = RenderOptions {
                pixel_per_pt: (*pixel_per_pt as f64).into(),
                render_bleed: false,
            };
            // `typst_render` re-exports tiny-skia privately, so the pixmap type cannot be named
            // in a helper's signature; encode it where inference already knows what it is.
            if *merged {
                let pixmap = typst_render::render_merged(document, &options, Abs::zero(), None);
                blobs.push(
                    pixmap
                        .encode_png()
                        .map_err(|err| format!("PNG encoding failed: {err}"))?,
                );
            } else {
                for page in document.pages() {
                    let pixmap = typst_render::render(page, &options);
                    blobs.push(
                        pixmap
                            .encode_png()
                            .map_err(|err| format!("PNG encoding failed: {err}"))?,
                    );
                }
            }
            Ok("png".to_string())
        }
        OutputSpec::Query {
            selector,
            field,
            one,
            pretty,
        } => {
            let json = run_query(world, document, selector, field.as_deref(), *one, *pretty)?;
            blobs.push(json.into_bytes());
            Ok("query".to_string())
        }
    }
}

/// Mirrors `typst query`: evaluate the selector in code mode, then walk the introspector.
fn run_query(
    world: &KmpWorld<'_>,
    document: &PagedDocument,
    selector: &str,
    field: Option<&str>,
    one: bool,
    pretty: bool,
) -> Result<String, String> {
    let mut sink = Sink::new();
    // `Track` is implemented for `dyn World`, not for concrete implementations.
    let tracked: &dyn World = world;
    let value = typst_eval::eval_string(
        tracked.track(),
        world.library(),
        sink.track_mut(),
        EmptyIntrospector.track(),
        Context::none().track(),
        selector,
        SpanMode::Uniform(Span::detached()),
        SyntaxMode::Code,
        Scope::default(),
    )
    .map_err(|errors| join_messages("invalid selector", &errors))?;

    let selector = value
        .cast::<LocatableSelector>()
        .map_err(|err| format!("invalid selector: {}", err.message()))?;
    let elements = document.introspector().query(&selector.0);

    if one {
        let element = match elements.len() {
            1 => &elements[0],
            n => return Err(format!("expected exactly one element, found {n}")),
        };
        return match field {
            Some(field) => {
                let value = element
                    .get_by_name(field)
                    .map_err(|err| format!("element has no field `{field}`: {err:?}"))?;
                to_json(&value, pretty)
            }
            None => to_json(element, pretty),
        };
    }

    match field {
        Some(field) => {
            let values = elements
                .iter()
                .map(|element| {
                    element
                        .get_by_name(field)
                        .map_err(|err| format!("element has no field `{field}`: {err:?}"))
                })
                .collect::<Result<Vec<_>, _>>()?;
            to_json(&values, pretty)
        }
        None => to_json(&elements, pretty),
    }
}

fn to_json<T: serde::Serialize>(value: &T, pretty: bool) -> Result<String, String> {
    let result = if pretty {
        serde_json::to_string_pretty(value)
    } else {
        serde_json::to_string(value)
    };
    result.map_err(|err| format!("failed to serialize query result: {err}"))
}

fn parse_pdf_standard(name: &str) -> Result<PdfStandard, String> {
    match name.to_ascii_lowercase().as_str() {
        "1.4" => Ok(PdfStandard::V_1_4),
        "1.5" => Ok(PdfStandard::V_1_5),
        "1.6" => Ok(PdfStandard::V_1_6),
        "1.7" => Ok(PdfStandard::V_1_7),
        "2.0" => Ok(PdfStandard::V_2_0),
        "a-2b" | "pdf/a-2b" => Ok(PdfStandard::A_2b),
        "a-3b" | "pdf/a-3b" => Ok(PdfStandard::A_3b),
        other => Err(format!("unknown PDF standard: {other}")),
    }
}

fn join_messages(prefix: &str, errors: &[SourceDiagnostic]) -> String {
    let joined = errors
        .iter()
        .map(|diag| diag.message.to_string())
        .collect::<Vec<_>>()
        .join("; ");
    format!("{prefix}: {joined}")
}

fn convert_diagnostic(world: &KmpWorld<'_>, diag: &SourceDiagnostic) -> Diagnostic {
    let (path, start, end, line, column) = locate(world, diag.span);
    let severity = match diag.severity {
        TypstSeverity::Error => Severity::Error,
        TypstSeverity::Warning => Severity::Warning,
    };
    let hints: Vec<String> = diag.hints.iter().map(|hint| hint.v.to_string()).collect();
    let trace: Vec<TracePoint> = diag
        .trace
        .iter()
        .map(|point| {
            let (path, _, _, line, column) = locate(world, point.span.into());
            TracePoint {
                message: point.v.to_string(),
                path,
                line,
                column,
            }
        })
        .collect();
    let message = diag.message.to_string();
    let rendered = render(world, diag.span, severity, &message, &hints, &trace);
    Diagnostic {
        severity,
        message,
        path,
        start,
        end,
        line,
        column,
        hints,
        trace,
        rendered,
    }
}

/// Lays a diagnostic out the way the `typst` CLI does, without colour:
///
/// ```text
/// error: unknown variable: foo
///   ┌─ /main.typ:3:7
///   │
/// 3 │ Hello #foo
///   │        ^^^
///   │
///   = hint: …
/// ```
fn render(
    world: &KmpWorld<'_>,
    span: DiagSpan,
    severity: Severity,
    message: &str,
    hints: &[String],
    trace: &[TracePoint],
) -> String {
    use std::fmt::Write;

    let label = match severity {
        Severity::Error => "error",
        Severity::Warning => "warning",
    };
    let mut out = format!("{label}: {message}");

    let excerpt = span.id().and_then(|id| {
        let range = world.range(span)?;
        let source = world.source(id).ok()?;
        let lines = source.lines();
        let line = lines.byte_to_line(range.start)?;
        let column = lines.byte_to_column(range.start)?;
        let line_range = lines.line_to_range(line)?;
        let text = source.text()[line_range.clone()].trim_end_matches(['\r', '\n']);
        // Underline to the end of the span or of the line, whichever comes first, counting
        // characters rather than bytes so the carets line up under multi-byte text.
        let end = range
            .end
            .min(line_range.start + text.len())
            .max(range.start);
        let width = source.text()[range.start..end].chars().count().max(1);
        Some((
            format_path(id),
            line + 1,
            column + 1,
            text.to_string(),
            column,
            width,
        ))
    });

    let gutter = excerpt
        .as_ref()
        .map(|(_, line, ..)| line.to_string().len())
        .unwrap_or(1);
    let pad = " ".repeat(gutter);

    if let Some((path, line, column, text, offset, width)) = &excerpt {
        let _ = write!(out, "\n{pad} ┌─ {path}:{line}:{column}");
        let _ = write!(out, "\n{pad} │");
        let _ = write!(out, "\n{line} │ {text}");
        let _ = write!(
            out,
            "\n{pad} │ {}{}",
            " ".repeat(*offset),
            "^".repeat(*width)
        );
    }
    if !hints.is_empty() || !trace.is_empty() {
        if excerpt.is_some() {
            let _ = write!(out, "\n{pad} │");
        }
        for hint in hints {
            let _ = write!(out, "\n{pad} = hint: {hint}");
        }
        for point in trace {
            let _ = write!(out, "\n{pad} = {}", point.message);
            if let (Some(path), Some(line), Some(column)) = (&point.path, point.line, point.column)
            {
                let _ = write!(out, " ({path}:{line}:{column})");
            }
        }
    }
    out
}

/// `(path, startByte, endByte, line, column)` — line and column are 1-based for display.
type Location = (
    Option<String>,
    Option<usize>,
    Option<usize>,
    Option<usize>,
    Option<usize>,
);

fn locate(world: &KmpWorld<'_>, span: DiagSpan) -> Location {
    let Some(id) = span.id() else {
        return (None, None, None, None, None);
    };
    let path = Some(format_path(id));
    let Some(range) = world.range(span) else {
        return (path, None, None, None, None);
    };
    let (line, column) = match world.source(id) {
        Ok(source) => (
            source
                .lines()
                .byte_to_line(range.start)
                .map(|value| value + 1),
            source
                .lines()
                .byte_to_column(range.start)
                .map(|value| value + 1),
        ),
        Err(_) => (None, None),
    };
    (path, Some(range.start), Some(range.end), line, column)
}

#[cfg(feature = "embed-fonts")]
fn embedded_fonts() -> Vec<Font> {
    typst_assets::fonts()
        .flat_map(|data| Font::iter(Bytes::new(data)))
        .collect()
}

#[cfg(not(feature = "embed-fonts"))]
fn embedded_fonts() -> Vec<Font> {
    Vec::new()
}

/// What the engine holds, as JSON. See [`TypstEngine::inspect`].
pub fn inspect_json(engine: &TypstEngine) -> String {
    serde_json::to_string(&engine.inspect()).expect("inspection serialises")
}

/// Parses a compile request and runs it.
pub fn compile_json(
    engine: &TypstEngine,
    request_json: &str,
    files: Vec<Vec<u8>>,
) -> Result<CompileOutcome, String> {
    let request: CompileRequest = serde_json::from_str(request_json)
        .map_err(|err| format!("invalid compile request: {err}"))?;
    engine.compile(request, files)
}

/// Serializes a [`CompileResponse`] for transport.
pub fn response_json(response: &CompileResponse) -> String {
    serde_json::to_string(response).expect("CompileResponse is always serializable")
}
