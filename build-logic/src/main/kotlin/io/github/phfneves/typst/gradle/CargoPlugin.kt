package io.github.phfneves.typst.gradle

import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.TaskProvider
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

    init {
        workspaceDir.convention(project.rootProject.layout.projectDirectory.dir("rust"))
        profile.convention(project.providers.gradleProperty("typst.cargoProfile").orElse("release"))
        noDefaultFeatures.convention(false)
        skipCargo.convention(
            project.providers.gradleProperty("typst.skipCargo").map { it.toBoolean() }.orElse(false),
        )
        project.providers.gradleProperty("typst.prebuiltDir").orNull?.let {
            prebuiltDir.set(project.layout.projectDirectory.dir(it))
        }
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
