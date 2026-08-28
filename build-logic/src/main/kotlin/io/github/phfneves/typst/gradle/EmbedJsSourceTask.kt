package io.github.phfneves.typst.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Turns a JavaScript file into a Kotlin source file holding it as a string constant.
 *
 * The same trade as [GenerateTypstFixturesTask]: the script stays a real `.js` file that an editor
 * can lint and a reviewer can read, while the Kotlin consuming it needs no resource lookup at all.
 *
 * For the worker script that buys more than convenience. A string can be wrapped in a `Blob` and
 * started as a worker from any page, which keeps it out of the consumer's bundler graph and makes
 * it same-origin by construction — the latter being what lets the WebAssembly module itself be
 * served from a different origin.
 */
@CacheableTask
abstract class EmbedJsSourceTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val source: RegularFileProperty

    @get:Input
    abstract val packageName: Property<String>

    /** Name of both the generated file and the object inside it. */
    @get:Input
    abstract val objectName: Property<String>

    /** Name of the constant holding the script. */
    @get:Input
    abstract val constantName: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val script = source.get().asFile
        val target = outputDir.get().asFile
        target.deleteRecursively()
        val packageDir = target.resolve(packageName.get().replace('.', '/'))
        packageDir.mkdirs()

        val content = buildString {
            appendLine("// Generated from ${script.name} by $name. Do not edit.")
            appendLine("@file:Suppress(\"ktlint\", \"MaxLineLength\")")
            appendLine()
            appendLine("package ${packageName.get()}")
            appendLine()
            appendLine("internal object ${objectName.get()} {")
            appendLine("    const val ${constantName.get()}: String =")
            appendLine("        \"${script.readText().normalise().escape()}\"")
            appendLine("}")
        }

        packageDir.resolve("${objectName.get()}.kt").writeText(content)
        logger.lifecycle("Embedded ${script.name} into $packageDir")
    }

    /** Normalises line endings so a Windows checkout produces the same constant as a Unix one. */
    private fun String.normalise(): String = replace("\r\n", "\n").replace("\r", "\n")

    /** Escapes for a Kotlin double-quoted literal; `$` starts a template in Kotlin. */
    private fun String.escape(): String = buildString(length + 64) {
        this@escape.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '$' -> append("\\$")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
    }
}
