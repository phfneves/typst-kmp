package io.github.phfneves.typst

internal actual val platformName: String = "jvm"

/** Set by the `jvmTest` task; see the `typst.test.output` system property in build.gradle.kts. */
internal actual val testOutputDirectory: String? = System.getProperty("typst.test.output")
