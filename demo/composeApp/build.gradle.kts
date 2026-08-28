import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.composeCompiler)
}

/*
 * Everything the demo *is* lives here; `androidApp`, `desktopApp` and `iosApp` are shells that do
 * nothing but start it. That split is forced on the Android side — AGP 9 no longer allows
 * `com.android.application` and the Kotlin Multiplatform plugin in one module — and the other two
 * follow it so the three entry points look alike.
 */
kotlin {
    jvmToolchain(21)

    jvm()

    android {
        namespace = "io.github.phfneves.typst.demo"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    // No iosX64: Compose Multiplatform no longer publishes for the Intel simulator, even though
    // the library itself still targets it.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "ComposeApp"
            // Static, so the Typst archive that cinterop links in ends up inside the framework.
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            // Substituted for the `:typst` project by demo/settings.gradle.kts, but written the
            // way a real consumer writes it.
            implementation("io.github.phfneves:typst-kmp:${property("typst.version")}")

            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            // Carries decodeToImageBitmap(), which turns a rendered PNG page into something
            // Compose can draw without a single platform-specific line.
            implementation(libs.compose.components.resources)
            implementation(libs.kotlinx.coroutines.core)
        }

        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            // FileProvider, used to hand the exported PDF to another app.
            api(libs.androidx.core.ktx)
        }

        // `api` so :desktopApp inherits the Compose desktop runtime without repeating the line.
        jvmMain.dependencies {
            api(compose.desktop.currentOs)
        }
    }
}
