# typst-kmp

The [Typst](https://typst.app) typesetting compiler as a Kotlin Multiplatform library — no `typst`
binary to install, no subprocess, no server round-trip. The compiler is embedded and runs in
process on Android, the JVM, iOS, macOS, Linux and Windows.

> **Status: work in progress.** The same 13-test `commonTest` suite runs green on the JVM (JNI),
> on Kotlin/Native (cinterop) and on an Android emulator (instrumented), on top of 15 Rust tests.
> Each run compiles a real multi-page document and writes the PDF out for inspection. Apple and
> Linux targets are configured but have only ever been built by CI, and nothing has been published.

```kotlin
Typst.create().use { typst ->
    val result = typst.compile(
        CompileRequest.of(
            source = """
                #set page(width: 10cm, height: auto)
                = Olá, #sys.inputs.at("name")
                Escrito com Typst.
            """.trimIndent(),
            inputs = mapOf("name" to "Pedro"),
        ),
    )
    val pdf: ByteArray = result.getOrThrow().filterIsInstance<Output.Pdf>().single().bytes
}
```

## Why not just wrap `java-typst`?

[`g0ddest/java-typst`](https://github.com/g0ddest/java-typst) solves the same problem for Java, and
its key idea is reused here: the hard part is a **Rust crate exposing a plain C ABI**, not the
bindings. What could not be reused is the consumption layer — it uses the Panama FFM API, which
requires Java 25 and does not exist on Android.

Two further design choices differ:

* **The Rust side performs no I/O at all** — no filesystem, no network, no clock. Everything the
  document reads comes from an in-memory VFS the host fills in.
* **No callbacks cross the FFI boundary.** When something is missing, the compiler reports it
  structurally and Kotlin resolves it and retries.

## Architecture

```
                       commonMain  — public API, JSON encoding, resolution loop
                            │
        ┌───────────────────┼───────────────────┐
        │                   │                   │
   jvmCommonMain        nativeMain          (jsMain/wasmJsMain — later)
   JNI bindings         cinterop
        │                   │                   │
 typst-kmp-jni       typst-kmp-cabi       typst-kmp-wasm
   (cdylib)        (staticlib + header)    (wasm-bindgen)
        └───────────────────┼───────────────────┘
                            │
                     typst-kmp-core
            World · VFS · exporters · diagnostics
                            │
                     typst 0.15 (Rust)
```

Only JSON strings and byte arrays cross the FFI boundary, so each platform binding is about a
hundred lines and identical in shape.

### The resolution loop

The compiler cannot fetch anything itself. A missing file or package comes back as structured data
rather than a parsed error message:

```
compile() → missing @preview/cetz:0.3.0 → packageResolver.resolve(spec) → VFS → compile() → …
```

This pays off three ways: Kotlin/Native needs no `staticCFunction` with captured state, path
traversal is impossible because there are no paths, and it is the only design that survives the
browser, where a package download is inherently asynchronous and cannot happen inside a synchronous
call into WebAssembly.

Supply the resolvers through `TypstConfig`:

```kotlin
val config = TypstConfig(
    fileResolver = FileResolver { path -> readFromDisk(path) },
    packageResolver = { spec -> httpClient.get("https://packages.typst.org/…").body() },
)
```

## Outputs

`CompileRequest.outputs` accepts any combination; asking for several reuses a single layout pass.

| Format | Produces |
| --- | --- |
| `OutputFormat.Pdf` | one PDF, optionally targeting a PDF/A standard |
| `OutputFormat.Svg` | one SVG per page, or one merged document |
| `OutputFormat.Png` | one PNG per page at a chosen `pixelPerPt` |
| `OutputFormat.Query` | JSON, the equivalent of `typst query` |

## Building

You need the [Rust toolchain](https://rustup.rs) on `PATH`, plus the targets you intend to build:

```bash
rustup target add aarch64-apple-ios aarch64-apple-ios-sim x86_64-apple-ios   # iOS
rustup target add x86_64-pc-windows-gnu                                      # mingwX64
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk                                                      # Android
```

Android additionally needs the NDK. The build looks for it in this order: `ANDROID_NDK_HOME`,
then `ANDROID_NDK_ROOT`, then whatever AGP resolves under the SDK for the `android-ndk` version in
the catalogue. CI runners already export one, so nothing has to be configured there. Locally,
install the pinned version through the SDK manager, or unzip the standalone NDK into
`$ANDROID_HOME/ndk/<version>/` — the directory name must match `Pkg.Revision` in the NDK's
`source.properties`.

Then:

```bash
cd rust && cargo test          # the Rust core, no Gradle involved
./gradlew :typst:jvmTest       # JNI
./gradlew :typst:linuxX64Test  # cinterop + static linking (mingwX64Test on Windows)

# Instrumented, against a running emulator or device. The ABI filter matters: without it every
# ABI is built, and only the device's own is ever loaded.
./gradlew :typst:connectedAndroidDeviceTest -Ptypst.androidAbis=x86_64
```

## Tests

Every platform runs the **same** `commonTest` suite — there are no per-platform test bodies, only
the `expect`/`actual` needed to say where artifacts go. The end-to-end case compiles
`src/commonTest/typst/report.typ`, a real two-page document with an import, maths, a table and
non-ASCII text, and checks the PDF, one SVG and one PNG per page, and a document query.

It then writes the PDF where you can open it:

| Suite | PDF lands in |
| --- | --- |
| `jvmTest`, `linuxX64Test`, `mingwX64Test`, … | `typst/build/test-artifacts/` |
| `connectedAndroidDeviceTest` | `typst/build/outputs/connected_android_test_additional_output/` |

The Android path goes through AGP's `additionalTestOutputDir` runner argument, because the test
app is uninstalled the moment the run ends and its own directories go with it.

The fixtures stay real `.typ` files in the repository; `generateTypstFixtures` turns them into
Kotlin constants so the suite needs no file access at all — which is what lets it run unchanged
inside an Android instrumentation runner.

Android has no host-test suite on purpose: `commonTest` would flow into it and every one of those
tests calls `System.loadLibrary`, which a plain JVM cannot satisfy.

Gradle drives cargo automatically; `build-logic` maps each Kotlin target to its Rust triple.

Useful properties:

| Property | Effect |
| --- | --- |
| `-Ptypst.cargoProfile=dev` | build the Rust crates unoptimised (much faster, much slower output) |
| `-Ptypst.skipCargo=true` | do not build any Rust at all — type-checks the Kotlin sources on a machine without a toolchain |
| `-Ptypst.prebuiltDir=<dir>` | use native artifacts from `<dir>/<triple>/`, as the CI publish job does |
| `-Ptypst.androidAbis=x86_64` | build only these Android ABIs instead of all three |
| `-Ptypst.cargo=<path>` | use a specific cargo binary instead of the one that is found |

Cargo is located automatically: first on `PATH`, then under `CARGO_HOME` (or `~/.cargo`). That
matters for IDE syncs — a Gradle daemon started before rustup was installed does not inherit the
shell's `PATH`, and the failure it produces otherwise is an opaque "cannot find the file
specified".

Targets whose Rust artifact the current machine could not produce are skipped rather than
attempted, so an IDE sync on Windows does not try to build an Apple static library. Supplying
`-Ptypst.prebuiltDir` lifts that restriction, since nothing needs compiling.

### A note on Windows ABIs

`mingwX64` always maps to the **GNU** ABI (`x86_64-pc-windows-gnu`), because that is what
Kotlin/Native links with.

The JVM library can use either ABI — the JVM loads both happily — so which one gets built is
decided by whichever the active Rust toolchain hosts, read from `cargo -vV`. That is the only
reliable signal: `os.name` cannot distinguish them, and guessing MSVC breaks every build on a
machine without the Visual Studio Build Tools.

If you would rather not install those, Kotlin/Native already ships a usable MinGW-w64 under
`~/.konan/dependencies/msys2-mingw-w64-x86_64-*/bin`. Put it on `PATH` and make the GNU toolchain
the default; nothing else needs configuring:

```bash
rustup toolchain install stable-x86_64-pc-windows-gnu
rustup default stable-x86_64-pc-windows-gnu
./gradlew :typst:jvmTest
```

Released Windows artifacts are built on CI, whose toolchain hosts MSVC.

## Module layout

| Module | Purpose |
| --- | --- |
| `rust/typst-kmp-core` | the engine: `World`, VFS, exporters, diagnostics |
| `rust/typst-kmp-cabi` | `extern "C"` facade plus a cbindgen-generated header, for cinterop |
| `rust/typst-kmp-jni` | JNI facade for the JVM and Android |
| `typst` | the published Kotlin Multiplatform library |
| `typst-android-native` | an AAR that carries nothing but `jniLibs/*.so` — see below |
| `build-logic` | the Gradle ↔ cargo integration |

`typst-android-native` exists because the AGP Kotlin Multiplatform library plugin
(`com.android.kotlin.multiplatform.library`) supports neither `jniLibs` nor `packagingOptions` nor
`externalNativeBuild`; the [Android documentation](https://developer.android.com/kotlin/multiplatform/plugin)
points at a separate `com.android.library` module as the way out.

## Known trade-offs

* **Binary size.** Measured on release builds: 39.8 MB (arm64-v8a), 33.8 MB (armeabi-v7a),
  43.3 MB (x86_64) and 41.4 MB for the Windows JVM library. Roughly 10 MB of that is the embedded
  font bundle — build the Rust crates with `--no-default-features` to drop `embed-fonts` and
  supply fonts through `TypstConfig.fonts` instead. Android apps should rely on ABI splits or app
  bundles so a device only downloads its own architecture.
* **The JVM jar bundles every platform**, so it is large. Splitting it into classifier jars is a
  planned follow-up.
* **Debug Android builds carry release-optimised native libraries.** The cargo profile follows
  `typst.cargoProfile`, not the Android variant, because an unoptimised Typst is too slow to be
  useful. Pass `-Ptypst.cargoProfile=dev` when you actually want to debug the Rust side.
* **Android tests.** The common test suite cannot run as `androidHostTest`, because the Android
  loader calls `System.loadLibrary` and a plain JVM cannot satisfy it. Android is covered by
  instrumented device tests.
* **watchOS and tvOS are not supported.** Their Rust targets are tier 3 and would need a nightly
  toolchain with `-Z build-std`.
* **Web (`js`, `wasmJs`) is not implemented yet.** The resolution loop above already handles the
  asynchrony it needs; what is missing is a `wasm-bindgen` crate and the JS glue.

## Licence

Apache 2.0. Typst itself is Apache 2.0 as well.
