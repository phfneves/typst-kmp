//! wasm-bindgen facade over [`typst_kmp_core`], consumed by the `js` and `wasmJs` Kotlin targets.
//!
//! # Conventions
//!
//! The shape mirrors `typst-kmp-cabi` and `typst-kmp-jni`: only JSON strings and byte buffers
//! cross the boundary, and the engine never performs I/O. What differs is ownership — wasm-bindgen
//! hands JavaScript a real object with a `free()` method, so there are no raw handles and no
//! `out_error` slots. Failures come back as a thrown `Error`.
//!
//! # Threading and panics
//!
//! The module is instantiated inside a dedicated Web Worker, so it is single-threaded by
//! construction: a [`RefCell`] is enough where the other facades need a `Mutex`.
//!
//! `wasm32-unknown-unknown` has no unwinding, so the `catch_unwind` guard the other two facades
//! rely on cannot exist here — a panic aborts the module and every later call on it fails. The
//! Kotlin side treats that as fatal and asks the caller for a fresh engine. [`start`] installs
//! `console_error_panic_hook` so the panic message reaches the console instead of vanishing into
//! an opaque `unreachable`.

use std::cell::RefCell;

use typst_kmp_core::{CompileOutcome, TypstEngine};
use wasm_bindgen::prelude::*;

/// Routes panic messages to `console.error`, which is otherwise all that is lost when a
/// `wasm32-unknown-unknown` module aborts.
#[wasm_bindgen(start)]
pub fn start() {
    console_error_panic_hook::set_once();
}

/// A Typst engine. Call `free()` when done, as with every wasm-bindgen object.
#[wasm_bindgen]
pub struct TypstKmpEngine {
    inner: RefCell<TypstEngine>,
}

/// A compilation result: the response envelope plus the output blobs it describes.
#[wasm_bindgen]
pub struct TypstKmpResult {
    json: String,
    blobs: Vec<Vec<u8>>,
}

fn error(message: impl AsRef<str>) -> JsError {
    JsError::new(message.as_ref())
}

#[wasm_bindgen]
impl TypstKmpEngine {
    /// Creates an engine from its JSON configuration.
    #[wasm_bindgen(constructor)]
    pub fn new(config_json: &str) -> Result<TypstKmpEngine, JsError> {
        let config = if config_json.trim().is_empty() {
            "{}"
        } else {
            config_json
        };
        let engine = typst_kmp_core::engine_from_json(config).map_err(error)?;
        Ok(TypstKmpEngine {
            inner: RefCell::new(engine),
        })
    }

    /// Registers every font face contained in `data`. Returns the number of faces added.
    #[wasm_bindgen(js_name = addFont)]
    pub fn add_font(&self, data: &[u8]) -> Result<u32, JsError> {
        let mut engine = self.inner.try_borrow_mut().map_err(error_reentrant)?;
        Ok(engine.add_font(data.to_vec()) as u32)
    }

    /// Places a file into the virtual file system.
    #[wasm_bindgen(js_name = vfsPut)]
    pub fn vfs_put(&self, path: &str, data: &[u8]) -> Result<(), JsError> {
        let mut engine = self.inner.try_borrow_mut().map_err(error_reentrant)?;
        engine.vfs_put(path, data.to_vec()).map_err(error)
    }

    /// Unpacks a `.tar.gz` package archive into the virtual file system.
    ///
    /// `spec` is the full package specification, e.g. `@preview/cetz:0.3.0`.
    #[wasm_bindgen(js_name = vfsPutPackage)]
    pub fn vfs_put_package(&self, spec: &str, data: &[u8]) -> Result<u32, JsError> {
        let mut engine = self.inner.try_borrow_mut().map_err(error_reentrant)?;
        engine
            .vfs_put_package(spec, data)
            .map(|n| n as u32)
            .map_err(error)
    }

    /// Removes one file from the virtual file system. Returns whether it was there.
    #[wasm_bindgen(js_name = vfsRemove)]
    pub fn vfs_remove(&self, path: &str) -> Result<bool, JsError> {
        let mut engine = self.inner.try_borrow_mut().map_err(error_reentrant)?;
        engine.vfs_remove(path).map_err(error)
    }

    /// Removes every project file, keeping packages. Returns how many were removed.
    #[wasm_bindgen(js_name = vfsClearFiles)]
    pub fn vfs_clear_files(&self) -> Result<u32, JsError> {
        let mut engine = self.inner.try_borrow_mut().map_err(error_reentrant)?;
        Ok(engine.vfs_clear_files() as u32)
    }

    /// Removes every package. Returns how many packages were removed.
    #[wasm_bindgen(js_name = vfsClearPackages)]
    pub fn vfs_clear_packages(&self) -> Result<u32, JsError> {
        let mut engine = self.inner.try_borrow_mut().map_err(error_reentrant)?;
        Ok(engine.vfs_clear_packages() as u32)
    }

    /// Compiles a request.
    ///
    /// The bytes of the request's `files` arrive concatenated in `files`, in the order the JSON
    /// lists their paths, with `file_lens` giving each one's length: two flat buffers cross the
    /// boundary in one copy each, where an array of arrays would need a binding per element.
    ///
    /// Throws only on a hard failure; a failed *compilation* still yields a result whose JSON
    /// carries the diagnostics.
    pub fn compile(
        &self,
        request_json: &str,
        files: &[u8],
        file_lens: &[u32],
    ) -> Result<TypstKmpResult, JsError> {
        let mut split = Vec::with_capacity(file_lens.len());
        let mut rest = files;
        for &len in file_lens {
            let len = len as usize;
            if len > rest.len() {
                return Err(error("file lengths exceed the supplied bytes"));
            }
            let (head, tail) = rest.split_at(len);
            split.push(head.to_vec());
            rest = tail;
        }
        let engine = self.inner.try_borrow().map_err(error_reentrant)?;
        let CompileOutcome { response, blobs } =
            typst_kmp_core::compile_json(&engine, request_json, split).map_err(error)?;
        Ok(TypstKmpResult {
            json: typst_kmp_core::response_json(&response),
            blobs,
        })
    }
}

#[wasm_bindgen]
impl TypstKmpResult {
    /// The response envelope as JSON.
    #[wasm_bindgen(getter)]
    pub fn json(&self) -> String {
        self.json.clone()
    }

    /// Number of output blobs the result carries.
    #[wasm_bindgen(getter, js_name = blobCount)]
    pub fn blob_count(&self) -> u32 {
        self.blobs.len() as u32
    }

    /// Blob `index` as a fresh `Uint8Array`, or an empty one when the index is out of range.
    ///
    /// wasm-bindgen copies the bytes into the JS heap, which is what lets the worker transfer them
    /// to the main thread and drop this result immediately afterwards.
    pub fn blob(&self, index: u32) -> Vec<u8> {
        self.blobs.get(index as usize).cloned().unwrap_or_default()
    }
}

/// Version of this module, mirroring `typst_kmp_version` in the C ABI.
#[wasm_bindgen(js_name = typstKmpVersion)]
pub fn version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}

/// A borrow conflict means JavaScript re-entered the engine, which the worker protocol forbids.
fn error_reentrant<E>(_: E) -> JsError {
    JsError::new("the Typst engine was called re-entrantly")
}
