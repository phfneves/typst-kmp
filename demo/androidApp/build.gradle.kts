plugins {
    alias(libs.plugins.android.application)
}

/*
 * The Android shell: a manifest, a theme and a dependency. All the code is in `:composeApp`,
 * because AGP 9 no longer allows `com.android.application` and the Kotlin Multiplatform plugin in
 * the same module.
 */

/**
 * ABIs to package. All of them by default, which is what a release build wants.
 *
 * Narrowing this to the one an emulator actually loads is worth a lot locally: every extra ABI is
 * another full cargo build of the Typst tree.
 */
val selectedAbis: List<String>? = providers.gradleProperty("demo.androidAbis").orNull
    ?.split(',')
    ?.map { it.trim() }
    ?.filter { it.isNotEmpty() }

android {
    namespace = "io.github.phfneves.typst.demo.app"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.phfneves.typst.demo"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.compileSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"

        selectedAbis?.let { abis -> ndk { abiFilters += abis } }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // Uncompressed, so the platform can mmap the Typst library straight out of the APK.
    packaging {
        jniLibs.useLegacyPackaging = false
    }
}

dependencies {
    implementation(project(":composeApp"))
}
