//! Platform-agnostic Typst compilation engine behind the `typst-kmp` Kotlin bindings.
//!
//! # Design
//!
//! This crate performs **no I/O** on a document's behalf — no filesystem, no network, no clock.
//! Everything a document reads must be placed in the in-memory VFS by the host first. The one
//! exception is fonts the host names by path, or asks for from the system directories, which are
//! read from disk because holding them all in memory is exactly what they exist to avoid. When the compiler asks
//! for something that is not there, the failure is reported structurally in
//! [`protocol::CompileResponse::missing`], and the Kotlin side fetches it and retries.
//!
//! That single decision buys three things at once:
//!
//! * Kotlin/Native needs no callbacks across the FFI boundary, so there is no `staticCFunction`
//!   with captured state and no threading hazard.
//! * The sandbox is airtight by construction: path traversal is impossible when there is no path.
//! * The same code works in a browser, where a package download is inherently asynchronous and
//!   therefore cannot happen inside a synchronous call into WebAssembly.
//!
//! # Boundary
//!
//! Only JSON strings and raw byte buffers cross the FFI boundary, which keeps every
//! platform-specific binding layer (`typst-kmp-cabi`, `typst-kmp-jni`) small and identical in
//! shape.

pub mod cancel;
pub mod engine;
pub mod fonts;
pub mod pkg;
pub mod protocol;
pub mod vfs;
pub mod world;

pub use cancel::{CancelToken, CANCELLED};
pub use engine::{compile_json, inspect_json, response_json, CompileOutcome, TypstEngine};
pub use protocol::{CompileRequest, CompileResponse, EngineConfig, Missing};

/// Builds an engine from its JSON configuration.
pub fn engine_from_json(config_json: &str) -> Result<TypstEngine, String> {
    let config: EngineConfig = if config_json.trim().is_empty() {
        EngineConfig::default()
    } else {
        serde_json::from_str(config_json).map_err(|err| format!("invalid engine config: {err}"))?
    };
    TypstEngine::new(config)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::{CompileRequest, OutputSpec};

    fn engine() -> TypstEngine {
        engine_from_json("{}").unwrap()
    }

    fn pdf_request(main: &str) -> CompileRequest {
        CompileRequest {
            main: main.to_string(),
            files: Vec::new(),
            inputs: Default::default(),
            outputs: vec![OutputSpec::Pdf {
                ident: None,
                creator: None,
                standards: Vec::new(),
                pretty: false,
            }],
            now: None,
        }
    }

    #[test]
    fn compiles_a_pdf() {
        let mut engine = engine();
        engine
            .vfs_put(
                "/main.typ",
                "#set page(width: 10cm, height: auto)\nOlá"
                    .as_bytes()
                    .to_vec(),
            )
            .unwrap();

        let outcome = engine
            .compile(pdf_request("/main.typ"), Vec::new(), None)
            .unwrap();

        assert!(
            outcome.response.ok,
            "diagnostics: {:?}",
            outcome.response.diagnostics
        );
        assert_eq!(outcome.blobs.len(), 1);
        assert!(outcome.blobs[0].starts_with(b"%PDF-"));
    }

    #[test]
    fn reports_a_missing_entry_point() {
        let engine = engine();
        let outcome = engine
            .compile(pdf_request("/main.typ"), Vec::new(), None)
            .unwrap();

        assert!(!outcome.response.ok);
        assert_eq!(
            outcome.response.missing,
            vec![Missing::File {
                path: "/main.typ".to_string()
            }]
        );
    }

    #[test]
    fn reports_a_missing_import_structurally() {
        let mut engine = engine();
        engine
            .vfs_put(
                "/main.typ",
                b"#import \"/helpers.typ\": greet\n#greet()".to_vec(),
            )
            .unwrap();

        let outcome = engine
            .compile(pdf_request("/main.typ"), Vec::new(), None)
            .unwrap();

        assert!(!outcome.response.ok);
        assert!(
            outcome.response.missing.contains(&Missing::File {
                path: "/helpers.typ".to_string()
            }),
            "missing: {:?}",
            outcome.response.missing
        );
    }

    #[test]
    fn reports_a_missing_package_structurally() {
        let mut engine = engine();
        engine
            .vfs_put(
                "/main.typ",
                b"#import \"@preview/example:0.1.0\": *".to_vec(),
            )
            .unwrap();

        let outcome = engine
            .compile(pdf_request("/main.typ"), Vec::new(), None)
            .unwrap();

        assert!(!outcome.response.ok);
        assert!(
            outcome.response.missing.iter().any(|miss| matches!(
                miss,
                Missing::Package { name, version, .. } if name == "example" && version == "0.1.0"
            )),
            "missing: {:?}",
            outcome.response.missing
        );
    }

    #[test]
    fn surfaces_compilation_errors_with_a_location() {
        let mut engine = engine();
        engine
            .vfs_put("/main.typ", b"#panic(\"boom\")".to_vec())
            .unwrap();

        let outcome = engine
            .compile(pdf_request("/main.typ"), Vec::new(), None)
            .unwrap();

        assert!(!outcome.response.ok);
        let error = outcome
            .response
            .diagnostics
            .iter()
            .find(|diag| matches!(diag.severity, protocol::Severity::Error))
            .expect("expected an error diagnostic");
        assert_eq!(error.path.as_deref(), Some("/main.typ"));
        assert_eq!(error.line, Some(1));
    }

    #[test]
    fn exposes_inputs_to_the_document() {
        let mut engine = engine();
        engine
            .vfs_put("/main.typ", b"#sys.inputs.name".to_vec())
            .unwrap();

        let mut request = pdf_request("/main.typ");
        request
            .inputs
            .insert("name".to_string(), "Pedro".to_string());
        let outcome = engine.compile(request, Vec::new(), None).unwrap();

        assert!(
            outcome.response.ok,
            "diagnostics: {:?}",
            outcome.response.diagnostics
        );
    }

    #[test]
    fn renders_svg_and_png_in_one_pass() {
        let mut engine = engine();
        engine
            .vfs_put(
                "/main.typ",
                b"#set page(width: 5cm, height: 5cm)\nA\n#pagebreak()\nB".to_vec(),
            )
            .unwrap();

        let mut request = pdf_request("/main.typ");
        request.outputs = vec![
            OutputSpec::Svg {
                merged: false,
                pretty: false,
            },
            OutputSpec::Png {
                pixel_per_pt: 1.0,
                merged: false,
            },
        ];
        let outcome = engine.compile(request, Vec::new(), None).unwrap();

        assert!(
            outcome.response.ok,
            "diagnostics: {:?}",
            outcome.response.diagnostics
        );
        assert_eq!(outcome.response.outputs.len(), 2);
        assert_eq!(
            outcome.response.outputs[0].blob_count, 2,
            "one SVG blob per page"
        );
        assert_eq!(
            outcome.response.outputs[1].blob_count, 2,
            "one PNG blob per page"
        );
        assert!(outcome.blobs[0].starts_with(b"<svg"));
        assert_eq!(&outcome.blobs[2][1..4], b"PNG");
    }

    #[test]
    fn request_files_shadow_the_vfs_and_do_not_stay() {
        let mut engine = engine();
        engine
            .vfs_put("/data.typ", b"#let name = \"vfs\"".to_vec())
            .unwrap();

        let mut request = pdf_request("/main.typ");
        request.files = vec!["/main.typ".to_string(), "/data.typ".to_string()];
        request.outputs = vec![OutputSpec::Query {
            selector: "metadata".to_string(),
            field: Some("value".to_string()),
            one: true,
            pretty: false,
        }];
        let files = vec![
            b"#import \"/data.typ\": name\n#metadata(name)".to_vec(),
            b"#let name = \"overlay\"".to_vec(),
        ];
        let outcome = engine.compile(request, files, None).unwrap();

        assert!(
            outcome.response.ok,
            "diagnostics: {:?}",
            outcome.response.diagnostics
        );
        assert_eq!(outcome.blobs[0], b"\"overlay\"");

        let again = engine
            .compile(pdf_request("/main.typ"), Vec::new(), None)
            .unwrap();
        assert_eq!(
            again.response.missing,
            vec![Missing::File {
                path: "/main.typ".to_string()
            }]
        );
    }

    #[test]
    fn rejects_a_file_count_mismatch() {
        let engine = engine();
        let mut request = pdf_request("/main.typ");
        request.files = vec!["/main.typ".to_string()];
        assert!(engine.compile(request, Vec::new(), None).is_err());
    }

    #[test]
    fn renders_a_diagnostic_like_the_cli() {
        let engine = engine();
        let mut request = pdf_request("/main.typ");
        request.files = vec!["/main.typ".to_string()];
        let outcome = engine
            .compile(request, vec![b"Hello\n#unknown-name here".to_vec()], None)
            .unwrap();

        let error = &outcome.response.diagnostics[0];
        assert_eq!(
            error.rendered,
            concat!(
                "error: unknown variable: unknown-name\n",
                "  ┌─ /main.typ:2:2\n",
                "  │\n",
                "2 │ #unknown-name here\n",
                "  │  ^^^^^^^^^^^^\n",
                "  │\n",
                "  = hint: if you meant to use subtraction, ",
                "try adding spaces around the minus sign: `unknown - name`",
            ),
        );
    }

    #[test]
    fn inspects_files_packages_and_fonts() {
        let mut engine = engine();
        engine.vfs_put("/b.typ", b"bb".to_vec()).unwrap();
        engine.vfs_put("/a.typ", b"a".to_vec()).unwrap();

        let inspection = engine.inspect();

        let files: Vec<_> = inspection
            .files
            .iter()
            .map(|entry| (entry.path.as_str(), entry.size))
            .collect();
        assert_eq!(files, vec![("/a.typ", 1), ("/b.typ", 2)]);
        assert!(inspection.packages.is_empty());
        assert!(
            inspection
                .fonts
                .iter()
                .any(|family| family.name == "Libertinus Serif" && family.faces > 0),
            "fonts: {:?}",
            inspection.fonts
        );
    }

    fn heavy_document() -> &'static str {
        "#set page(width: 12cm, height: auto)\n#for i in range(120) [= Section #i\n#lorem(60)\n]"
    }

    fn pdf_of(outcome: CompileOutcome) -> Vec<u8> {
        assert!(outcome.response.ok, "{:?}", outcome.response.diagnostics);
        outcome.blobs.into_iter().next().unwrap()
    }

    #[test]
    fn a_cancelled_token_stops_the_compilation() {
        let mut engine = engine();
        engine.vfs_put("/main.typ", b"Hello".to_vec()).unwrap();
        let token = CancelToken::new();
        token.cancel();

        let result = engine.compile(pdf_request("/main.typ"), Vec::new(), Some(&token));

        assert_eq!(result.err().as_deref(), Some(CANCELLED));
    }

    /// The spike behind cancellation: interrupt the same compilation at many different points,
    /// then check the engine still produces exactly what an untouched one does. A cache left
    /// inconsistent by the unwind, or a poisoned lock, would show up as a different PDF, an
    /// error or a panic.
    #[test]
    fn cancelled_compilations_leave_the_engine_intact() {
        let mut reference = engine();
        reference
            .vfs_put("/main.typ", heavy_document().as_bytes().to_vec())
            .unwrap();
        let expected = pdf_of(
            reference
                .compile(pdf_request("/main.typ"), Vec::new(), None)
                .unwrap(),
        );
        drop(reference);
        typst::comemo::evict(0);

        let mut engine = engine();
        engine
            .vfs_put("/main.typ", heavy_document().as_bytes().to_vec())
            .unwrap();
        let mut cancelled = 0;
        for delay in [0u64, 1, 2, 5, 10, 20, 40, 80] {
            let token = CancelToken::new();
            let result = std::thread::scope(|scope| {
                scope.spawn(|| {
                    std::thread::sleep(std::time::Duration::from_millis(delay));
                    token.cancel();
                });
                engine.compile(pdf_request("/main.typ"), Vec::new(), Some(&token))
            });
            match result {
                Err(message) => {
                    assert_eq!(message, CANCELLED);
                    cancelled += 1;
                }
                Ok(outcome) => assert_eq!(pdf_of(outcome), expected),
            }
        }
        assert!(cancelled > 0, "no compilation was ever interrupted");

        let after = engine
            .compile(pdf_request("/main.typ"), Vec::new(), None)
            .unwrap();
        assert_eq!(pdf_of(after), expected);
    }

    fn temp_dir(name: &str) -> std::path::PathBuf {
        let dir = std::env::temp_dir().join(format!("typst-kmp-{name}-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn font_paths_are_indexed_now_and_loaded_when_used() {
        let dir = temp_dir("fonts");
        let nested = dir.join("nested");
        std::fs::create_dir_all(&nested).unwrap();
        for (index, data) in typst_assets::fonts().enumerate() {
            std::fs::write(nested.join(format!("font-{index}.otf")), data).unwrap();
        }
        std::fs::write(dir.join("README.txt"), "not a font").unwrap();

        let config = format!(
            r#"{{"embedDefaultFonts": false, "fontPaths": [{:?}]}}"#,
            dir.display().to_string()
        );
        let mut engine = engine_from_json(&config).unwrap();
        let families: Vec<_> = engine.inspect().fonts.into_iter().map(|f| f.name).collect();
        assert!(
            families.contains(&"DejaVu Sans Mono".to_string()),
            "{families:?}"
        );
        assert!(engine.fonts().slots().iter().all(|slot| !slot.is_loaded()));

        engine
            .vfs_put(
                "/main.typ",
                b"#set text(font: \"DejaVu Sans Mono\")\nHello".to_vec(),
            )
            .unwrap();
        pdf_of(
            engine
                .compile(pdf_request("/main.typ"), Vec::new(), None)
                .unwrap(),
        );

        let slots = engine.fonts().slots();
        let loaded = slots.iter().filter(|slot| slot.is_loaded()).count();
        assert!(
            loaded > 0 && loaded < slots.len(),
            "{loaded} of {}",
            slots.len()
        );
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn rejects_a_font_path_that_does_not_exist() {
        let error = engine_from_json(r#"{"fontPaths": ["/no/such/font.ttf"]}"#)
            .err()
            .unwrap();
        assert!(error.contains("/no/such/font.ttf"), "{error}");
    }

    #[test]
    fn rejects_a_font_path_that_is_not_a_font() {
        let dir = temp_dir("not-a-font");
        let file = dir.join("notes.ttf");
        std::fs::write(&file, "not a font").unwrap();
        let config = format!(r#"{{"fontPaths": [{:?}]}}"#, file.display().to_string());
        assert!(engine_from_json(&config).is_err());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn includes_the_system_fonts_on_request() {
        let engine =
            engine_from_json(r#"{"embedDefaultFonts": false, "includeSystemFonts": true}"#)
                .unwrap();
        // Every macOS has fonts in /System/Library/Fonts; a bare Linux container may have none.
        if cfg!(target_os = "macos") {
            assert!(!engine.inspect().fonts.is_empty());
        }
    }
}
