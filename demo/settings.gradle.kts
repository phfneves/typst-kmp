pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
    // The library's catalogue, so Kotlin, AGP and the Android SDK levels are defined once.
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "typst-kmp-demo"

/*
 * The demo is a build of its own so that it depends on typst-kmp by its published coordinates,
 * exactly as any other consumer would. `includeBuild` then resolves those coordinates against the
 * sources next door, so nothing has to be published to run it.
 *
 * The substitution is spelled out rather than inferred: the Gradle project is called `typst` while
 * the artifact is `typst-kmp`, and relying on Gradle to match those through the publications of an
 * included build is more fragile than saying it.
 */
includeBuild("..") {
    dependencySubstitution {
        substitute(module("io.github.phfneves:typst-kmp")).using(project(":typst"))
    }
}

include(":composeApp")
include(":androidApp")
include(":desktopApp")
