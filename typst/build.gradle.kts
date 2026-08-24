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

// --- JVM: ship the JNI library inside the jar and point tests at the freshly built one ---------

/*
 * Which JVM platforms end up inside the jar.
 *
 * Locally only the host's library can be produced, so that is all the jar gets. The CI publish
 * job passes `-Ptypst.prebuiltDir=…` with binaries built on their own runners, and then every
 * platform is packaged into a single jar that works everywhere.
 *
 * Registered up front: registering tasks from inside another task's configuration action is not
 * allowed.
 */
val jvmNativeTargets = if (usePrebuiltNatives) RustTargets.jvm else listOf(hostRustTarget)

val jvmNativeBuilds = jvmNativeTargets.mapNotNull { target ->
    target.jvmPlatformId?.let { platformId ->
        platformId to cargo.build("typst-kmp-jni", target, RustArtifactKind.DYNAMIC_LIB)
    }
}

tasks.named<ProcessResources>("jvmProcessResources") {
    jvmNativeBuilds.forEach { (platformId, task) ->
        from(task.map { it.outputDir }) {
            into("io/github/phfneves/typst/native/$platformId")
        }
    }
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
