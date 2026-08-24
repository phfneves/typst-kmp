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
use typst::syntax::{DiagSpan, Span, SyntaxMode};
use typst::text::{Font, FontBook};
use typst::utils::LazyHash;
use typst::{Library, LibraryExt, World, WorldExt};
use typst_layout::PagedDocument;
use typst_pdf::{PdfOptions, PdfStandard, PdfStandards};
use typst_render::RenderOptions;
use typst_svg::SvgOptions;

use crate::pkg;
use crate::protocol::{
    CompileRequest, CompileResponse, Diagnostic, EngineConfig, Missing, OutputMeta, OutputSpec,
    Severity, TracePoint,
};
use crate::vfs::{format_path, parse_path, Vfs};
use crate::world::KmpWorld;

/// Result of a compilation: the JSON envelope plus the raw output blobs it describes.
pub struct CompileOutcome {
    pub response: CompileResponse,
    pub blobs: Vec<Vec<u8>>,
}

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

    pub fn compile(&self, request: CompileRequest) -> Result<CompileOutcome, String> {
        let main = parse_path(&request.main)?;
        if !self.vfs.contains(main) {
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

        let world = KmpWorld::new(library, &self.book, &self.fonts, &self.vfs, main, today);

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
    Diagnostic {
        severity: match diag.severity {
            TypstSeverity::Error => Severity::Error,
            TypstSeverity::Warning => Severity::Warning,
        },
        message: diag.message.to_string(),
        path,
        start,
        end,
        line,
        column,
        hints: diag.hints.iter().map(|hint| hint.v.to_string()).collect(),
        trace: diag
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
            .collect(),
    }
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

/// Parses a compile request and runs it.
pub fn compile_json(engine: &TypstEngine, request_json: &str) -> Result<CompileOutcome, String> {
    let request: CompileRequest = serde_json::from_str(request_json)
        .map_err(|err| format!("invalid compile request: {err}"))?;
    engine.compile(request)
}

/// Serializes a [`CompileResponse`] for transport.
pub fn response_json(response: &CompileResponse) -> String {
    serde_json::to_string(response).expect("CompileResponse is always serializable")
}
