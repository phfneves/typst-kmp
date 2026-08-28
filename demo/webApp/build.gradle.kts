plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.jetbrainsCompose)
    alias(libs.plugins.composeCompiler)
}

/*
 * The browser shell, the counterpart of `desktopApp` and `androidApp`.
 *
 * Two distributions rather than one, because typst-kmp targets both web flavours and the demo is
 * how each of them is proven to work: `wasmJsBrowserDistribution` needs a WasmGC-capable browser
 * and is the faster of the two, `jsBrowserDistribution` runs anywhere.
 */
kotlin {
    jvmToolchain(21)

    js(IR) {
        browser()
        binaries.executable()
    }
    wasmJs {
        browser()
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":composeApp"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.ui)
        }
    }
}

/*
 * The WebAssembly module, copied into each distribution at `typst-kmp/`.
 *
 * This is the step a real consumer has to take too, and doing it explicitly here is the point: the
 * library cannot place its own resources into someone else's browser distribution, so the demo
 * shows the copy rather than hiding it. `desktopApp` reaches into the included build for the JNI
 * jar the same way.
 *
 * A published build writes this as an unzip of `typst-kmp-js:<version>:webassets` instead; see the
 * README's Web section.
 */
val libraryBuild = gradle.includedBuild("typst-kmp")

val copyTypstWebAssets = tasks.register<Copy>("copyTypstWebAssets") {
    group = "build"
    description = "Copies the typst-kmp WebAssembly module into the browser distributions."
    dependsOn(libraryBuild.task(":typst:stageWebAssets"))
    from(rootDir.resolve("../typst/build/generated/webAssets"))
    into(layout.buildDirectory.dir("typstWebAssets"))
}

// Both the dev server and the packaged distribution need the module beside the page.
listOf(
    "jsBrowserDevelopmentExecutableDistribution",
    "jsBrowserDistribution",
    "jsBrowserDevelopmentRun",
    "jsBrowserProductionRun",
    "wasmJsBrowserDevelopmentExecutableDistribution",
    "wasmJsBrowserDistribution",
    "wasmJsBrowserDevelopmentRun",
    "wasmJsBrowserProductionRun",
).forEach { name ->
    tasks.matching { it.name == name }.configureEach { dependsOn(copyTypstWebAssets) }
}

listOf("jsProcessResources", "wasmJsProcessResources").forEach { name ->
    tasks.matching { it.name == name }.configureEach {
        this as Copy
        from(copyTypstWebAssets)
    }
}
