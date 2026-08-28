package io.github.phfneves.typst.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/**
 * Runs `wasm-bindgen` over the `.wasm` a [CargoBuildTask] produced, yielding the JS glue and the
 * processed module a browser actually loads.
 *
 * A task of its own rather than another [CargoBuildTask] mode, because the output is a
 * *directory*: wasm-bindgen emits `<name>.js` alongside `<name>_bg.wasm`, and `CargoBuildTask`
 * copies exactly one artifact.
 *
 * `--target no-modules` is deliberate. It emits a classic script that installs a global
 * `wasm_bindgen` factory, which `importScripts()` can load from inside a worker — so the module
 * never enters the consumer's bundler graph, and nothing here depends on webpack, Vite, or ES
 * module support in workers.
 *
 * Configuration-cache safe: no `Project` state at execution time, only injected [ExecOperations]
 * and [FileSystemOperations].
 */
@CacheableTask
abstract class WasmBindgenTask : DefaultTask() {

    /** Directory holding the `.wasm` cargo produced. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val wasmDir: DirectoryProperty

    /** File name of the module inside [wasmDir], e.g. `typst_kmp_wasm.wasm`. */
    @get:Input
    abstract val wasmFileName: Property<String>

    /** Base name for the generated pair, e.g. `typst_kmp_wasm`. */
    @get:Input
    abstract val outputName: Property<String>

    /**
     * The wasm-bindgen version the crate was compiled against.
     *
     * The CLI refuses to process a module produced by a different version, and its own message
     * names neither where it got its number nor where the module got its. Checking here turns
     * that into an install hint naming the exact version to fetch.
     */
    @get:Input
    abstract val expectedVersion: Property<String>

    /**
     * Path to `wasm-bindgen`.
     *
     * Not called `executable`: inside an `exec { }` block that name resolves to `ExecSpec`'s own
     * property instead of this one, and the failure is an unrelated-looking error about a missing
     * `index` argument. The same hazard is documented in [CargoExtension.build].
     */
    @get:Input
    abstract val wasmBindgenExecutable: Property<String>

    /**
     * Emit an empty directory instead of running anything.
     *
     * Driven by `-Ptypst.skipCargo=true`, matching [CargoBuildTask]: the Kotlin sources still
     * type-check and still assemble, they just cannot start an engine.
     */
    @get:Input
    abstract val skipCargo: Property<Boolean>

    @get:Internal
    abstract val prebuiltDir: DirectoryProperty

    /** True when [prebuiltDir] points at a staged wasm-bindgen output for this target. */
    @get:Input
    abstract val usePrebuilt: Property<Boolean>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    protected abstract val execOps: ExecOperations

    @get:Inject
    protected abstract val fsOps: FileSystemOperations

    @TaskAction
    fun run() {
        if (usePrebuilt.get()) {
            copyPrebuilt()
            return
        }

        if (skipCargo.get()) {
            logger.warn(
                "typst.skipCargo is set: not running wasm-bindgen. " +
                    "The resulting web artifacts will not contain a WebAssembly module.",
            )
            outputDir.get().asFile.mkdirs()
            return
        }

        val module = wasmDir.get().asFile.resolve(wasmFileName.get())
        if (!module.isFile) {
            throw GradleException("cargo did not produce $module")
        }

        verifyVersion()

        val destination = outputDir.get().asFile
        fsOps.delete { delete(destination) }
        destination.mkdirs()
        execute(
            listOf(
                "--target", "no-modules",
                "--no-typescript",
                "--out-dir", destination.absolutePath,
                "--out-name", outputName.get(),
                module.absolutePath,
            ),
        )
    }

    /**
     * Copies a wasm-bindgen output staged by the publish workflow.
     *
     * Unlike every single-artifact platform, here the whole directory is the artifact, so this
     * mirrors `CargoBuildTask.copyPrebuilt` but copies recursively.
     */
    private fun copyPrebuilt() {
        val source = prebuiltDir.get().asFile
        if (!source.isDirectory) {
            throw GradleException(
                "typst.prebuiltDir is set but $source is missing. " +
                    "Expected the wasm-bindgen output directory staged there.",
            )
        }
        val destination = outputDir.get().asFile
        fsOps.delete { delete(destination) }
        fsOps.copy {
            from(source)
            into(destination)
        }
        logger.lifecycle("Using the prebuilt wasm-bindgen output from $source")
    }

    private fun verifyVersion() {
        val command = wasmBindgenExecutable.get()
        val output = ByteArrayOutputStream()
        val result = runCatching {
            execOps.exec {
                commandLine(listOf(command, "--version"))
                standardOutput = output
                errorOutput = ByteArrayOutputStream()
                isIgnoreExitValue = true
            }
        }.getOrNull()

        if (result == null || result.exitValue != 0) {
            throw GradleException(installHint("Could not run '$command'."))
        }

        // "wasm-bindgen 0.2.106"
        val found = output.toString(Charsets.UTF_8.name()).trim().substringAfterLast(' ')
        val expected = expectedVersion.get()
        if (found != expected) {
            throw GradleException(
                installHint(
                    "wasm-bindgen $found is installed, but the crate was built against " +
                        "$expected. The two must match exactly.",
                ),
            )
        }
    }

    private fun installHint(problem: String): String =
        "$problem Install it with " +
            "'cargo install wasm-bindgen-cli --locked --version ${expectedVersion.get()}', " +
            "or point at it with -Ptypst.wasmBindgen=<path>. To assemble the Kotlin sources " +
            "without a WebAssembly module, build with -Ptypst.skipCargo=true."

    private fun execute(arguments: List<String>) {
        val command = wasmBindgenExecutable.get()
        val stderr = ByteArrayOutputStream()
        val result = execOps.exec {
            commandLine(listOf(command) + arguments)
            errorOutput = stderr
            isIgnoreExitValue = true
        }
        System.err.write(stderr.toByteArray())
        System.err.flush()
        if (result.exitValue != 0) {
            throw GradleException(
                "$command ${arguments.joinToString(" ")} failed with exit code ${result.exitValue}.",
            )
        }
    }
}
