# typst-kmp

The [Typst](https://typst.app) typesetting compiler as a Kotlin Multiplatform library — no `typst`
binary to install, no subprocess, no server round-trip. The compiler is embedded and runs in
process on Android, the JVM, iOS, macOS, Linux, Windows and in the browser.

> **Status: alpha.** `0.1.0-alpha01` is the first published build; the API may still change.
> The same 13-test `commonTest` suite runs green on the JVM (JNI), on Kotlin/Native (cinterop),
> on an Android emulator (instrumented) and in a browser on both `js` and `wasmJs`
> (wasm-bindgen), on top of 15 Rust tests. Each run compiles a real multi-page document and —
> everywhere but the browser, which has nowhere to write one — leaves the PDF for inspection.
>
> Some targets are packaged without ever having run the suite: `iosArm64` on a real device,
> `iosX64`, `macosX64`, `linuxArm64`, the Android `arm64-v8a`, `armeabi-v7a` and `x86` libraries
> (their JNI entry points are checked, but no test executes on those ABIs) and the JVM classifier
> jars for macOS and Linux arm64. If you are on one of those, you are the first.

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

## Demo

[`demo/`](demo/) is a Compose Multiplatform application built on the library: edit a Typst document
on one side, watch its pages render on the other, export the PDF. It runs on Android, on the
desktop, on iOS and in a browser, and it is a separate Gradle build that consumes typst-kmp by its
published coordinates — so it doubles as a worked example of the installation below.

```bash
./gradlew -p demo :desktopApp:run
./gradlew -p demo :webApp:wasmJsBrowserDevelopmentRun   # or jsBrowserDevelopmentRun
```

## Installation

Published on Maven Central. The examples below pin `0.1.0-alpha01`; check
[Releases](https://github.com/phfneves/typst-kmp/releases) for the current version.

```kotlin
repositories { mavenCentral() }
```

On the JVM the compiled classes and the native library ship as **separate artifacts**: the main
`typst-kmp-jvm` jar is bytecode only (~160 KB), and each platform's JNI library is a classifier jar
of its own (~20 MB compressed). Gradle module metadata cannot reach a classifier artifact, so the
native jar is always one explicit extra line — nothing infers it for you.

```kotlin
// Kotlin Multiplatform
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.phfneves:typst-kmp:0.1.0-alpha01")
        }
        jvmMain.dependencies {
            // Pick the classifier matching where the JVM will run.
            runtimeOnly("io.github.phfneves:typst-kmp-jvm:0.1.0-alpha01:linux-x86_64")
        }
    }
}
```

```kotlin
// Plain JVM
dependencies {
    implementation("io.github.phfneves:typst-kmp-jvm:0.1.0-alpha01")
    runtimeOnly("io.github.phfneves:typst-kmp-jvm:0.1.0-alpha01:linux-x86_64")
}
```

```xml
<!-- Maven -->
<dependency>
  <groupId>io.github.phfneves</groupId>
  <artifactId>typst-kmp-jvm</artifactId>
  <version>0.1.0-alpha01</version>
</dependency>
<dependency>
  <groupId>io.github.phfneves</groupId>
  <artifactId>typst-kmp-jvm</artifactId>
  <version>0.1.0-alpha01</version>
  <classifier>linux-x86_64</classifier>
  <scope>runtime</scope>
</dependency>
```

Android needs no extra line: `typst-kmp-android-native` carries the `jniLibs` and comes in
transitively. The web targets need no extra dependency either, but they do need two files served
alongside the page — see [Web](#web).

### JVM classifiers

| classifier | library |
| --- | --- |
| `linux-x86_64` | `libtypst_kmp_jni.so` |
| `linux-aarch64` | `libtypst_kmp_jni.so` |
| `macos-x86_64` | `libtypst_kmp_jni.dylib` |
| `macos-aarch64` | `libtypst_kmp_jni.dylib` |
| `windows-x86_64` | `typst_kmp_jni.dll` |
| `windows-aarch64` | `typst_kmp_jni.dll` |
| `all` | every one of the above, in a single ~200 MB jar |

Use `all` only when one build has to run everywhere — a desktop application shipped as a single
cross-platform bundle. For anything that knows its own target, a single classifier keeps the
download to one library instead of six.

To resolve the classifier for the machine running the build:

```kotlin
val classifier = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = when (System.getProperty("os.arch").lowercase()) {
        "x86_64", "amd64" -> "x86_64"
        "aarch64", "arm64" -> "aarch64"
        else -> error("Unsupported architecture")
    }
    when {
        os.startsWith("windows") -> "windows-$arch"
        os.startsWith("mac") -> "macos-$arch"
        else -> "linux-$arch"
    }
}
```

For a platform with no published classifier, build the `typst-kmp-jni` crate yourself and point the
loader at it with `-Dtypst.kmp.library.path=/path/to/library`.

### Web

Both `js` and `wasmJs` are supported, **in a browser only** — the engine needs a `Worker` and
fetches its module over HTTP, neither of which Node offers as-is.

The compiler is a WebAssembly module, so unlike every other platform it is not linked into the
artifact the Kotlin compiler produces: it is fetched at runtime. Two files have to be reachable
from the page:

```
typst_kmp_wasm.js        19 KB    the wasm-bindgen glue
typst_kmp_wasm_bg.wasm   40 MB    the compiler (17 MB over gzip, 11 MB over brotli)
```

They are published as a `webassets` classifier on both web artifacts — `typst-kmp-js` and
`typst-kmp-wasm-js` — and the two zips are identical, so a project building both targets only needs
one. Unzip it into your distribution at `typst-kmp/`, which is where the engine looks by default:

```kotlin
// Gradle: unpack the module into the browser distribution.
val typstWebAssets by configurations.creating
dependencies {
    typstWebAssets("io.github.phfneves:typst-kmp-js:0.1.0-alpha01:webassets@zip")
}
val unpackTypst by tasks.registering(Copy::class) {
    from(typstWebAssets.map { zipTree(it) })
    into(layout.buildDirectory.dir("typstWebAssets/typst-kmp"))
}
tasks.named("jsProcessResources") { (this as Copy).from(unpackTypst) }
```

[`demo/webApp`](demo/webApp/build.gradle.kts) does exactly this, against the sources next door
rather than a repository.

Serving them from somewhere else — a CDN, a versioned asset host — needs no build wiring at all,
only a config line. A cross-origin location works as long as it sends CORS headers:

```kotlin
Typst.create(TypstConfig(webAssetBaseUrl = "https://cdn.example.com/typst-kmp/0.1.0/"))
```

Two things worth knowing about how this runs:

* **The engine lives in a Web Worker.** Typesetting takes seconds and a page has one thread, so
  `compile()` would otherwise freeze the UI. The worker is started from a `Blob` holding a script
  compiled into the library, so there is nothing extra to host, nothing for a bundler to resolve,
  and the worker is always same-origin — which is what leaves the module itself free to be
  cross-origin.
* **A Rust panic is fatal to that engine.** `wasm32-unknown-unknown` cannot unwind, so a panic
  tears the module down instead of raising through it. The engine reports this and refuses further
  calls; recover by closing it and creating another `Typst`. The other platforms catch panics at
  the FFI boundary and carry on.

The 40 MB is roughly a quarter fonts. `-Ptypst.wasmEmbedFonts=false` builds the module without
them — 31 MB, 11 MB gzipped — leaving `TypstConfig.fonts` to supply what the document needs.

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
   jvmCommonMain        nativeMain            webMain
   JNI bindings         cinterop         Web Worker protocol
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

### Sharing an instance

`Typst.create()` loads every font, so create one instance and share it across the application. It
is safe to call from any coroutine or thread. The instance runs one operation at a time: a
compilation holds it from writing `CompileRequest.files` to producing its last output, so two
compilations that both write `/main.typ` or `/data.json` never see each other's files. They queue
rather than run in parallel; for throughput, keep a small pool of instances instead.

Resolvers run while the compilation holds the instance, so a resolver must not call back into it.

`close()` is safe at any time. A compilation already running finishes first and the engine is
released when it returns; any call made after that fails with `IllegalStateException`.

### What stays in the virtual file system

The virtual file system belongs to the instance and outlives each compilation:

| Source | Lifetime |
| --- | --- |
| `CompileRequest.files` | written before every compilation, replacing what was at those paths, then kept |
| `fileResolver` | kept; the resolver is not asked for that path again |
| `packageResolver` | kept; a published package version never changes |

That gives two rules:

* **Data that changes goes in the request.** A JSON payload, a generated chart, a photo that can be
  replaced under the same path: pass them in `CompileRequest.files`. Served through the resolver,
  only the first version would ever be read.
* **Clear what accumulates.** Nothing is removed on its own, so documents that keep bringing new
  paths, such as one image per record, grow the instance's memory with every compilation.
  `clearFiles()` removes every file but keeps fonts and packages. Calling it after each compilation
  costs no more than resolving those files again. `removeFile(path)` drops a single file and
  `clearPackages()` drops the packages.

```kotlin
val typst = Typst.create(TypstConfig(fonts = fonts, fileResolver = images))

suspend fun render(data: ByteArray): ByteArray {
    val result = typst.compile(
        CompileRequest(files = mapOf("/main.typ" to template, "/data.json" to data)),
    )
    typst.clearFiles()
    return result.getOrThrow().filterIsInstance<Output.Pdf>().single().bytes
}
```

Typst's own memoisation cache is trimmed after every compilation, so it does not grow with the
number of documents either.

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
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android \
                  i686-linux-android
cargo install cargo-ndk                                                      # Android
rustup target add wasm32-unknown-unknown                                     # js, wasmJs
```

The web targets also need `wasm-bindgen-cli`, at **exactly** the version `rust/typst-kmp-wasm`
pins — the CLI refuses to process a module built by any other, and the build checks this up front
and tells you which version to fetch:

```bash
cargo install wasm-bindgen-cli --locked --version 0.2.106
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
./gradlew :typst:jvmJarTest    # the same suite, loading the library out of the assembled jars
./gradlew :typst:linuxX64Test  # cinterop + static linking (mingwX64Test on Windows)

# In a real browser, engine in a Web Worker. Needs Chrome; karma is told where the module is.
./gradlew :typst:jsBrowserTest :typst:wasmJsBrowserTest

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
| `jvmJarTest` | `typst/build/test-artifacts/jvm-from-jar/` |
| `connectedAndroidDeviceTest` | `typst/build/outputs/connected_android_test_additional_output/` |
| `jsBrowserTest`, `wasmJsBrowserTest` | nowhere — a browser has no file system to write to |

`jvmJarTest` runs that same suite a second time, against the assembled jars. `jvmTest` hands the
loader an absolute path through `typst.kmp.library.path`, so it never walks the resource lookup
every published artifact depends on; `jvmJarTest` puts the main jar and the host's classifier jar
on the classpath and sets no override, so a packaging mistake fails a test instead of a release.

The Android path goes through AGP's `additionalTestOutputDir` runner argument, because the test
app is uninstalled the moment the run ends and its own directories go with it.

The fixtures stay real `.typ` files in the repository; `generateTypstFixtures` turns them into
Kotlin constants so the suite needs no file access at all — which is what lets it run unchanged
inside an Android instrumentation runner.

Android has no host-test suite on purpose: `commonTest` would flow into it and every one of those
tests calls `System.loadLibrary`, which a plain JVM cannot satisfy.

The browser suites run under karma in headless Chrome. `karmaWebAssetsConfig` generates the snippet
that serves the WebAssembly module to the test page at the same relative URL a real page uses, so
the suite exercises the shipping asset path rather than a shortcut around it.

Gradle drives cargo automatically; `build-logic` maps each Kotlin target to its Rust triple.

Useful properties:

| Property | Effect |
| --- | --- |
| `-Ptypst.cargoProfile=dev` | build the Rust crates unoptimised (much faster, much slower output) |
| `-Ptypst.skipCargo=true` | do not build any Rust at all — type-checks the Kotlin sources on a machine without a toolchain (JVM, Android and web; the native targets still need the generated header, see below) |
| `-Ptypst.prebuiltDir=<dir>` | use native artifacts from `<dir>/<triple>/`, as the CI publish job does |
| `-Ptypst.androidAbis=x86_64` | build only these Android ABIs instead of all four |
| `-Ptypst.cargo=<path>` | use a specific cargo binary instead of the one that is found |

Cargo is located automatically: first on `PATH`, then under `CARGO_HOME` (or `~/.cargo`), then in
the directories the usual package managers install into — `/opt/homebrew/bin`, `/usr/local/bin` and
`/opt/local/bin` on macOS, `~/.local/bin` and `/usr/local/bin` on Linux, `~/scoop/shims` on
Windows. That matters for IDE syncs, and on macOS most of all: a Gradle daemon launched from an
IDE inherits a `PATH` of `/usr/bin:/bin:/usr/sbin:/sbin` and sees none of those locations, so an
installed toolchain still looks absent. If yours lives somewhere else, put

```properties
typst.cargo=/path/to/cargo
```

in `~/.gradle/gradle.properties`, which every build and every sync reads.

Targets whose Rust artifact the current machine could not produce are skipped rather than
attempted, so an IDE sync on Windows does not try to build an Apple static library. Supplying
`-Ptypst.prebuiltDir` lifts that restriction, since nothing needs compiling.

Those targets still get a cinterop, though — `nativeMain` is only handed a commonized interop when
*every* one of its targets carries one — and a cinterop needs the cbindgen-generated
`rust/typst-kmp-cabi/include/typst_kmp.h`. That header is a build output, written by the cabi
crate's `build.rs`, so those cinterops are ordered after a cabi build the host *can* run; the
header is target-independent, and the host's own build is already in the graph. Without that
ordering they raced it and failed with `'typst_kmp.h' file not found` — which is why a fresh sync
on macOS, where three of the eight native targets are unbuildable, failed the most reliably.

The same header is why `-Ptypst.skipCargo=true` cannot carry the native targets on a machine that
has never built the cabi crate: there is nothing to read the ABI out of. The JVM, Android and web
sources type-check regardless.

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

## Releasing

CI runs only for a release: nothing builds on a push or a pull request. Publishing a GitHub release
starts the `Publish` workflow, which runs the full test suite (`Build`) as a gate, builds the native
libraries for every platform, and uploads the result to Maven Central. The version comes from the
tag, minus its leading `v`, so `v0.1.0-alpha02` publishes `0.1.0-alpha02`.

1. **Make sure `main` holds what you want to ship.** The release is built from the commit the tag
   points at, so merge everything first and pull:

   ```bash
   git switch main
   git pull
   ```

2. **Create the tag and push it.** Use an annotated tag named `v<version>`:

   ```bash
   git tag -a v0.1.0-alpha02 -m "v0.1.0-alpha02"
   git push origin v0.1.0-alpha02
   ```

   Pushing the tag on its own triggers nothing.

3. **Create a draft release from that tag.** On GitHub, open
   [Releases → Draft a new release](https://github.com/phfneves/typst-kmp/releases/new), pick the
   tag you just pushed under *Choose a tag*, give it a title and write the notes (*Generate release
   notes* fills them in from the merged pull requests). Tick *Set as a pre-release* for an alpha,
   beta or RC. Then click **Save draft**, not *Publish release*.

   The same thing from the command line:

   ```bash
   gh release create v0.1.0-alpha02 --verify-tag --draft --generate-notes --prerelease
   ```

   A draft triggers nothing either, so you can review the notes at leisure.

4. **Publish the draft.** Open the draft and click **Publish release**, or run
   `gh release edit v0.1.0-alpha02 --draft=false`. This is the step that starts the `Publish`
   workflow; follow it in the [Actions](https://github.com/phfneves/typst-kmp/actions) tab. If any
   test or any platform's build fails, nothing reaches Maven Central.

5. **Wait for Maven Central.** `mavenCentralAutomaticPublishing=true` in `gradle.properties`
   releases the deployment as soon as it passes validation, so there is nothing to click. Its status
   shows on the [Central Portal](https://central.sonatype.com/publishing/deployments), and the
   artifacts usually become resolvable within half an hour.

6. **Update the version the README pins** in the installation examples.

To retry a release whose workflow failed, rerun it from the Actions tab once the cause is fixed. If
the fix needs a new commit, delete the release and the tag, and start again from step 2. A version
that already reached Maven Central can never be replaced, so the retry needs a new version number.
`Publish` can also be started by hand from the Actions tab (*Run workflow*) with an explicit
version. That builds whatever branch you select, so prefer the release path.

The workflow needs these repository secrets: `MAVEN_CENTRAL_USERNAME` and `MAVEN_CENTRAL_PASSWORD`
(a Central Portal user token), plus `SIGNING_KEY_ID`, `SIGNING_PASSWORD` and `GPG_KEY_CONTENTS` for
the GPG key that signs the artifacts.

## Module layout

| Module | Purpose |
| --- | --- |
| `rust/typst-kmp-core` | the engine: `World`, VFS, exporters, diagnostics |
| `rust/typst-kmp-cabi` | `extern "C"` facade plus a cbindgen-generated header, for cinterop |
| `rust/typst-kmp-jni` | JNI facade for the JVM and Android |
| `rust/typst-kmp-wasm` | wasm-bindgen facade for the browser, plus the worker script that drives it |
| `typst` | the published Kotlin Multiplatform library |
| `typst-android-native` | an AAR that carries nothing but `jniLibs/*.so` — see below |
| `build-logic` | the Gradle ↔ cargo integration |
| `demo` | a Compose Multiplatform app built on the library — a build of its own, see [demo/README.md](demo/README.md) |

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
* **The JVM native library ships separately from the classes.** `typst-kmp-jvm` carries only
  bytecode; the JNI library comes from a classifier jar (see [Installation](#installation)). A
  single-platform application downloads one library instead of five, at the cost of one dependency
  line that Gradle module metadata cannot infer for you. The `all` classifier is there for the
  cross-platform bundle that genuinely needs every one.
* **Debug Android builds carry release-optimised native libraries.** The cargo profile follows
  `typst.cargoProfile`, not the Android variant, because an unoptimised Typst is too slow to be
  useful. Pass `-Ptypst.cargoProfile=dev` when you actually want to debug the Rust side.
* **Android tests.** The common test suite cannot run as `androidHostTest`, because the Android
  loader calls `System.loadLibrary` and a plain JVM cannot satisfy it. Android is covered by
  instrumented device tests.
* **watchOS and tvOS are not supported.** Their Rust targets are tier 3 and would need a nightly
  toolchain with `-Z build-std`.
* **The web module is fetched, not bundled, and the library cannot place it for you.** Kotlin/JS
  does not replay a dependency's resources into your distribution, and the API that would — the
  one Compose Resources uses — is closed to plugins other than Compose. So the module ships as a
  `webassets` zip and you either unpack it into your distribution or point `webAssetBaseUrl` at a
  copy you host. See [Web](#web).
* **Web is browser-only.** Node has no DOM `Worker`, and running the engine on the page's own
  thread instead would freeze it for the length of every compilation.
* **Blobs are copied byte by byte on `wasmJs`.** Kotlin/JS gets this free — there a `ByteArray`
  *is* an `Int8Array` — but Kotlin/Wasm keeps arrays in linear memory and the standard library
  offers no bulk copy to a typed array. It costs single-digit milliseconds for a PDF; large sets
  of rendered pages pay more.

## Licence

Apache 2.0. Typst itself is Apache 2.0 as well.
