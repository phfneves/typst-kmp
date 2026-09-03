import io.github.phfneves.typst.gradle.CargoBuildTask
import io.github.phfneves.typst.gradle.EmbedJsSourceTask
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

/**
 * Subdirectory of the browser distribution the WebAssembly module is published into.
 *
 * The engine looks here by default; see the `webAssetBaseUrl` parameter of `TypstConfig`.
 */
val WEB_ASSET_DIRECTORY = "typst-kmp"

/** Where the generated karma snippet that serves those assets to the browser suite lands. */
val karmaConfigDir: Provider<Directory> = layout.buildDirectory.dir("karma.config.d")

/** The machine running this build, used to skip targets it could never produce. */
val hostFamily: HostFamily = HostFamily.of(providers.systemProperty("os.name").get())

/** True when native artifacts come from `-Ptypst.prebuiltDir`, so cargo never runs. */
val usePrebuiltNatives: Boolean = providers.gradleProperty("typst.prebuiltDir").isPresent

/** True when `-Ptypst.skipCargo=true` asks for a Kotlin type check with no Rust behind it. */
val skipCargo: Boolean =
    providers.gradleProperty("typst.skipCargo").map(String::toBoolean).getOrElse(false)

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

/**
 * The cabi build that stands in as the source of the cbindgen-generated C header.
 *
 * `rust/typst-kmp-cabi/include/typst_kmp.h` is a build output — the crate's `build.rs` writes it —
 * yet *every* native target's cinterop reads it, including the ones this host cannot build and
 * which therefore get no cargo task of their own. Nothing ordered those cinterops after a build
 * that produces the header, so on a fresh clone they raced it and failed with
 * "'typst_kmp.h' file not found"; on a machine that had built once before, the header left behind
 * by the previous run hid the problem. macOS is where it always showed, because three of the eight
 * native targets are unbuildable there.
 *
 * The header is target-independent, so any build of the crate will do. Picking the host's own
 * target means this adds an ordering edge and no work: that build is already in the graph.
 */
val cabiHeaderBuild: TaskProvider<CargoBuildTask> = cargo.build(
    "typst-kmp-cabi",
    RustTargets.native.firstOrNull { it.triple == hostRustTarget.triple }
        ?: RustTargets.native.first { it.isBuildableOn(hostFamily) },
    RustArtifactKind.STATIC_LIB,
)

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

    // Browser only, on both web targets. The engine runs in a Web Worker and reaches its module
    // over HTTP, neither of which Node offers without shims that would only ever be exercised by
    // this project's own tests.
    val karmaConfig = karmaConfigDir.get().asFile
    js {
        browser {
            testTask {
                useKarma {
                    useChromeHeadless()
                    // Serves the WebAssembly module; see karmaWebAssetsConfig below.
                    useConfigDirectory(karmaConfig)
                }
            }
        }
    }
    wasmJs {
        browser {
            testTask {
                useKarma {
                    useChromeHeadless()
                    useConfigDirectory(karmaConfig)
                }
            }
        }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        // JNI is shared verbatim between the JVM and Android; only the library *loader* differs.
        val jvmCommonMain = create("jvmCommonMain") {
            dependsOn(commonMain.get())
        }
        jvmMain.get().dependsOn(jvmCommonMain)
        androidMain.get().dependsOn(jvmCommonMain)

        // webMain/webTest group js and wasmJs and come from the default hierarchy template — the
        // one intermediate set here that did not have to be hand-rolled. The worker protocol lives
        // there; only the handful of lines that touch a JavaScript value are per target.

        // Everything except web, which has no file system to write the inspectable PDF to.
        val fileTest = create("fileTest") { dependsOn(commonTest.get()) }
        jvmTest.get().dependsOn(fileTest)
        nativeTest.get().dependsOn(fileTest)
        getByName("androidDeviceTest").dependsOn(fileTest)

        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotlinx.serialization.json)
        }
        webMain.dependencies {
            // Typed arrays and the DOM, which Kotlin/Wasm does not carry in its standard library.
            implementation(libs.kotlinx.browser)
        }
        fileTest.dependencies {
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

        // Every native target declares the interop, including one this machine could never link.
        // `nativeMain` is only handed a commonized interop when *all* of its targets carry one,
        // and the source sets exist whether or not the host can build the targets behind them —
        // so declaring this conditionally left the shared native code without a single C symbol.
        // The header comes from cabiHeaderBuild's staged output, never from the crate's source
        // tree: that copy is gitignored, so a fresh checkout only has one if cargo actually ran —
        // which a build-cache hit skips. See CargoBuildTask.stageHeaders.
        val cinterop = compilations.getByName("main").cinterops.create("typst") {
            defFile(project.file("src/nativeInterop/cinterop/typst.def"))
            includeDirs(cabiHeaderBuild.flatMap { it.outputDir }.get().asFile.resolve("include"))
        }

        // The archive is the part the host does limit: cargo cannot produce an Apple static
        // library on Windows. Leaving it out still yields bindings — enough to compile and to
        // commonize — and only linking a real binary needs the bytes, which is something this
        // host could not do either way.
        //
        // Prebuilt artifacts lift the restriction: the publish job assembles every target on one
        // macOS runner and needs all of them wired up.
        if (!descriptor.isBuildableOn(hostFamily) && !usePrebuiltNatives) {
            logger.info("Skipping the Rust build for '$name': not buildable on a $hostFamily host.")
            // No archive to link, but the bindings still need the generated header, and nothing
            // else in this branch would wait for it. See cabiHeaderBuild.
            tasks.named("cinteropTypst${name.replaceFirstChar { it.uppercase() }}") {
                dependsOn(cabiHeaderBuild)
            }
            return@configureEach
        }

        val cargoTask = cargo.build("typst-kmp-cabi", descriptor, RustArtifactKind.STATIC_LIB)

        // `-staticLibrary` copies the archive into the klib, so naming one that cargo was told
        // not to build fails the interop outright — and `typst.skipCargo` exists precisely to
        // type-check Kotlin on a machine with no Rust toolchain. Bindings alone get that far;
        // linking a binary is what the missing archive would cost, and this flag never links one.
        if (!skipCargo) {
            cinterop.extraOpts(
                "-libraryPath", cargoTask.flatMap { it.outputDir }.get().asFile.absolutePath,
                "-staticLibrary", descriptor.staticLibFileName("typst-kmp-cabi"),
            )
        }

        // cinterop needs both the generated header and the archive to exist first. The header
        // comes from cabiHeaderBuild even here, where this target's own cargo build would also
        // produce one: pointing every target at a single staged copy is what keeps includeDirs
        // above target-independent. When the two are the same task, this edge is a no-op.
        tasks.named("cinteropTypst${name.replaceFirstChar { it.uppercase() }}") {
            dependsOn(cargoTask, cabiHeaderBuild)
        }
    }
}

// --- Web: the WebAssembly module and the worker that drives it ---------------------------------

/**
 * `typst_kmp_wasm.js` plus `typst_kmp_wasm_bg.wasm` — the pair the browser fetches at runtime.
 *
 * Unlike every other platform, these are not linked into the artifact the compiler produces: a
 * WebAssembly module is loaded over HTTP, so they travel as resources and the engine is told where
 * to find them.
 */
val wasmBindgen = cargo.wasmBindgen("typst-kmp-wasm")

/**
 * The worker script, compiled into the Kotlin sources as a string constant.
 *
 * See `EmbedJsSourceTask`: a string can be started as a same-origin worker from a `Blob`, which
 * means nothing about the worker has to be hosted or resolved by a bundler — and only the module
 * itself is left needing a URL.
 */
val embedWorkerScript = tasks.register<EmbedJsSourceTask>("embedTypstWorkerScript") {
    source.set(layout.projectDirectory.file("../rust/typst-kmp-wasm/js/typst-kmp-worker.js"))
    packageName.set("io.github.phfneves.typst.internal")
    objectName.set("TypstWorkerScript")
    constantName.set("SOURCE")
    outputDir.set(layout.buildDirectory.dir("generated/typstWorker/kotlin"))
}

kotlin.sourceSets.getByName("webMain").kotlin.srcDir(embedWorkerScript)

/**
 * The module staged under the directory name the engine looks in, ready to be used as a resource
 * root.
 *
 * The nesting is the point: a resource root contributes its *contents*, so the extra level is what
 * puts the two files at `typst-kmp/…` rather than at the root of the distribution.
 */
val stageWebAssets = tasks.register<Sync>("stageWebAssets") {
    group = "build"
    description = "Stages the WebAssembly module under $WEB_ASSET_DIRECTORY/."
    from(wasmBindgen)
    into(layout.buildDirectory.dir("generated/webAssets/$WEB_ASSET_DIRECTORY"))
}

/*
 * Deliberately *not* added to jsMain/wasmJsMain resources.
 *
 * That would be the obvious move, and it does nothing useful: Kotlin/JS does not replay a klib's
 * resources into a consumer's distribution, and the API that would — the one Compose Resources
 * uses — is explicitly closed to plugins other than Compose. It would only bury a 40 MB copy of
 * the module inside each published klib, on top of the classifier zip below.
 *
 * So the module travels one way only, as `webAssetsZip`, and reaches a page either because the
 * consumer unpacked it into their distribution or because `TypstConfig.webAssetBaseUrl` points at
 * a copy they host. This project's own browser suite gets it a third way, straight from the staged
 * directory; see karmaWebAssetsConfig below.
 */

/*
 * Karma serves the browser suite out of a directory of its own and knows nothing about a Kotlin
 * source set's resources, so the staged assets have to be handed to it explicitly. Generated
 * rather than checked in because the pattern and the proxy target are absolute paths.
 */
val karmaWebAssetsConfig = tasks.register("karmaWebAssetsConfig") {
    group = "verification"
    description = "Writes the karma snippet that serves the WebAssembly module to the test page."
    // Captured into locals: a lambda that reads a build-script property holds a reference to the
    // script object, which the configuration cache cannot serialise.
    val assetDirectory = WEB_ASSET_DIRECTORY
    val stagedRoot = layout.buildDirectory.dir("generated/webAssets")
        .map { it.asFile.invariantSeparatorsPath }
    val target = karmaConfigDir.map { it.file("typst-kmp-assets.js") }
    inputs.property("stagedRoot", stagedRoot)
    inputs.property("assetDirectory", assetDirectory)
    outputs.file(target)
    doLast {
        // Karma exposes anything it serves under /absolute<path>; the proxy is what puts it at the
        // same relative URL the engine asks for on a real page.
        val root = stagedRoot.get()
        target.get().asFile.writeText(
            """
            // Generated by :typst:karmaWebAssetsConfig. Do not edit.
            config.files.unshift({
                pattern: '$root/$assetDirectory/**',
                served: true,
                included: false,
                watched: false,
                nocache: true,
            });
            config.proxies['/$assetDirectory/'] = '/absolute$root/$assetDirectory/';
            // Instantiating a 40 MB module and then typesetting a document takes far longer than a
            // browser test usually does.
            config.browserNoActivityTimeout = 300000;
            config.captureTimeout = 300000;

            """.trimIndent(),
        )
    }
}

tasks.named("jsBrowserTest") { dependsOn(karmaWebAssetsConfig, stageWebAssets) }
tasks.named("wasmJsBrowserTest") { dependsOn(karmaWebAssetsConfig, stageWebAssets) }

/** The two files as a zip, published beside each web artifact so a consumer can fetch them. */
val webAssetsZip = tasks.register<Zip>("webAssetsZip") {
    group = "build"
    description = "Packages the WebAssembly module for consumers to host themselves."
    archiveBaseName.set("typst")
    archiveClassifier.set("webassets")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(wasmBindgen)
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
}

/*
 * Attached to both web publications, so `typst-kmp-js` and `typst-kmp-wasm-js` each carry the
 * module a consumer needs to serve. Same mechanism as the JVM classifier jars below.
 */
listOf("js", "wasmJs").forEach { targetName ->
    kotlin.targets.getByName(targetName).mavenPublication { artifact(webAssetsZip) }
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
