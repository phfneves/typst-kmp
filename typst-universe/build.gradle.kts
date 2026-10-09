plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.vanniktech.mavenPublish)
}

/*
 * Downloads packages from Typst Universe.
 *
 * A module of its own so that Ktor stays out of `typst-kmp`: an application that vendors its
 * packages, or fetches them some other way, should not have to carry an HTTP client.
 */

kotlin {
    explicitApi()
    jvmToolchain(21)

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation()

    // The same targets as :typst, which this extends.
    jvm()
    android {
        namespace = "io.github.phfneves.typst.universe"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
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
    js { browser { testTask { useKarma { useChromeHeadless() } } } }
    wasmJs { browser { testTask { useKarma { useChromeHeadless() } } } }

    applyDefaultHierarchyTemplate()

    sourceSets {
        // Everything except web, which has no file system to cache into.
        val fileMain = create("fileMain") { dependsOn(commonMain.get()) }
        jvmMain.get().dependsOn(fileMain)
        androidMain.get().dependsOn(fileMain)
        nativeMain.get().dependsOn(fileMain)
        val fileTest = create("fileTest") { dependsOn(commonTest.get()) }
        jvmTest.get().dependsOn(fileTest)
        nativeTest.get().dependsOn(fileTest)

        commonMain.dependencies {
            api(project(":typst"))
            api(libs.ktor.client.core)
        }
        fileMain.dependencies {
            implementation(libs.kotlinx.io.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
    }
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    coordinates(group.toString(), "typst-kmp-universe", version.toString())

    pom {
        name = "typst-kmp-universe"
        description = "Downloads Typst Universe packages for typst-kmp."
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
