//! C ABI facade over [`typst_kmp_core`], consumed by Kotlin/Native through cinterop.
//!
//! # Conventions
//!
//! * The caller owns every input buffer and string; this library copies what it needs.
//! * Handles returned here are owned by the caller and must be released with the matching
//!   `*_free` function.
//! * Buffers obtained from a result handle stay valid until that result is freed.
//! * Fallible functions take an `out_error` slot. On failure they write a NUL-terminated,
//!   caller-owned message into it, to be released with [`typst_kmp_string_free`].
//! * Every entry point catches panics. Unwinding across the FFI boundary is undefined behaviour,
//!   and this library is loaded into a host runtime that must not be taken down by a bug here.
//!
//! # Safety
//!
//! Every function in this crate shares one contract, stated here rather than repeated on each
//! declaration:
//!
//! * Handle arguments must either be null or have come from the matching constructor
//!   ([`typst_kmp_engine_new`], [`typst_kmp_compile`]) and must not have been freed. Passing null
//!   is always safe: it produces an error or is a no-op, never undefined behaviour.
//! * `*const c_char` arguments must point at a NUL-terminated, valid UTF-8 string, or be null.
//! * A `(data, len)` pair must describe a readable region of `len` bytes, or be `(null, 0)`.
//! * Out-parameters must either be null or point at writable storage of the right type.
//! * A handle must not be freed while another thread is still using it. Concurrent *use* is fine;
//!   the engine serialises access internally.
#![allow(clippy::missing_safety_doc)]

use std::ffi::{c_char, c_int, CStr, CString};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::ptr;
use std::sync::Mutex;

use typst_kmp_core::{CompileOutcome, TypstEngine};

/// Opaque engine handle.
pub struct TypstKmpEngine {
    inner: Mutex<TypstEngine>,
}

/// Opaque compilation result handle.
pub struct TypstKmpResult {
    json: CString,
    blobs: Vec<Vec<u8>>,
}

const OK: c_int = 0;
const ERR: c_int = -1;

// --- helpers ---------------------------------------------------------------------------------

fn set_error(slot: *mut *mut c_char, message: impl Into<Vec<u8>>) {
    if slot.is_null() {
        return;
    }
    let owned = CString::new(message).unwrap_or_else(|_| {
        CString::new("error message contained an interior NUL byte").expect("static string")
    });
    unsafe { *slot = owned.into_raw() };
}

/// Runs `body`, converting both panics and `Err` into the `out_error` convention.
fn guard<T>(
    out_error: *mut *mut c_char,
    failure: T,
    body: impl FnOnce() -> Result<T, String>,
) -> T {
    match catch_unwind(AssertUnwindSafe(body)) {
        Ok(Ok(value)) => value,
        Ok(Err(message)) => {
            set_error(out_error, message);
            failure
        }
        Err(payload) => {
            let message = payload
                .downcast_ref::<&str>()
                .map(|value| value.to_string())
                .or_else(|| payload.downcast_ref::<String>().cloned())
                .unwrap_or_else(|| "panic in typst-kmp".to_string());
            set_error(out_error, format!("panic: {message}"));
            failure
        }
    }
}

unsafe fn as_str<'a>(pointer: *const c_char, what: &str) -> Result<&'a str, String> {
    if pointer.is_null() {
        return Err(format!("{what} must not be null"));
    }
    CStr::from_ptr(pointer)
        .to_str()
        .map_err(|_| format!("{what} must be valid UTF-8"))
}

unsafe fn as_slice<'a>(data: *const u8, len: usize) -> &'a [u8] {
    if data.is_null() || len == 0 {
        &[]
    } else {
        std::slice::from_raw_parts(data, len)
    }
}

// --- engine lifecycle ------------------------------------------------------------------------

/// Creates an engine from its JSON configuration. Returns null on failure.
#[no_mangle]
pub unsafe extern "C" fn typst_kmp_engine_new(
    config_json: *const c_char,
    out_error: *mut *mut c_char,
) -> *mut TypstKmpEngine {
    guard(out_error, ptr::null_mut(), || {
        let config = if config_json.is_null() {
            "{}"
        } else {
            as_str(config_json, "config_json")?
        };
        let engine = typst_kmp_core::engine_from_json(config)?;
        Ok(Box::into_raw(Box::new(TypstKmpEngine {
            inner: Mutex::new(engine),
        })))
    })
}

/// Releases an engine. Passing null is a no-op.
#[no_mangle]
pub unsafe extern "C" fn typst_kmp_engine_free(engine: *mut TypstKmpEngine) {
    if engine.is_null() {
        return;
    }
    let _ = catch_unwind(AssertUnwindSafe(|| drop(Box::from_raw(engine))));
}

/// Registers every font face contained in `data`. Returns the number of faces added, or -1.
#[no_mangle]
pub unsafe extern "C" fn typst_kmp_engine_add_font(
    engine: *mut TypstKmpEngine,
    data: *const u8,
    len: usize,
    out_error: *mut *mut c_char,
) -> c_int {
    guard(out_error, ERR, || {
        let engine = engine.as_ref().ok_or("engine must not be null")?;
        let bytes = as_slice(data, len).to_vec();
        let mut locked = engine.inner.lock().map_err(|_| "engine lock poisoned")?;
        Ok(locked.add_font(bytes) as c_int)
    })
}

/// Places a file into the virtual file system.
#[no_mangle]
pub unsafe extern "C" fn typst_kmp_engine_vfs_put(
    engine: *mut TypstKmpEngine,
    path: *const c_char,
    data: *const u8,
    len: usize,
    out_error: *mut *mut c_char,
) -> c_int {
    guard(out_error, ERR, || {
        let engine = engine.as_ref().ok_or("engine must not be null")?;
        let path = as_str(path, "path")?;
        let bytes = as_slice(data, len).to_vec();
        let mut locked = engine.inner.lock().map_err(|_| "engine lock poisoned")?;
        locked.vfs_put(path, bytes)?;
        Ok(OK)
    })
}

/// Unpacks a `.tar.gz` package archive into the virtual file system.
///
/// `spec` is the full package specification, e.g. `@preview/cetz:0.3.0`.
#[no_mangle]
pub unsafe extern "C" fn typst_kmp_engine_vfs_put_package(
    engine: *mut TypstKmpEngine,
    spec: *const c_char,
    data: *const u8,
    len: usize,
    out_error: *mut *mut c_char,
) -> c_int {
    guard(out_error, ERR, || {
        let engine = engine.as_ref().ok_or("engine must not be null")?;
        let spec = as_str(spec, "spec")?;
        let archive = as_slice(data, len);
        let mut locked = engine.inner.lock().map_err(|_| "engine lock poisoned")?;
        Ok(locked.vfs_put_package(spec, archive)? as c_int)
    })
}

// --- compilation -----------------------------------------------------------------------------

/// Compiles a request. Returns null only on a hard failure; a failed *compilation* still yields a
/// result whose JSON carries the diagnostics.
#[no_mangle]
pub unsafe extern "C" fn typst_kmp_compile(
    engine: *mut TypstKmpEngine,
    request_json: *const c_char,
    out_error: *mut *mut c_char,
) -> *mut TypstKmpResult {
    guard(out_error, ptr::null_mut(), || {
        let engine = engine.as_ref().ok_or("engine must not be null")?;
        let request = as_str(request_json, "request_json")?;
        let locked = engine.inner.lock().map_err(|_| "engine lock poisoned")?;
        let CompileOutcome { response, blobs } = typst_kmp_core::compile_json(&locked, request)?;
        let json = CString::new(typst_kmp_core::response_json(&response))
            .map_err(|_| "response JSON contained an interior NUL byte")?;
        Ok(Box::into_raw(Box::new(TypstKmpResult { json, blobs })))
    })
}

/// The response envelope as a NUL-terminated UTF-8 JSON string, owned by the result.
#[no_mangle]
pub unsafe extern "C" fn typst_kmp_result_json(result: *const TypstKmpResult) -> *const c_char {
    match result.as_ref() {
        Some(result) => result.json.as_ptr(),
        None => ptr::null(),
    }
}

/// Number of output blobs the result carries.
#[no_mangle]
pub unsafe extern "C" fn typst_kmp_result_blob_count(result: *const TypstKmpResult) -> usize {
    result
        .as_ref()
        .map(|result| result.blobs.len())
        .unwrap_or(0)
}

/// Pointer to blob `index`, writing its length into `out_len`. Valid until the result is freed.
#[no_mangle]
pub unsafe extern "C" fn typst_kmp_result_blob(
    result: *const TypstKmpResult,
    index: usize,
    out_len: *mut usize,
) -> *const u8 {
    if !out_len.is_null() {
        *out_len = 0;
    }
    let Some(result) = result.as_ref() else {
        return ptr::null();
    };
    let Some(blob) = result.blobs.get(index) else {
        return ptr::null();
    };
    if !out_len.is_null() {
        *out_len = blob.len();
    }
    blob.as_ptr()
}

/// Releases a result. Passing null is a no-op.
#[no_mangle]
pub unsafe extern "C" fn typst_kmp_result_free(result: *mut TypstKmpResult) {
    if result.is_null() {
        return;
    }
    let _ = catch_unwind(AssertUnwindSafe(|| drop(Box::from_raw(result))));
}

// --- misc ------------------------------------------------------------------------------------

/// Releases a string produced by this library (currently only error messages).
#[no_mangle]
pub unsafe extern "C" fn typst_kmp_string_free(value: *mut c_char) {
    if value.is_null() {
        return;
    }
    let _ = catch_unwind(AssertUnwindSafe(|| drop(CString::from_raw(value))));
}

/// Version of this native library, as a static NUL-terminated string.
#[no_mangle]
pub extern "C" fn typst_kmp_version() -> *const c_char {
    concat!(env!("CARGO_PKG_VERSION"), "\0").as_ptr() as *const c_char
}
