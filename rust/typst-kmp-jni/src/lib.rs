//! JNI facade over [`typst_kmp_core`].
//!
//! JNI rather than the Panama FFM API on purpose: it is the one mechanism that serves the JVM
//! *and* Android from a single implementation, and it does not require Java 25.
//!
//! Every entry point catches panics and converts failures into a Java exception, because
//! unwinding out of a native frame into the JVM is undefined behaviour.

use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::Mutex;

use jni::objects::{JByteArray, JObject, JObjectArray, JString};
use jni::sys::{jint, jlong, jobjectArray};
use jni::JNIEnv;
use typst_kmp_core::{CompileOutcome, TypstEngine};

const EXCEPTION: &str = "io/github/phfneves/typst/TypstNativeException";

type Handle = Mutex<TypstEngine>;

/// Runs `body`, turning both `Err` and panics into a thrown Java exception and returning
/// `failure` to the caller.
fn guard<'local, T>(
    env: &mut JNIEnv<'local>,
    failure: T,
    body: impl FnOnce(&mut JNIEnv<'local>) -> Result<T, String>,
) -> T {
    let result = catch_unwind(AssertUnwindSafe(|| body(env)));
    let message = match result {
        Ok(Ok(value)) => return value,
        Ok(Err(message)) => message,
        Err(payload) => {
            let detail = payload
                .downcast_ref::<&str>()
                .map(|value| value.to_string())
                .or_else(|| payload.downcast_ref::<String>().cloned())
                .unwrap_or_else(|| "unknown".to_string());
            format!("panic in typst-kmp: {detail}")
        }
    };
    // Never throw over an exception that is already pending.
    if !env.exception_check().unwrap_or(false) {
        let _ = env.throw_new(EXCEPTION, message);
    }
    failure
}

/// Borrows an engine from a handle produced by `engineNew`.
///
/// # Safety
/// The handle must come from `engineNew` and must not have been freed.
unsafe fn engine<'a>(handle: jlong) -> Result<&'a Handle, String> {
    if handle == 0 {
        return Err("engine handle is null (already closed?)".to_string());
    }
    Ok(&*(handle as *const Handle))
}

fn read_string(env: &mut JNIEnv<'_>, value: &JString<'_>, what: &str) -> Result<String, String> {
    env.get_string(value)
        .map(|java| java.into())
        .map_err(|err| format!("failed to read {what}: {err}"))
}

fn read_bytes(env: &mut JNIEnv<'_>, value: &JByteArray<'_>) -> Result<Vec<u8>, String> {
    env.convert_byte_array(value)
        .map_err(|err| format!("failed to read byte array: {err}"))
}

#[no_mangle]
pub extern "system" fn Java_io_github_phfneves_typst_internal_TypstNative_engineNew<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    config_json: JString<'local>,
) -> jlong {
    guard(&mut env, 0, |env| {
        let config = read_string(env, &config_json, "config JSON")?;
        let engine = typst_kmp_core::engine_from_json(&config)?;
        Ok(Box::into_raw(Box::new(Mutex::new(engine))) as jlong)
    })
}

#[no_mangle]
pub extern "system" fn Java_io_github_phfneves_typst_internal_TypstNative_engineFree<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    handle: jlong,
) {
    guard(&mut env, (), |_| {
        if handle != 0 {
            unsafe { drop(Box::from_raw(handle as *mut Handle)) };
        }
        Ok(())
    })
}

#[no_mangle]
pub extern "system" fn Java_io_github_phfneves_typst_internal_TypstNative_engineAddFont<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    handle: jlong,
    data: JByteArray<'local>,
) -> jint {
    guard(&mut env, -1, |env| {
        let bytes = read_bytes(env, &data)?;
        let engine = unsafe { engine(handle) }?;
        let mut locked = engine.lock().map_err(|_| "engine lock poisoned")?;
        Ok(locked.add_font(bytes) as jint)
    })
}

#[no_mangle]
pub extern "system" fn Java_io_github_phfneves_typst_internal_TypstNative_vfsPut<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    handle: jlong,
    path: JString<'local>,
    data: JByteArray<'local>,
) {
    guard(&mut env, (), |env| {
        let path = read_string(env, &path, "path")?;
        let bytes = read_bytes(env, &data)?;
        let engine = unsafe { engine(handle) }?;
        let mut locked = engine.lock().map_err(|_| "engine lock poisoned")?;
        locked.vfs_put(&path, bytes)
    })
}

#[no_mangle]
pub extern "system" fn Java_io_github_phfneves_typst_internal_TypstNative_vfsPutPackage<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    handle: jlong,
    spec: JString<'local>,
    data: JByteArray<'local>,
) -> jint {
    guard(&mut env, -1, |env| {
        let spec = read_string(env, &spec, "package spec")?;
        let archive = read_bytes(env, &data)?;
        let engine = unsafe { engine(handle) }?;
        let mut locked = engine.lock().map_err(|_| "engine lock poisoned")?;
        Ok(locked.vfs_put_package(&spec, &archive)? as jint)
    })
}

/// Returns `Object[] { String responseJson, byte[][] blobs }`.
///
/// Handing the blobs back in one call keeps the result lifetime entirely on the Java heap, so
/// there is no native result handle for callers to leak.
#[no_mangle]
pub extern "system" fn Java_io_github_phfneves_typst_internal_TypstNative_compile<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
    handle: jlong,
    request_json: JString<'local>,
) -> jobjectArray {
    let null = JObject::null().into_raw();
    guard(&mut env, null, |env| {
        let request = read_string(env, &request_json, "request JSON")?;
        let engine = unsafe { engine(handle) }?;
        let locked = engine.lock().map_err(|_| "engine lock poisoned")?;
        let CompileOutcome { response, blobs } = typst_kmp_core::compile_json(&locked, &request)?;
        drop(locked);

        let json = env
            .new_string(typst_kmp_core::response_json(&response))
            .map_err(|err| format!("failed to allocate response string: {err}"))?;

        let byte_array_class = env
            .find_class("[B")
            .map_err(|err| format!("failed to find byte[] class: {err}"))?;
        let blob_array: JObjectArray<'_> = env
            .new_object_array(blobs.len() as jint, &byte_array_class, JObject::null())
            .map_err(|err| format!("failed to allocate blob array: {err}"))?;
        for (index, blob) in blobs.iter().enumerate() {
            let element = env
                .byte_array_from_slice(blob)
                .map_err(|err| format!("failed to allocate blob {index}: {err}"))?;
            env.set_object_array_element(&blob_array, index as jint, &element)
                .map_err(|err| format!("failed to store blob {index}: {err}"))?;
        }

        let object_class = env
            .find_class("java/lang/Object")
            .map_err(|err| format!("failed to find Object class: {err}"))?;
        let result: JObjectArray<'_> = env
            .new_object_array(2, &object_class, JObject::null())
            .map_err(|err| format!("failed to allocate result array: {err}"))?;
        env.set_object_array_element(&result, 0, &json)
            .map_err(|err| format!("failed to store response JSON: {err}"))?;
        env.set_object_array_element(&result, 1, &blob_array)
            .map_err(|err| format!("failed to store blobs: {err}"))?;

        Ok(result.into_raw())
    })
}

#[no_mangle]
pub extern "system" fn Java_io_github_phfneves_typst_internal_TypstNative_nativeVersion<'local>(
    mut env: JNIEnv<'local>,
    _this: JObject<'local>,
) -> jni::sys::jstring {
    let null = JObject::null().into_raw();
    guard(&mut env, null, |env| {
        env.new_string(env!("CARGO_PKG_VERSION"))
            .map(|value| value.into_raw())
            .map_err(|err| format!("failed to allocate version string: {err}"))
    })
}
