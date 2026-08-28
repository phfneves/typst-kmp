package io.github.phfneves.typst.gradle

import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import java.io.File
import javax.inject.Inject

/**
 * Configures cargo builds of the `rust/` workspace and exposes them as Gradle tasks.
 *
 * Register a build with [CargoExtension.build]; the returned [TaskProvider] produces a directory
 * containing exactly one artifact, which callers wire into cinterop, jar resources or jniLibs.
 */
class CargoPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.extensions.create("cargo", CargoExtension::class.java, project)
    }
}

abstract class CargoExtension @Inject constructor(private val project: Project) {

    /** Root of the cargo workspace. Defaults to `<rootDir>/rust`. */
    abstract val workspaceDir: DirectoryProperty

    /** `release` or `dev`. Driven by the `typst.cargoProfile` Gradle property. */
    abstract val profile: Property<String>

    /** When set, cargo is not invoked and artifacts are copied from here instead. */
    abstract val prebuiltDir: DirectoryProperty

    abstract val features: ListProperty<String>

    abstract val noDefaultFeatures: Property<Boolean>

    abstract val androidMinSdk: Property<Int>

    /**
     * Extra environment variables for every cargo invocation.
     *
     * Android builds use this to hand `cargo-ndk` the NDK that AGP resolved, so a local build
     * does not depend on `ANDROID_NDK_HOME` being set by hand.
     */
    abstract val environment: MapProperty<String, String>

    /** `-Ptypst.skipCargo=true` — type-check Kotlin without a Rust toolchain. */
    abstract val skipCargo: Property<Boolean>

    /**
     * The cargo executable.
     *
     * Resolved rather than assumed, because a Gradle daemon does not necessarily inherit the
     * PATH of the shell that installed rustup — an IDE launched before the install is the common
     * case, and it fails with a bare "cannot find the file specified". Override with
     * `-Ptypst.cargo=/path/to/cargo`.
     */
    abstract val cargoExecutable: Property<String>

    /**
     * The `wasm-bindgen` executable. Resolved the same way as [cargoExecutable], because rustup
     * installs both into the same bin directory. Override with `-Ptypst.wasmBindgen=<path>`.
     */
    abstract val wasmBindgenExecutable: Property<String>

    /**
     * Whether the WebAssembly module carries the bundled fonts. `-Ptypst.wasmEmbedFonts=false`
     * builds the slim variant.
     *
     * Every other platform embeds them unconditionally, so this is the one place where
     * `TypstConfig.embedDefaultFonts` can mean something different from platform to platform. It
     * exists because those fonts are roughly a quarter of a download the browser must complete
     * before it can typeset anything.
     */
    abstract val wasmEmbedFonts: Property<Boolean>

    init {
        workspaceDir.convention(project.rootProject.layout.projectDirectory.dir("rust"))
        profile.convention(project.providers.gradleProperty("typst.cargoProfile").orElse("release"))
        noDefaultFeatures.convention(false)
        wasmEmbedFonts.convention(
            project.providers.gradleProperty("typst.wasmEmbedFonts").map { it.toBoolean() }
                .orElse(true),
        )
        skipCargo.convention(
            project.providers.gradleProperty("typst.skipCargo").map { it.toBoolean() }.orElse(false),
        )
        project.providers.gradleProperty("typst.prebuiltDir").orNull?.let {
            prebuiltDir.set(project.layout.projectDirectory.dir(it))
        }
        cargoExecutable.convention(
            project.providers.gradleProperty("typst.cargo").orElse(
                project.provider { findExecutable(project, "cargo") },
            ),
        )
        wasmBindgenExecutable.convention(
            project.providers.gradleProperty("typst.wasmBindgen").orElse(
                project.provider { findExecutable(project, "wasm-bindgen") },
            ),
        )
    }

    /**
     * Looks for [name] on PATH, then in rustup's default install location.
     *
     * Falls back to the bare name so the task still runs — and still produces its own install
     * hint — on a machine with no toolchain at all. `wasm-bindgen` is resolved the same way as
     * cargo because `cargo install` puts it in the very same bin directory.
     */
    private fun findExecutable(project: Project, name: String): String {
        val windows = HostFamily.of(System.getProperty("os.name")) == HostFamily.WINDOWS
        val executable = if (windows) "$name.exe" else name

        val onPath = System.getenv("PATH")
            ?.split(File.pathSeparator)
            ?.asSequence()
            ?.filter { it.isNotBlank() }
            ?.map { File(it, executable) }
            ?.firstOrNull { it.isFile }
        if (onPath != null) return onPath.absolutePath

        val cargoHome = System.getenv("CARGO_HOME")?.let(::File)
            ?: File(System.getProperty("user.home"), ".cargo")
        val inCargoHome = File(cargoHome, "bin/$executable")
        if (inCargoHome.isFile) {
            project.logger.info("Using $name from ${inCargoHome.absolutePath} (not on PATH).")
            return inCargoHome.absolutePath
        }

        return executable
    }

    /**
     * The triple the active Rust toolchain builds for by default, from `cargo -vV`.
     *
     * This is the only reliable answer to "which target can this machine actually build", and it
     * is not derivable from `os.name`: on Windows the same OS and architecture serve both the
     * MSVC and the GNU ABI, and which one works depends on the installed toolchain, not the
     * machine. Returns `null` when cargo cannot be run, leaving the caller to guess.
     */
    fun hostTriple(): String? {
        val execution = project.providers.exec {
            commandLine(cargoExecutable.get(), "-vV")
            isIgnoreExitValue = true
        }
        val exitValue = runCatching { execution.result.get().exitValue }.getOrNull()
        if (exitValue != 0) return null
        val output = runCatching { execution.standardOutput.asText.get() }.getOrNull() ?: return null
        return output.lineSequence()
            .firstOrNull { it.startsWith("host:") }
            ?.substringAfter("host:")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    /**
     * Builds [crate] for `wasm32-unknown-unknown` and runs wasm-bindgen over the result.
     *
     * The returned task produces a directory holding `<crate>.js` and `<crate>_bg.wasm` — the pair
     * the browser loads. The expected CLI version is read out of the crate's own `Cargo.toml`, so
     * the pin lives in exactly one place.
     */
    fun wasmBindgen(crate: String): TaskProvider<WasmBindgenTask> {
        val target = RustTargets.wasm
        val cargoTask = build(crate, target, RustArtifactKind.WASM)
        val embedFonts = wasmEmbedFonts
        cargoTask.configure { noDefaultFeatures.set(embedFonts.map { embed -> !embed }) }
        val taskName = "wasmBindgen" + crate.toCamel()
        if (taskName in project.tasks.names) {
            @Suppress("UNCHECKED_CAST")
            return project.tasks.named(taskName) as TaskProvider<WasmBindgenTask>
        }

        val stem = crate.replace('-', '_')
        val version = wasmBindgenVersion(crate)
        val configuredExecutable = wasmBindgenExecutable
        val configuredSkip = skipCargo
        // A subdirectory of the triple, so the staged layout can hold both the raw `.wasm` the
        // cargo task expects and the wasm-bindgen output this one does.
        val prebuiltForTarget = prebuiltDir.orNull?.asFile?.resolve(target.triple)?.resolve("bindgen")
        val output = project.layout.buildDirectory.dir("rust/${target.triple}/$crate-bindgen")

        val provider = project.tasks.register(taskName, WasmBindgenTask::class.java)
        provider.configure {
            group = "rust"
            description = "Runs wasm-bindgen over $crate"

            wasmDir.set(cargoTask.flatMap { it.outputDir })
            wasmFileName.set(target.dynamicLibFileName(crate))
            outputName.set(stem)
            expectedVersion.set(version)
            wasmBindgenExecutable.set(configuredExecutable)
            skipCargo.set(configuredSkip)
            prebuiltForTarget?.let { prebuiltDir.set(it) }
            usePrebuilt.set(prebuiltForTarget?.isDirectory == true)
            outputDir.set(output)
        }
        return provider
    }

    /**
     * The exact `wasm-bindgen` version [crate] pins, read from its manifest.
     *
     * Duplicating the number in the build script is how it goes stale, and a mismatch between the
     * crate and the CLI is a confusing failure to debug.
     */
    private fun wasmBindgenVersion(crate: String): Provider<String> =
        project.providers.fileContents(
            workspaceDir.file("$crate/Cargo.toml"),
        ).asText.map { manifest ->
            val declaration = manifest.lineSequence()
                .map(String::trim)
                .firstOrNull { it.startsWith("wasm-bindgen") && it.contains('=') }
                ?: error("$crate/Cargo.toml does not declare a wasm-bindgen dependency.")
            Regex("""\d+\.\d+\.\d+""").find(declaration)?.value
                ?: error("Could not read a wasm-bindgen version out of: $declaration")
        }
    fun build(
        crate: String,
        target: RustTarget,
        kind: RustArtifactKind,
    ): TaskProvider<CargoBuildTask> {
        val taskName = "cargoBuild" + crate.toCamel() + target.triple.toCamel()
        if (taskName in project.tasks.names) {
            // The same (crate, target) pair can be requested by several consumers — for instance
            // the host JNI build is wanted by both `jvmTest` and `jvmProcessResources`.
            @Suppress("UNCHECKED_CAST")
            return project.tasks.named(taskName) as TaskProvider<CargoBuildTask>
        }

        val workspace = workspaceDir.get()
        val prebuiltForTarget = prebuiltDir.orNull?.asFile?.resolve(target.triple)

        // Everything cargo reads, minus its own build output directory.
        val sources = project.fileTree(workspace)
        sources.include("**/*.rs", "**/Cargo.toml", "**/Cargo.lock", "**/*.toml")
        sources.exclude("target/**")

        // Gradle annotates `Action` with `@HasImplicitReceiver`, so the configuration block below
        // is receiver-style and `androidMinSdk` etc. would resolve to the *task*'s properties.
        // Capture what we need from the extension first.
        val configuredProfile = profile
        val configuredFeatures = features
        val configuredNoDefaultFeatures = noDefaultFeatures
        val configuredAndroidMinSdk = androidMinSdk
        val configuredPrebuiltDir = prebuiltDir.orNull
        val configuredSkipCargo = skipCargo
        val configuredEnvironment = environment
        val configuredCargo = cargoExecutable
        val cargoTarget = project.rootProject.layout.buildDirectory.dir("cargo")
        val output = project.layout.buildDirectory.dir("rust/${target.triple}/$crate")

        val provider = project.tasks.register(taskName, CargoBuildTask::class.java)
        provider.configure {
            group = "rust"
            description = "Builds $crate for ${target.triple}"

            cratePackage.set(crate)
            rustTarget.set(target.triple)
            profile.set(configuredProfile)
            artifactKind.set(kind)
            features.set(configuredFeatures)
            noDefaultFeatures.set(configuredNoDefaultFeatures)
            extraEnvironment.set(configuredEnvironment)
            cargoExecutable.set(configuredCargo)

            if (target.androidAbi != null) {
                androidAbi.set(target.androidAbi)
                androidMinSdk.set(configuredAndroidMinSdk)
            }

            workspaceDir.set(workspace)
            cargoTargetDir.set(cargoTarget)
            configuredPrebuiltDir?.let { prebuiltDir.set(it) }
            usePrebuilt.set(prebuiltForTarget?.isDirectory == true)
            skipCargo.set(configuredSkipCargo)

            rustSources.from(sources)
            outputDir.set(output)
        }
        return provider
    }

    private fun String.toCamel(): String =
        split('-', '_', '.').joinToString("") { part ->
            part.replaceFirstChar { it.uppercase() }
        }
}
