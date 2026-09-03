package io.github.phfneves.typst

import androidx.test.platform.app.InstrumentationRegistry

internal actual val platformName: String = "android-" + android.os.Build.SUPPORTED_ABIS.first()

/**
 * Where the instrumented suite writes the PDF it compiled.
 *
 * `additionalTestOutputDir` is the runner argument AGP passes when it wants files back off the
 * device: everything written there is pulled into
 * `build/outputs/connected_android_test_additional_output/` once the run finishes, which matters
 * because the app is uninstalled straight afterwards and its own directories go with it.
 *
 * The fallback keeps the suite working when it is launched by hand through `adb shell am
 * instrument`, where that argument is absent.
 */
internal actual val testOutputDirectory: String?
    get() {
        val arguments = InstrumentationRegistry.getArguments()
        arguments.getString("additionalTestOutputDir")?.let { return it }
        return InstrumentationRegistry.getInstrumentation()
            .targetContext
            .getExternalFilesDir(null)
            ?.absolutePath
    }
