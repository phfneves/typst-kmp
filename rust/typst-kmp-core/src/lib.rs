//! Platform-agnostic Typst compilation engine behind the `typst-kmp` Kotlin bindings.
//!
//! # Design
//!
//! This crate performs **no I/O whatsoever** — no filesystem, no network, no clock. Everything a
//! document reads must be placed in the in-memory VFS by the host first. When the compiler asks
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

pub mod engine;
pub mod pkg;
pub mod protocol;
pub mod vfs;
pub mod world;

pub use engine::{compile_json, response_json, CompileOutcome, TypstEngine};
pub use protocol::{CompileRequest, CompileResponse, EngineConfig, Missing};

/// Builds an engine from its JSON configuration.
pub fn engine_from_json(config_json: &str) -> Result<TypstEngine, String> {
    let config: EngineConfig = if config_json.trim().is_empty() {
        EngineConfig::default()
    } else {
        serde_json::from_str(config_json).map_err(|err| format!("invalid engine config: {err}"))?
    };
    Ok(TypstEngine::new(config))
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

        let outcome = engine.compile(pdf_request("/main.typ")).unwrap();

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
        let outcome = engine.compile(pdf_request("/main.typ")).unwrap();

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

        let outcome = engine.compile(pdf_request("/main.typ")).unwrap();

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

        let outcome = engine.compile(pdf_request("/main.typ")).unwrap();

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

        let outcome = engine.compile(pdf_request("/main.typ")).unwrap();

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
        let outcome = engine.compile(request).unwrap();

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
        let outcome = engine.compile(request).unwrap();

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
}
