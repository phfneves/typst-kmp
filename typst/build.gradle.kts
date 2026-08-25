import io.github.phfneves.typst.gradle.CargoBuildTask
import io.github.phfneves.typst.gradle.GenerateTypstFixturesTask
import io.github.phfneves.typst.gradle.HostFamily
import io.github.phfneves.typst.gradle.RustArtifactKind
import io.github.phfneves.typst.gradle.RustTarget
import io.github.phfneves.typst.gradle.RustTargets
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.vanniktech.mavenPublish)
    id("io.github.phfneves.typst.cargo")
}

cargo {
    androidMinSdk = libs.versions.android.minSdk.get().toInt()
}

/** The machine running this build, used to skip targets it could never produce. */
val hostFamily: HostFamily = HostFamily.of(providers.systemProperty("os.name").get())

/** True when native artifacts come from `-Ptypst.prebuiltDir`, so cargo never runs. */
val usePrebuiltNatives: Boolean = providers.gradleProperty("typst.prebuiltDir").isPresent

/**
 * Rust target matching the machine running this build, used for `jvmTest`.
 *
 * Taken from the active toolchain rather than guessed from `os.name`, because on Windows the
 * same OS and architecture serve both the MSVC and the GNU ABI and only the installed toolchain
 * knows which one will link. Guessing meant every IDE-launched test needed a flag to correct it.
 *
 * The fallback covers a machine with no cargo at all, where the value is only ever used to name
 * a task that will fail with an install hint anyway.
 */
val hostRustTarget: RustTarget = cargo.hostTriple()?.let(RustTargets::jvmHostForTriple)
    ?: run {
        val arch = providers.systemProperty("os.arch").get().lowercase()
        val aarch64 = arch == "aarch64" || arch == "arm64"
        when (hostFamily) {
            HostFamily.WINDOWS -> RustTargets.windowsX64Msvc
            HostFamily.MAC -> if (aarch64) RustTargets.macosArm64 else RustTargets.macosX64
            else -> if (aarch64) RustTargets.linuxArm64 else RustTargets.linuxX64
        }
    }

/** JNI shared library for the host, so tests can run against a freshly built Rust crate. */
val hostJniBuild = cargo.build("typst-kmp-jni", hostRustTarget, RustArtifactKind.DYNAMIC_LIB)

kotlin {
    explicitApi()
    jvmToolchain(21)

    compilerOptions {
        // `NativeEngine` is an expect/actual class, which is still flagged as Beta.
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    jvm()

    android {
        namespace = "io.github.phfneves.typst"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        // No host test builder on purpose. `commonTest` would flow into it, and every one of
        // those tests loads the native library through System.loadLibrary, which a plain JVM
        // cannot satisfy. Android is covered by the instrumented device tests instead, which run
        // the identical `commonTest` suite on a real ABI.
        withDeviceTestBuilder { sourceSetTreeName = "test" }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    iosArm64()
    iosSimulatorArm64()
    iosX64()
    macosArm64()
    macosX64()
    linuxX64()
    linuxArm64()
    mingwX64()

    applyDefaultHierarchyTemplate()

    sourceSets {
        // JNI is shared verbatim between the JVM and Android; only the library *loader* differs.
        val jvmCommonMain = create("jvmCommonMain") {
            dependsOn(commonMain.get())
        }
        jvmMain.get().dependsOn(jvmCommonMain)
        androidMain.get().dependsOn(jvmCommonMain)

        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotlinx.serialization.json)
            // Writing the end-to-end PDF out is shared code; only the directory is per platform.
            implementation(libs.kotlinx.io.core)
        }
        androidMain.dependencies {
            // Carries libtypst_kmp_jni.so: the AGP KMP library plugin cannot package jniLibs
            // itself, so a plain com.android.library module ships them.
            api(project(":typst-android-native"))
        }
        getByName("androidDeviceTest").dependencies {
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.test.core)
        }
    }

    // --- Kotlin/Native: cinterop against the C ABI, linking the Rust static archive ------------
    targets.withType<KotlinNativeTarget>().configureEach {
        val descriptor = RustTargets.byKonanTarget(name)
            ?: error("No Rust target mapped for the Kotlin/Native target '$name'.")

        // Skip targets whose Rust artifact this machine could not produce — otherwise an IDE sync
        // tries to build an Apple static library on Windows and fails the whole sync.
        //
        // The host only limits *cargo*, not Kotlin/Native, so this must not apply when prebuilt
        // artifacts are supplied: the publish job assembles every target on one macOS runner and
        // needs all of them wired up.
        if (!descriptor.isBuildableOn(hostFamily) && !usePrebuiltNatives) {
            logger.info("Skipping the Rust build for '$name': not buildable on a $hostFamily host.")
            return@configureEach
        }

        val cargoTask = cargo.build("typst-kmp-cabi", descriptor, RustArtifactKind.STATIC_LIB)
        val headerDir = layout.projectDirectory.dir("../rust/typst-kmp-cabi/include")
        val libraryDir = cargoTask.flatMap { it.outputDir }

        compilations.getByName("main").cinterops.create("typst") {
            defFile(project.file("src/nativeInterop/cinterop/typst.def"))
            includeDirs(headerDir)
            extraOpts(
                "-libraryPath", libraryDir.get().asFile.absolutePath,
                "-staticLibrary", descriptor.staticLibFileName("typst-kmp-cabi"),
            )
        }

        // cinterop needs both the generated header and the archive to exist first.
        tasks.named("cinteropTypst${name.replaceFirstChar { it.uppercase() }}") {
            dependsOn(cargoTask)
        }
    }
}

// --- JVM: one jar of classes, one jar per platform's JNI library -------------------------------

/*
 * Which JVM platforms have a native library in this build.
 *
 * Locally only the host's can be produced, so that is the only classifier jar registered — asking
 * for the others would wire up a cargo build this machine cannot run. The CI publish job passes
 * `-Ptypst.prebuiltDir=…` with binaries built on their own runners, and then all five appear.
 *
 * Registered up front: registering tasks from inside another task's configuration action is not
 * allowed.
 */
val jvmNativeTargets = if (usePrebuiltNatives) RustTargets.jvm else listOf(hostRustTarget)

val jvmNativeBuilds: List<Pair<String, TaskProvider<CargoBuildTask>>> =
    jvmNativeTargets.mapNotNull { target ->
        target.jvmPlatformId?.let { platformId ->
            platformId to cargo.build("typst-kmp-jni", target, RustArtifactKind.DYNAMIC_LIB)
        }
    }

/** `linux-x86_64` → `LinuxX64`, so task names read like the Kotlin target names do. */
fun jvmPlatformTaskSuffix(platformId: String): String {
    val (os, arch) = platformId.split('-', limit = 2)
    val architecture = when (arch) {
        "x86_64" -> "X64"
        "aarch64" -> "Arm64"
        else -> error("No task-name suffix mapped for the JVM platform '$platformId'.")
    }
    return os.replaceFirstChar { it.uppercase() } + architecture
}

/**
 * A jar carrying nothing but JNI libraries, laid out where `NativeLibrary.jvm.kt` looks for them.
 *
 * An extension on `Project` because a top-level function in a build script does not see the
 * script's implicit receiver.
 */
fun Project.registerJvmNativeJar(
    taskName: String,
    classifier: String,
    builds: List<Pair<String, TaskProvider<CargoBuildTask>>>,
): TaskProvider<Jar> = tasks.register<Jar>(taskName) {
    group = "build"
    description = "Packages the JNI library for '$classifier' as a classifier jar."
    // Mirrors jvmJar, whose archive is typst-jvm-<version>.jar.
    archiveBaseName.set("typst")
    archiveAppendix.set("jvm")
    archiveClassifier.set(classifier)
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    builds.forEach { (platformId, build) ->
        from(build.map { it.outputDir }) {
            into("io/github/phfneves/typst/native/$platformId")
        }
    }
}

val jvmNativeJarTasks: List<Pair<String, TaskProvider<Jar>>> =
    jvmNativeBuilds.map { (platformId, build) ->
        platformId to registerJvmNativeJar(
            taskName = "jvmNativeJar${jvmPlatformTaskSuffix(platformId)}",
            classifier = platformId,
            builds = listOf(platformId to build),
        )
    }

/**
 * Every platform in one jar.
 *
 * Opt-in, for an application shipped as a single cross-platform bundle. Locally it degrades to the
 * host's library alone, which is exactly what the old fat jar contained on a dev machine.
 */
val jvmNativeJarAll = registerJvmNativeJar("jvmNativeJarAll", "all", jvmNativeBuilds)

tasks.register("jvmNativeJars") {
    group = "build"
    description = "Builds every per-platform JVM native jar, plus the combined 'all' jar."
    dependsOn(jvmNativeJarTasks.map { it.second })
    dependsOn(jvmNativeJarAll)
}

/*
 * Attach the classifier jars to the KMP `jvm` publication, i.e. to `typst-kmp-jvm`.
 *
 * `mavenPublication` is replayed against the publication whenever it is created, so this neither
 * has to run before nor after the publishing plugin. Signing is safe for the same reason:
 * `signing.sign(publication)` watches the publication's artifacts live, so artifacts added after
 * the signing task was created are still signed and still published as `.asc`.
 */
kotlin.targets.getByName("jvm").mavenPublication {
    jvmNativeJarTasks.forEach { (_, jar) -> artifact(jar) }
    artifact(jvmNativeJarAll)
}

// --- Test fixtures and their inspectable output ------------------------------------------------

/**
 * Where every platform's suite drops the PDF it compiled, so it can actually be opened.
 * One directory per platform, since several suites may run in the same build.
 */
val testArtifactDir: Provider<Directory> = layout.buildDirectory.dir("test-artifacts")

val generateTypstFixtures = tasks.register<GenerateTypstFixturesTask>("generateTypstFixtures") {
    fixtureDir.set(layout.projectDirectory.dir("src/commonTest/typst"))
    packageName.set("io.github.phfneves.typst.fixtures")
    outputDir.set(layout.buildDirectory.dir("generated/typstFixtures/kotlin"))
}

kotlin.sourceSets.commonTest.get().kotlin.srcDir(generateTypstFixtures)

tasks.named<Test>("jvmTest") {
    dependsOn(hostJniBuild)
    val libraryFile = hostJniBuild.map { task ->
        task.outputDir.get().asFile.resolve(hostRustTarget.dynamicLibFileName("typst-kmp-jni"))
    }
    val artifacts = testArtifactDir
    jvmArgumentProviders.add(
        CommandLineArgumentProvider {
            listOf(
                "-Dtypst.kmp.library.path=${libraryFile.get().absolutePath}",
                "-Dtypst.test.output=${artifacts.get().asFile.absolutePath}",
            )
        },
    )
    testLogging { showStandardStreams = true }
}

/*
 * The same suite, this time loading the library the way a consumer does.
 *
 * `jvmTest` hands the loader an absolute path through `typst.kmp.library.path`, so it never walks
 * the `getResourceAsStream` branch that every published artifact depends on. Here the main jar and
 * the host's classifier jar are the only things on the classpath that could carry the library, and
 * nothing tells the loader where to look.
 */
val hostNativeJar = jvmNativeJarTasks
    .firstOrNull { (platformId, _) -> platformId == hostRustTarget.jvmPlatformId }
    ?.second

if (hostNativeJar != null) {
    val jvmTarget = kotlin.targets.getByName("jvm")
    val jvmMainOutputs = jvmTarget.compilations.getByName("main").output.allOutputs
    val jvmTestCompilation = jvmTarget.compilations.getByName("test")
    val jvmJar = tasks.named<Jar>("jvmJar")
    // A sibling of the jvmTest output: both suites write typst-kmp-report-jvm.pdf, and both are
    // picked up by the workflow's upload of build/test-artifacts/.
    val jarTestArtifacts = layout.buildDirectory.dir("test-artifacts/jvm-from-jar")

    tasks.register<Test>("jvmJarTest") {
        group = "verification"
        description =
            "Runs the common suite against the assembled JVM jars, without a library path override."

        testClassesDirs = jvmTestCompilation.output.classesDirs

        // The jars stand in for the main compilation's classes and resources. Subtracting those
        // directories is what makes the run prove the library really came out of a jar: leave them
        // on and a regression that refilled the main jar's resources would go unnoticed here.
        classpath = files(
            jvmJar.flatMap { it.archiveFile },
            hostNativeJar.flatMap { it.archiveFile },
            jvmTestCompilation.output.allOutputs,
        ) + ((jvmTestCompilation.runtimeDependencyFiles ?: files()) - jvmMainOutputs)

        // Matches the framework jvmTest defaults to, and hence the kotlin-test variant Kotlin
        // infers from it. If one ever moves to the JUnit platform, the other must follow.
        useJUnit()

        jvmArgumentProviders.add(
            CommandLineArgumentProvider {
                listOf("-Dtypst.test.output=${jarTestArtifacts.get().asFile.absolutePath}")
            },
        )
        testLogging { showStandardStreams = true }
    }
}

// Kotlin/Native test binaries read the directory from the environment instead.
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
    environment("TYPST_TEST_OUTPUT", testArtifactDir.get().asFile.absolutePath)
    testLogging { showStandardStreams = true }
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    coordinates(group.toString(), "typst-kmp", version.toString())

    pom {
        name = "typst-kmp"
        description = "The Typst typesetting compiler as a Kotlin Multiplatform library."
        inceptionYear = "2026"
        url = "https://github.com/phfneves/typst-kmp"
        licenses {
            license {
                name = "The Apache License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "repo"
            }
        }
        developers {
            developer {
                id = "phfneves"
                name = "Pedro Neves"
                url = "https://github.com/phfneves"
            }
        }
        scm {
            url = "https://github.com/phfneves/typst-kmp"
            connection = "scm:git:git://github.com/phfneves/typst-kmp.git"
            developerConnection = "scm:git:ssh://git@github.com/phfneves/typst-kmp.git"
        }
    }
}
