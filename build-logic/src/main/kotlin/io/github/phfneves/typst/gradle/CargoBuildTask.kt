package io.github.phfneves.typst.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/**
 * Builds one crate of the `rust/` workspace for one Rust target and copies the resulting artifact
 * into [outputDir] under a stable name.
 *
 * Two modes:
 *  * **cargo** (default) — invokes `cargo build`, or `cargo ndk … build` when [androidAbi] is set.
 *  * **prebuilt** — when [prebuiltDir] is present, no toolchain is required and the artifact is
 *    copied from `<prebuiltDir>/<rustTarget>/`. This is what lets the CI publish job assemble
 *    every platform's artifacts on a single runner.
 *
 * The task is configuration-cache safe: it touches no `Project` state at execution time and uses
 * injected [ExecOperations] / [FileSystemOperations].
 */
@CacheableTask
abstract class CargoBuildTask : DefaultTask() {

    /** Cargo package to build, e.g. `typst-kmp-cabi`. */
    @get:Input
    abstract val cratePackage: Property<String>

    /** Rust target triple, e.g. `aarch64-apple-ios`. */
    @get:Input
    abstract val rustTarget: Property<String>

    /** Cargo profile: `release` or `dev`. */
    @get:Input
    abstract val profile: Property<String>

    @get:Input
    abstract val artifactKind: Property<RustArtifactKind>

    @get:Input
    @get:Optional
    abstract val androidAbi: Property<String>

    @get:Input
    @get:Optional
    abstract val androidMinSdk: Property<Int>

    @get:Input
    abstract val features: ListProperty<String>

    @get:Input
    abstract val noDefaultFeatures: Property<Boolean>

    @get:Input
    abstract val extraEnvironment: MapProperty<String, String>

    /** Every source file cargo depends on. Deliberately excludes `target/`. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val rustSources: ConfigurableFileCollection

    /** Root of the cargo workspace (the `rust/` directory). */
    @get:Internal
    abstract val workspaceDir: DirectoryProperty

    /** Shared `--target-dir`, kept out of the workspace so `rustSources` stays clean. */
    @get:Internal
    abstract val cargoTargetDir: DirectoryProperty

    @get:Internal
    abstract val prebuiltDir: DirectoryProperty

    /** True when [prebuiltDir] points at an existing directory for this Rust target. */
    @get:Input
    abstract val usePrebuilt: Property<Boolean>

    /**
     * Produce an empty output directory instead of building.
     *
     * Driven by `-Ptypst.skipCargo=true`, this exists so that the Kotlin sources can be
     * type-checked on a machine without a Rust toolchain. Anything actually *running* the native
     * library will fail, which is the intended trade.
     */
    @get:Input
    abstract val skipCargo: Property<Boolean>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    protected abstract val execOps: ExecOperations

    @get:Inject
    protected abstract val fsOps: FileSystemOperations

    @TaskAction
    fun build() {
        val target = rustTarget.get()
        val crate = cratePackage.get()
        val artifactName = artifactFileName(crate, target, artifactKind.get())

        if (usePrebuilt.get()) {
            copyPrebuilt(target, artifactName)
            return
        }

        if (skipCargo.get()) {
            logger.warn(
                "typst.skipCargo is set: not building $crate for $target. " +
                    "The resulting artifacts will not contain a native library.",
            )
            outputDir.get().asFile.mkdirs()
            return
        }

        runCargo(target, crate)
        collectArtifact(target, artifactName)
    }

    private fun copyPrebuilt(target: String, artifactName: String) {
        val source = prebuiltDir.get().asFile.resolve(target).resolve(artifactName)
        if (!source.isFile) {
            throw GradleException(
                "typst.prebuiltDir is set but $source is missing. " +
                    "Expected layout: <prebuiltDir>/<rustTarget>/<artifact>.",
            )
        }
        val destination = outputDir.get().asFile
        destination.mkdirs()
        source.copyTo(destination.resolve(artifactName), overwrite = true)
        logger.lifecycle("Using prebuilt $artifactName for $target")
    }

    private fun runCargo(target: String, crate: String) {
        val args = mutableListOf<String>()
        val abi = androidAbi.orNull
        if (abi != null) {
            // cargo-ndk selects the Rust target itself from the ABI, so `--target` must not be
            // passed as well. Note the API level flag is `-P`, uppercase, since cargo-ndk 4.
            args += listOf("ndk", "-t", abi)
            androidMinSdk.orNull?.let { args += listOf("-P", it.toString()) }
            args += listOf("build", "--package", crate)
        } else {
            args += listOf("build", "--package", crate, "--target", target)
        }
        if (profile.get() == "release") args += "--release"
        if (noDefaultFeatures.get()) args += "--no-default-features"
        features.get().takeIf { it.isNotEmpty() }?.let { args += listOf("--features", it.joinToString(",")) }
        args += listOf("--target-dir", cargoTargetDir.get().asFile.absolutePath)

        val stderr = ByteArrayOutputStream()
        val result = try {
            execOps.exec {
                commandLine(listOf("cargo") + args)
                workingDir = workspaceDir.get().asFile
                environment.putAll(extraEnvironment.get())
                errorOutput = stderr
                isIgnoreExitValue = true
            }
        } catch (cause: Exception) {
            throw GradleException(
                "Could not run 'cargo'. Install the Rust toolchain from https://rustup.rs and make " +
                    "sure cargo is on PATH." +
                    (if (abi != null) " Android builds also need 'cargo install cargo-ndk'." else "") +
                    " To type-check the Kotlin sources without a Rust toolchain, build with " +
                    "-Ptypst.skipCargo=true.",
                cause,
            )
        }
        System.err.write(stderr.toByteArray())
        System.err.flush()
        if (result.exitValue != 0) {
            throw GradleException(
                "cargo ${args.joinToString(" ")} failed with exit code ${result.exitValue}. " +
                    "Make sure the '$target' target is installed (rustup target add $target).",
            )
        }
    }

    private fun collectArtifact(target: String, artifactName: String) {
        val profileDir = if (profile.get() == "release") "release" else "debug"
        val produced = cargoTargetDir.get().asFile
            .resolve(target).resolve(profileDir).resolve(artifactName)
        if (!produced.isFile) {
            throw GradleException("cargo did not produce $produced")
        }
        val destination = outputDir.get().asFile
        fsOps.delete { delete(destination) }
        destination.mkdirs()
        produced.copyTo(destination.resolve(artifactName), overwrite = true)
    }

    private fun artifactFileName(crate: String, target: String, kind: RustArtifactKind): String {
        val descriptor = RustTargets.native.firstOrNull { it.triple == target }
            ?: RustTargets.jvm.firstOrNull { it.triple == target }
            ?: RustTargets.android.firstOrNull { it.triple == target }
            ?: RustTarget(target)
        return when (kind) {
            RustArtifactKind.STATIC_LIB -> descriptor.staticLibFileName(crate)
            RustArtifactKind.DYNAMIC_LIB -> descriptor.dynamicLibFileName(crate)
        }
    }
}
