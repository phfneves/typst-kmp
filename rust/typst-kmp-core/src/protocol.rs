//! Serde types for the JSON protocol spoken between Kotlin and Rust.
//!
//! Only JSON strings and raw byte buffers ever cross the FFI boundary, which keeps the
//! platform-specific Kotlin layer (JNI / cinterop / wasm-bindgen) tiny and identical in shape.

use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;

fn default_true() -> bool {
    true
}

/// Engine-level configuration, supplied once at construction.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct EngineConfig {
    /// Whether to register the fonts bundled in the binary (requires the `embed-fonts` feature).
    pub embed_default_fonts: bool,
}

impl Default for EngineConfig {
    fn default() -> Self {
        Self {
            embed_default_fonts: true,
        }
    }
}

/// A single compilation request.
#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct CompileRequest {
    /// VFS path of the entry point, e.g. `/main.typ`.
    pub main: String,
    /// Values exposed to the document through `sys.inputs`.
    #[serde(default)]
    pub inputs: BTreeMap<String, String>,
    /// Which artifacts to produce. Producing several at once reuses one layout pass.
    pub outputs: Vec<OutputSpec>,
    /// The "current" date, supplied by Kotlin so that Rust never reads the system clock.
    #[serde(default)]
    pub now: Option<DateSpec>,
}

#[derive(Debug, Clone, Copy, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct DateSpec {
    pub year: i32,
    pub month: u8,
    pub day: u8,
    pub hour: u8,
    pub minute: u8,
    pub second: u8,
    /// Minutes east of UTC. `sys` exposes this to `datetime.today(offset: …)`.
    #[serde(default)]
    pub offset_minutes: i32,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(
    tag = "type",
    rename_all = "camelCase",
    rename_all_fields = "camelCase"
)]
pub enum OutputSpec {
    Pdf {
        #[serde(default)]
        ident: Option<String>,
        #[serde(default)]
        creator: Option<String>,
        #[serde(default)]
        standards: Vec<String>,
        #[serde(default)]
        pretty: bool,
    },
    Svg {
        /// When true, emits one blob containing every page; otherwise one blob per page.
        #[serde(default)]
        merged: bool,
        #[serde(default)]
        pretty: bool,
    },
    Png {
        #[serde(default = "one")]
        pixel_per_pt: f32,
        #[serde(default)]
        merged: bool,
    },
    Query {
        selector: String,
        #[serde(default)]
        field: Option<String>,
        #[serde(default)]
        one: bool,
        #[serde(default = "default_true")]
        pretty: bool,
    },
}

fn one() -> f32 {
    1.0
}

/// Response envelope. Output bytes travel out of band as an ordered list of blobs; each
/// [`OutputMeta`] says which slice of that list belongs to which requested output.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CompileResponse {
    pub ok: bool,
    pub outputs: Vec<OutputMeta>,
    pub diagnostics: Vec<Diagnostic>,
    /// Files and packages the compilation asked for but the VFS could not provide.
    ///
    /// This is what drives the resolution loop in `commonMain`: Kotlin fetches these,
    /// puts them into the VFS and retries. Recording them here structurally is far more
    /// robust than pattern-matching on diagnostic messages.
    pub missing: Vec<Missing>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OutputMeta {
    pub kind: String,
    /// Index of the first blob belonging to this output.
    pub blob_start: usize,
    pub blob_count: usize,
}

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord, Serialize)]
#[serde(tag = "kind", rename_all = "camelCase")]
pub enum Missing {
    File {
        path: String,
    },
    Package {
        namespace: String,
        name: String,
        version: String,
    },
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Diagnostic {
    pub severity: Severity,
    pub message: String,
    /// VFS path the diagnostic points at, when it has a span.
    pub path: Option<String>,
    pub start: Option<usize>,
    pub end: Option<usize>,
    /// 1-based, for display.
    pub line: Option<usize>,
    /// 1-based, for display.
    pub column: Option<usize>,
    pub hints: Vec<String>,
    pub trace: Vec<TracePoint>,
}

#[derive(Debug, Clone, Copy, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum Severity {
    Error,
    Warning,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct TracePoint {
    pub message: String,
    pub path: Option<String>,
    pub line: Option<usize>,
    pub column: Option<usize>,
}
