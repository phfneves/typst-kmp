package io.github.phfneves.typst

import kotlin.experimental.ExperimentalNativeApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

@OptIn(ExperimentalNativeApi::class)
internal actual val platformName: String =
    Platform.osFamily.name.lowercase() + "-" + Platform.cpuArchitecture.name.lowercase()

/** Set by the Kotlin/Native test tasks; see `TYPST_TEST_OUTPUT` in build.gradle.kts. */
@OptIn(ExperimentalForeignApi::class)
internal actual val testOutputDirectory: String? = getenv("TYPST_TEST_OUTPUT")?.toKString()
