package io.github.phfneves.typst.gradle

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.Sync

/**
 * Gathers the per-ABI Android libraries into a single `jniLibs`-shaped directory.
 *
 * This is a [Sync] with an explicit [outputDir] because AGP's variant API wires generated source
 * directories through `addGeneratedSourceDirectory(task) { it.someDirectoryProperty }`, and
 * `Sync.destinationDir` is a plain `File`.
 */
abstract class CollectJniLibsTask : Sync() {

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty
}
