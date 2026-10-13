package io.github.phfneves.typst.universe

import io.github.phfneves.typst.PackageSpec
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where [UniversePackageResolver] keeps the archives it downloaded. */
public interface PackageCache {
    /** The archive stored for [spec], or `null` if there is none. */
    public suspend fun get(spec: PackageSpec): ByteArray?

    /** Stores [archive] for [spec], replacing any earlier one. */
    public suspend fun put(spec: PackageSpec, archive: ByteArray)
}

/** Keeps archives in memory, for as long as the cache itself lives. */
public class InMemoryPackageCache : PackageCache {

    private val mutex = Mutex()
    private val archives = mutableMapOf<PackageSpec, ByteArray>()

    override suspend fun get(spec: PackageSpec): ByteArray? = mutex.withLock { archives[spec] }

    override suspend fun put(spec: PackageSpec, archive: ByteArray) {
        mutex.withLock { archives[spec] = archive }
    }
}
