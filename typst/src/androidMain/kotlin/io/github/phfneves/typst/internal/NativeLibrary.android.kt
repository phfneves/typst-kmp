package io.github.phfneves.typst.internal

/**
 * On Android the `.so` is unpacked from the APK by the platform, so there is nothing to extract.
 *
 * The library itself ships in the separate `typst-kmp-android-native` AAR: the AGP Kotlin
 * Multiplatform library plugin does not support `jniLibs`, so a plain `com.android.library`
 * module carries them instead.
 */
internal actual fun loadTypstNativeLibrary() {
    System.loadLibrary("typst_kmp_jni")
}
