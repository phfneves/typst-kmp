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

/*
 * Which ABIs to build.
 *
 * All three by default. `-Ptypst.androidAbis=x86_64` narrows it, which matters for the
 * instrumented-test job: an emulator only ever loads its own ABI, and each extra one is a full
 * rebuild of the Typst tree for another triple.
 */
val selectedAbis: List<String> =
    providers.gradleProperty("typst.androidAbis").orNull
        ?.split(',')
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?: RustTargets.android.mapNotNull { it.androidAbi }

val selectedAndroidTargets = RustTargets.android.filter { it.androidAbi in selectedAbis }

require(selectedAndroidTargets.isNotEmpty()) {
    "typst.androidAbis=${selectedAbis.joinToString(",")} matched no known ABI. " +
        "Known: ${RustTargets.android.mapNotNull { it.androidAbi }.joinToString(", ")}"
}

android {
    namespace = "io.github.phfneves.typst.nativelib"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    ndkVersion = libs.versions.android.ndk.get()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        ndk {
            abiFilters += selectedAbis
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

/*
 * Where cargo-ndk finds the NDK.
 *
 * An NDK already exported by the environment wins — that is the case on CI runners, whose image
 * ships one that need not match `ndkVersion`. Otherwise fall back to the NDK that AGP resolves
 * under the SDK, so a local build works with nothing exported by hand.
 *
 * `orElse` keeps the fallback lazy: AGP's provider throws when no NDK is installed, and it is
 * never resolved while an environment variable is present.
 */
val ndkPath = providers.environmentVariable("ANDROID_NDK_HOME")
    .orElse(providers.environmentVariable("ANDROID_NDK_ROOT"))
    .orElse(androidComponents.sdkComponents.ndkDirectory.map { it.asFile.absolutePath })

cargo {
    androidMinSdk = libs.versions.android.minSdk.get().toInt()
    environment.put("ANDROID_NDK_HOME", ndkPath)
}

// Register the cargo builds up front; registering tasks from inside another task's configuration
// action is not safe.
val androidBuilds = selectedAndroidTargets.associate { target ->
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
