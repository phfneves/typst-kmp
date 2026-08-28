# typst-kmp demo

A Compose Multiplatform application that uses the library on Android, on the desktop (JVM) and on
iOS: you edit a Typst document on one side and watch its pages appear on the other, compiled in the
same process — no `typst` binary installed, no subprocess, no network.

What it shows:

* **One reused instance.** `Typst.create()` runs once; every keystroke only recompiles.
* **Several formats from one pass.** A single `compile()` asks for `OutputFormat.Png` (the preview)
  and `OutputFormat.Pdf` (the export), and the layout runs once for both.
* **Structured diagnostics.** The *Com erro* sample fails inside an imported file, and the panel
  reports `/helpers.typ:2` rather than "something went wrong somewhere".
* **An in-memory VFS.** None of the samples touch the disk; the files travel in
  `CompileRequest.files`.

The interface and the sample documents are in Portuguese, like the library's own fixtures — which
is also what keeps the non-ASCII path exercised.

## Layout

| Module | What is in it |
| --- | --- |
| `composeApp` | All the UI and logic, plus each platform's `actual` |
| `androidApp` | Manifest, theme and the `FileProvider`. Not one line of Kotlin |
| `desktopApp` | `main()`, native packaging, and the host's JNI library jar |
| `iosApp` | An Xcode project with the SwiftUI shell |

Three application modules exist because AGP 9 no longer allows `com.android.application` together
with the Kotlin Multiplatform plugin in one module; desktop and iOS follow the same shape so that
the three entry points look alike.

This is a **separate Gradle build**. It depends on `io.github.phfneves:typst-kmp` by its published
coordinates, exactly as any consumer would, and `includeBuild("..")` in `settings.gradle.kts`
resolves that against the sources next door — so nothing has to be published to run it. In an IDE,
open `demo/` as its own project; the library comes along as an included build.

## Prerequisites

* JDK 21 and the [Rust toolchain](https://rustup.rs); the library builds its native crates as part
  of the build.
* For Android: the SDK, the NDK and `cargo-ndk` (see the root README), plus a
  `demo/local.properties` pointing at the SDK. AGP reads that file from the root of the *build*,
  and the demo is a build of its own:

  ```properties
  sdk.dir=C\:\\Users\\you\\AppData\\Local\\Android\\Sdk
  ```

* For iOS: macOS with Xcode.

## Running

Every command below starts at the repository root, hence the `-p demo`. The demo also carries a
wrapper of its own, pinned to the same Gradle version, so `cd demo && ./gradlew :desktopApp:run`
works just as well — and having one stops an IDE from generating a wrapper at whatever version it
happens to prefer.

```bash
# Desktop
./gradlew -p demo :desktopApp:run

# Android, against a running emulator or device. The ABI filter matters: each extra architecture is
# another full build of the Typst tree.
./gradlew -p demo :androidApp:installDebug -Ptypst.androidAbis=x86_64 -Pdemo.androidAbis=x86_64

# Type-check everything without touching Rust
./gradlew -p demo :desktopApp:assemble -Ptypst.skipCargo=true
```

For iOS, open `demo/iosApp/iosApp.xcodeproj` in Xcode and run. Its *Compile Kotlin Framework* phase
calls the demo's Gradle build to assemble and sign the `ComposeApp` framework.

The first run builds Typst in release mode and takes a while. `-Ptypst.cargoProfile=dev` shortens
that considerably while you are only changing the interface.

## The extra line on the desktop

On the JVM the native library does not ship with the classes: `typst-kmp-jvm` is bytecode only, and
each platform gets a classifier jar of its own. A published consumer writes

```kotlin
runtimeOnly("io.github.phfneves:typst-kmp-jvm:<version>:windows-x86_64")
```

and `desktopApp/build.gradle.kts` does the equivalent against the build next door, resolving the
host's classifier the same way. Drop that line and the app still starts — it just shows the
loader's own message naming the artifact that is missing, which is exactly what a consumer would
see.

Android needs nothing of the sort: the `typst-kmp-android-native` AAR brings the `jniLibs` in
transitively.
