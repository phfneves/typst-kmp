package io.github.phfneves.typst.gradle

/**
 * The Rust artifact kind a given consumer needs.
 *
 * Kotlin/Native links a static archive straight into the klib, while the JVM and Android load a
 * shared library at runtime through JNI.
 */
enum class RustArtifactKind {
    STATIC_LIB,
    DYNAMIC_LIB,
}

/**
 * A Rust compilation target together with everything needed to find and link its output.
 *
 * [konanTarget] is the Kotlin/Native target name (`null` for JVM/Android-only targets), and
 * [jvmPlatformId] is the `<os>-<arch>` directory name used inside the JVM jar resources
 * (`null` for targets that are never loaded from a jar).
 */
data class RustTarget(
    val triple: String,
    val konanTarget: String? = null,
    val jvmPlatformId: String? = null,
    val androidAbi: String? = null,
    val linkerOpts: List<String> = emptyList(),
) {
    /** File name cargo produces for a `staticlib` crate on this target. */
    fun staticLibFileName(crate: String): String {
        val stem = crate.replace('-', '_')
        return if (isMsvc) "$stem.lib" else "lib$stem.a"
    }

    /** File name cargo produces for a `cdylib` crate on this target. */
    fun dynamicLibFileName(crate: String): String {
        val stem = crate.replace('-', '_')
        return when {
            isWindows -> "$stem.dll"
            isApple -> "lib$stem.dylib"
            else -> "lib$stem.so"
        }
    }

    val isWindows: Boolean get() = triple.contains("windows")
    val isMsvc: Boolean get() = triple.endsWith("msvc")
    val isApple: Boolean get() = triple.contains("apple")
    val isAndroid: Boolean get() = triple.contains("android")

    /** Host OS family able to build this target without a cross-compilation toolchain. */
    val requiredHostFamily: HostFamily
        get() = when {
            isApple -> HostFamily.MAC
            isAndroid -> HostFamily.ANY
            isWindows -> HostFamily.WINDOWS
            else -> HostFamily.LINUX
        }
}

enum class HostFamily { MAC, LINUX, WINDOWS, ANY }

/**
 * Every target we ship.
 *
 * Note that `mingwX64` maps to the **GNU** ABI: Kotlin/Native links with MinGW-w64, so an MSVC
 * build would not be linkable. The JVM on Windows, in contrast, uses the MSVC ABI.
 */
object RustTargets {

    // --- Kotlin/Native (static libraries, linked into the klib via cinterop) -------------------

    val iosArm64 = RustTarget("aarch64-apple-ios", konanTarget = "iosArm64")
    val iosSimulatorArm64 = RustTarget("aarch64-apple-ios-sim", konanTarget = "iosSimulatorArm64")
    val iosX64 = RustTarget("x86_64-apple-ios", konanTarget = "iosX64")
    val macosArm64 = RustTarget("aarch64-apple-darwin", konanTarget = "macosArm64", jvmPlatformId = "macos-aarch64")
    val macosX64 = RustTarget("x86_64-apple-darwin", konanTarget = "macosX64", jvmPlatformId = "macos-x86_64")

    val linuxX64 = RustTarget(
        triple = "x86_64-unknown-linux-gnu",
        konanTarget = "linuxX64",
        jvmPlatformId = "linux-x86_64",
        linkerOpts = listOf("-lm", "-ldl", "-lpthread"),
    )
    val linuxArm64 = RustTarget(
        triple = "aarch64-unknown-linux-gnu",
        konanTarget = "linuxArm64",
        jvmPlatformId = "linux-aarch64",
        linkerOpts = listOf("-lm", "-ldl", "-lpthread"),
    )
    val mingwX64 = RustTarget(
        triple = "x86_64-pc-windows-gnu",
        konanTarget = "mingwX64",
        linkerOpts = listOf("-lws2_32", "-luserenv", "-lbcrypt", "-lntdll", "-ladvapi32"),
    )

    // --- JVM-only (dynamic libraries loaded through JNI) --------------------------------------

    val windowsX64Msvc = RustTarget("x86_64-pc-windows-msvc", jvmPlatformId = "windows-x86_64")

    /**
     * The GNU-ABI alternative for the Windows JVM library.
     *
     * The JVM loads either ABI happily, so this is a way to build on a machine that has MinGW-w64
     * — for instance the one Kotlin/Native already ships — but no Visual Studio. Select it with
     * `-Ptypst.windowsAbi=gnu`. Released artifacts use MSVC.
     */
    val windowsX64Gnu = RustTarget("x86_64-pc-windows-gnu", jvmPlatformId = "windows-x86_64")

    // --- Android (dynamic libraries built through cargo-ndk) ----------------------------------

    val androidArm64 = RustTarget("aarch64-linux-android", androidAbi = "arm64-v8a")
    val androidArm32 = RustTarget("armv7-linux-androideabi", androidAbi = "armeabi-v7a")
    val androidX64 = RustTarget("x86_64-linux-android", androidAbi = "x86_64")

    /** Targets consumed by Kotlin/Native through cinterop. */
    val native: List<RustTarget> = listOf(
        iosArm64, iosSimulatorArm64, iosX64,
        macosArm64, macosX64,
        linuxX64, linuxArm64, mingwX64,
    )

    /** Targets whose shared library is packaged into the JVM jars. */
    val jvm: List<RustTarget> = listOf(
        linuxX64, linuxArm64, macosX64, macosArm64, windowsX64Msvc,
    )

    /** Targets whose shared library is packaged into the Android AAR. */
    val android: List<RustTarget> = listOf(androidArm64, androidArm32, androidX64)

    fun byKonanTarget(name: String): RustTarget? = native.firstOrNull { it.konanTarget == name }
}
