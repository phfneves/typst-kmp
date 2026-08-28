/*
 * The worker that owns the typst-kmp WebAssembly module.
 *
 * Compiling a document takes seconds, and JavaScript has one thread per context, so the engine
 * lives here rather than on the page. Kotlin drives it through the message protocol below; see
 * `NativeEngine.web.kt`, which is the only thing that ever speaks it.
 *
 * This file is not shipped as an asset. A Gradle task embeds it verbatim into the Kotlin sources,
 * and the engine starts the worker from a `Blob` built out of that string. Two consequences worth
 * knowing when editing it:
 *
 *  * There is nothing to host, nothing for a bundler to resolve, and the worker is always
 *    same-origin — which is what lets `wasmUrl` point at a cross-origin CDN.
 *  * A `blob:` URL has no useful base, so every URL arriving here is already absolute. Never
 *    resolve one relatively.
 *
 * Requests:  { id, op: "init"|"addFont"|"vfsPut"|"vfsPutPackage"|"compile", text?, bytes? }
 * Replies:   { id, ok: true, count?, json?, blobs? } | { id, ok: false, error, fatal? }
 */

'use strict';

let engine = null;

/**
 * Set once the module has aborted.
 *
 * `wasm32-unknown-unknown` cannot unwind, so a Rust panic tears the instance down for good and
 * every later call fails with an opaque error. Remembering it lets the first failure — the one
 * carrying the real message — be the one reported, and marks the reply `fatal` so Kotlin can tell
 * the caller to build a new engine instead of retrying.
 */
let aborted = null;

self.onmessage = async (event) => {
    const request = event.data;
    const id = request.id;
    try {
        if (aborted !== null && request.op !== 'init') {
            throw new Error(aborted);
        }
        const reply = await handle(request);
        reply.id = id;
        reply.ok = true;
        self.postMessage(reply, transferables(reply));
    } catch (failure) {
        const message = describe(failure);
        // A module that aborted stays aborted; the engine object is unusable from here on.
        const fatal = aborted !== null || isAbort(failure);
        if (fatal && aborted === null) {
            aborted = 'the Typst WebAssembly module aborted: ' + message;
        }
        self.postMessage({ id: id, ok: false, error: message, fatal: fatal });
    }
};

async function handle(request) {
    switch (request.op) {
        case 'init': {
            // `no-modules` output installs a global `wasm_bindgen` factory with the exports
            // merged onto it, so this is the whole module bootstrap.
            importScripts(request.glueUrl);
            await wasm_bindgen({ module_or_path: request.wasmUrl });
            engine = new wasm_bindgen.TypstKmpEngine(request.text);
            aborted = null;
            return {};
        }
        case 'addFont':
            return { count: alive().addFont(bytesOf(request)) };
        case 'vfsPut':
            alive().vfsPut(request.text, bytesOf(request));
            return {};
        case 'vfsPutPackage':
            return { count: alive().vfsPutPackage(request.text, bytesOf(request)) };
        case 'compile': {
            const result = alive().compile(request.text);
            try {
                const count = result.blobCount;
                const blobs = new Array(count);
                for (let index = 0; index < count; index++) {
                    blobs[index] = result.blob(index);
                }
                return { json: result.json, blobs: blobs };
            } finally {
                // wasm-bindgen objects are not garbage collected on the Rust side.
                result.free();
            }
        }
        default:
            throw new Error('unknown operation "' + request.op + '"');
    }
}

function alive() {
    if (engine === null) {
        throw new Error('the Typst engine has not been initialised');
    }
    return engine;
}

/** `bytes` is absent for operations that carry none; the Rust side wants an empty slice. */
function bytesOf(request) {
    return request.bytes || new Uint8Array(0);
}

/** Hands the blob buffers over rather than copying them; a rendered page is easily megabytes. */
function transferables(reply) {
    if (!reply.blobs) {
        return [];
    }
    return reply.blobs.map((blob) => blob.buffer);
}

function describe(failure) {
    if (failure instanceof Error) {
        return failure.message || String(failure);
    }
    return String(failure);
}

/**
 * Whether this failure means the module itself is gone rather than the call being rejected.
 *
 * A Rust panic surfaces as a `RuntimeError: unreachable` from the wasm trap; wasm-bindgen's own
 * "null pointer passed to rust" is the same thing seen one call later.
 */
function isAbort(failure) {
    if (typeof RuntimeError === 'function' && failure instanceof RuntimeError) {
        return true;
    }
    const name = failure && failure.name;
    return name === 'RuntimeError' || name === 'CompileError' || name === 'LinkError';
}
