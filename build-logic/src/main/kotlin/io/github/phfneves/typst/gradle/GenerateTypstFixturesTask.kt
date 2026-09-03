package io.github.phfneves.typst.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Turns the `.typ` test fixtures into a Kotlin source file of string constants.
 *
 * The fixtures stay real files in the repository — editable, diffable, openable in a Typst editor
 * — while the tests that consume them remain pure `commonTest` code with no file access. That
 * matters because the suite has to run identically on the JVM, on Kotlin/Native and inside an
 * Android instrumentation runner, where "read a file from the project directory" means three
 * different things or nothing at all.
 */
@CacheableTask
abstract class GenerateTypstFixturesTask : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val fixtureDir: DirectoryProperty

    @get:Input
    abstract val packageName: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val fixtures = fixtureDir.get().asFile
            .listFiles { file -> file.isFile && file.extension == "typ" }
            ?.sortedBy { it.name }
            .orEmpty()

        require(fixtures.isNotEmpty()) { "No .typ fixtures found in ${fixtureDir.get().asFile}" }

        val target = outputDir.get().asFile
        target.deleteRecursively()
        val packageDir = target.resolve(packageName.get().replace('.', '/'))
        packageDir.mkdirs()

        val content = buildString {
            appendLine("// Generated from src/commonTest/typst by generateTypstFixtures. Do not edit.")
            appendLine("@file:Suppress(\"ktlint\", \"MaxLineLength\")")
            appendLine()
            appendLine("package ${packageName.get()}")
            appendLine()
            appendLine("/** The `.typ` documents the end-to-end tests compile, as VFS path to source. */")
            appendLine("internal object TypstFixtures {")
            appendLine()
            fixtures.forEach { file ->
                appendLine("    const val ${file.constantName()}: String =")
                appendLine("        \"${file.readText().normalise().escape()}\"")
                appendLine()
            }
            appendLine("    /** Every fixture, keyed by the VFS path the tests mount it at. */")
            appendLine("    val files: Map<String, String> = mapOf(")
            fixtures.forEach { file ->
                appendLine("        \"/${file.name}\" to ${file.constantName()},")
            }
            appendLine("    )")
            appendLine("}")
        }

        packageDir.resolve("TypstFixtures.kt").writeText(content)
        logger.lifecycle("Generated ${fixtures.size} Typst fixture(s) into ${packageDir}")
    }

    private fun java.io.File.constantName(): String =
        nameWithoutExtension.uppercase().replace(Regex("[^A-Z0-9]"), "_")

    /** Normalises line endings so a Windows checkout produces the same constant as a Unix one. */
    private fun String.normalise(): String = replace("\r\n", "\n").replace("\r", "\n")

    /**
     * Escapes for a Kotlin double-quoted literal.
     *
     * `$` needs escaping because Typst uses it for maths, and Kotlin would otherwise read it as
     * a template expression.
     */
    private fun String.escape(): String = buildString(length + 32) {
        this@escape.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '$' -> append("\\$")
                '\n' -> append("\\n")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
    }
}
