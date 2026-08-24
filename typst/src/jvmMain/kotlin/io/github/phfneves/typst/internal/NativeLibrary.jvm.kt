package io.github.phfneves.typst.internal

import io.github.phfneves.typst.TypstNativeException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

/**
 * Extracts the platform's shared library from the jar and loads it.
 *
 * Resources live at `/io/github/phfneves/typst/native/<os>-<arch>/<library>`, which is the layout
 * the per-platform jars are assembled with. Set `typst.kmp.library.path` to point at a locally
 * built `.so`/`.dylib`/`.dll` instead — that is how the Gradle test tasks run against a fresh
 * cargo build without repackaging anything.
 */
internal actual fun loadTypstNativeLibrary() {
    System.getProperty("typst.kmp.library.path")?.let { override ->
        System.load(java.io.File(override).absolutePath)
        return
    }

    val platform = detectPlatform()
    val resource = "/io/github/phfneves/typst/native/$platform/${libraryFileName()}"
    val stream = NativeEngine::class.java.getResourceAsStream(resource)
        ?: throw TypstNativeException(
            "No native Typst library for $platform on the classpath ($resource). " +
                "Add the typst-kmp-jvm artifact for your platform, or set the " +
                "typst.kmp.library.path system property to a locally built library.",
        )

    val extracted = stream.use { input ->
        val directory = Files.createTempDirectory("typst-kmp")
        directory.toFile().deleteOnExit()
        val file = directory.resolve(libraryFileName())
        Files.copy(input, file, StandardCopyOption.REPLACE_EXISTING)
        file.toFile().apply { deleteOnExit() }
    }

    System.load(extracted.absolutePath)
}

private fun detectPlatform(): String {
    val os = System.getProperty("os.name").lowercase(Locale.ROOT)
    val arch = System.getProperty("os.arch").lowercase(Locale.ROOT)

    val osId = when {
        os.contains("win") -> "windows"
        os.contains("mac") || os.contains("darwin") -> "macos"
        os.contains("nux") || os.contains("nix") -> "linux"
        else -> throw TypstNativeException("Unsupported operating system: $os")
    }
    val archId = when (arch) {
        "x86_64", "amd64" -> "x86_64"
        "aarch64", "arm64" -> "aarch64"
        else -> throw TypstNativeException("Unsupported architecture: $arch")
    }
    return "$osId-$archId"
}

private fun libraryFileName(): String {
    val os = System.getProperty("os.name").lowercase(Locale.ROOT)
    return when {
        os.contains("win") -> "typst_kmp_jni.dll"
        os.contains("mac") || os.contains("darwin") -> "libtypst_kmp_jni.dylib"
        else -> "libtypst_kmp_jni.so"
    }
}
