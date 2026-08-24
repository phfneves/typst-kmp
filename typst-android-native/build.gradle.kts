import io.github.phfneves.typst.gradle.CollectJniLibsTask
import io.github.phfneves.typst.gradle.RustArtifactKind
import io.github.phfneves.typst.gradle.RustTargets

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.vanniktech.mavenPublish)
    id("io.github.phfneves.typst.cargo")
}

/*
 * This module exists solely to carry `libtypst_kmp_jni.so`.
 *
 * The AGP Kotlin Multiplatform library plugin (`com.android.kotlin.multiplatform.library`) does
 * not support `jniLibs`, `packagingOptions` or `externalNativeBuild`, and the Android
 * documentation points at a separate `com.android.library` module as the way out. `:typst`
 * depends on this AAR from `androidMain`.
 */

android {
    namespace = "io.github.phfneves.typst.nativelib"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        ndk {
            abiFilters += RustTargets.android.mapNotNull { it.androidAbi }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // Keep the libraries uncompressed so the platform can mmap them straight out of the APK.
    packaging {
        jniLibs.useLegacyPackaging = false
    }
}

cargo {
    androidMinSdk = libs.versions.android.minSdk.get().toInt()
}

// Register the cargo builds up front; registering tasks from inside another task's configuration
// action is not safe.
val androidBuilds = RustTargets.android.associate { target ->
    requireNotNull(target.androidAbi) to
        cargo.build("typst-kmp-jni", target, RustArtifactKind.DYNAMIC_LIB)
}

val assembleJniLibs = tasks.register<CollectJniLibsTask>("assembleJniLibs") {
    group = "rust"
    description = "Collects the Android JNI libraries produced by cargo-ndk."
    outputDir.set(layout.buildDirectory.dir("generated/jniLibs"))
    into(outputDir)
    androidBuilds.forEach { (abi, cargoTask) ->
        from(cargoTask.map { it.outputDir }) {
            into(abi)
        }
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(assembleJniLibs) { it.outputDir }
    }
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates(group.toString(), "typst-kmp-android-native", version.toString())

    pom {
        name = "typst-kmp-android-native"
        description = "Android JNI libraries for typst-kmp."
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
