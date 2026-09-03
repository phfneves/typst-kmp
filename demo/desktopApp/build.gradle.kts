import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvmToolchain(21)
}

/*
 * The JNI library for this machine.
 *
 * On the JVM, typst-kmp ships its classes and its native library as separate artifacts, and Gradle
 * module metadata cannot reach a classifier — so the native jar is always one explicit extra line.
 * A published build writes it as:
 *
 *     runtimeOnly("io.github.phfneves:typst-kmp-jvm:<version>:windows-x86_64")
 *
 * Here the library is the sources next door, so the same jar comes from the task that assembles it
 * instead of from a repository. The classifier is resolved exactly as the README suggests.
 */
val hostClassifier: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = when (val value = System.getProperty("os.arch").lowercase()) {
        "x86_64", "amd64" -> "x86_64"
        "aarch64", "arm64" -> "aarch64"
        else -> error("No typst-kmp JVM library for the architecture '$value'.")
    }
    when {
        os.startsWith("windows") -> "windows-x86_64"
        os.startsWith("mac") || os.startsWith("darwin") -> "macos-$arch"
        else -> "linux-$arch"
    }
}

/** `windows-x86_64` → `:typst:jvmNativeJarWindowsX64`, the task that packages that classifier. */
val hostNativeJarTask: String = run {
    val (os, arch) = hostClassifier.split('-', limit = 2)
    val suffix = os.replaceFirstChar { it.uppercase() } + when (arch) {
        "x86_64" -> "X64"
        else -> "Arm64"
    }
    ":typst:jvmNativeJar$suffix"
}

dependencies {
    // Brings the Compose desktop runtime along, which :composeApp exposes with `api`.
    implementation(project(":composeApp"))

    runtimeOnly(
        fileTree(layout.projectDirectory.dir("../../typst/build/libs")) {
            include("typst-jvm-*-$hostClassifier.jar")
        },
    )
}

// The jar above only exists once the library build has assembled it, and it is a different build.
listOf("run", "createDistributable", "runDistributable", "packageDistributionForCurrentOS")
    .forEach { name ->
        tasks.matching { it.name == name }.configureEach {
            dependsOn(gradle.includedBuild("typst-kmp").task(hostNativeJarTask))
        }
    }

compose.desktop {
    application {
        mainClass = "io.github.phfneves.typst.demo.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "typst-kmp-demo"
            packageVersion = "1.0.0"
        }
    }
}
