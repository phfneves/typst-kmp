# Changelog

Every release lists the Typst version it embeds, because the compiler decides layout: a new Typst
can move text by a pixel, and pixel-compared goldens have to be regenerated with it.

| typst-kmp | Typst | Released |
| --- | --- | --- |
| unreleased | 0.15 | — |
| 0.1.0-alpha02 | 0.15 | 2026-10-08 |
| 0.1.0-alpha01 | 0.15 | 2026-09-03 |

## Unreleased

### Breaking

* `CompileRequest.files` belong to their compilation alone. They no longer enter the instance's
  virtual file system, so they are gone when `compile()` returns and a later compilation cannot see
  them. Code that relied on a file passed once staying around must pass it every time, or serve it
  through `TypstConfig.fileResolver`.
* `clearFiles()` and `removeFile()` act only on the files the instance keeps, those fetched through
  `TypstConfig.fileResolver`. Calling them to clean up after a request is no longer needed.
* `Diagnostic` has a new constructor parameter, `rendered`.

### Added

* Compilations on one instance run in parallel on the JVM, Android and Kotlin/Native. Calls that
  change the instance still run alone. The browser engine is one worker and still queues.
* `CompileRequest.fileResolver`: a resolver per request, asked before the instance's, whose files
  last for that compilation only.
* `Typst.compilePdf()` and `CompileResult.Success.pdf`, both non-null.
* `Typst.listFiles()`, `listPackages()` and `fontFamilies()`.
* `Diagnostic.rendered`: the diagnostic as the `typst` CLI prints it, with the source line and the
  span underlined. `TypstCompilationException` uses it for its message.
* Public API dumps under `typst/api/`, checked in CI.
* Ready-made package resolvers: `DirectoryPackageResolver` serves a local directory, packed or
  unpacked, on every platform but the web; `RecordingPackageResolver` lists the packages a
  compilation pulls in; `PackageResolver.orElse` chains resolvers.
* A new artifact, `typst-kmp-universe`, with `UniversePackageResolver` to download `@preview`
  packages over Ktor, and `InMemoryPackageCache` and `DirectoryPackageCache` to keep them.
* A package archive may be a plain `.tar` as well as a `.tar.gz`.
* `TypstConfig.fontPaths` and `includeSystemFonts`: fonts on disk, indexed when the instance is
  created and mapped into memory only when a document uses them. Not in the browser.

### Changed

* Resolution continues for as long as each round supplies something new, so a deep import chain
  no longer needs a raised limit.
* Resolvers run outside any lock and may call back into the instance.
* Cancelling `compile()` interrupts the compilation in progress on Android, the JVM and
  Kotlin/Native. In the browser it takes effect between resolution rounds.

### Deprecated

* `TypstConfig.maxResolveRounds`. Resolution no longer needs a bound.

## 0.1.0-alpha02 — 2026-10-08

### Added

* Android `x86` and JVM `windows-aarch64` native libraries.
* `Typst.removeFile()`, `clearFiles()` and `clearPackages()` to manage the virtual file system.

### Changed

* Every operation on an instance is serialised, so concurrent compilations never see each other's
  request files.

## 0.1.0-alpha01 — 2026-09-03

First published release: the Typst compiler in process on Android, the JVM, iOS, macOS, Linux,
Windows and the browser (`js` and `wasmJs`), with PDF, SVG, PNG and query outputs, file and
package resolvers, and `sys.inputs`.
