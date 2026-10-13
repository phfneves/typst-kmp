package io.github.phfneves.typst.internal

import io.github.phfneves.typst.TypstNativeException
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs the blocking native call [block], calling [interrupt] if the coroutine is cancelled while
 * it runs.
 *
 * [block] occupies the calling thread, so it cannot notice the cancellation itself. A watcher
 * coroutine waits for it instead, on another thread of the same dispatcher, and passes it on to
 * the native side, which then stops the compilation and returns an error. That error is turned
 * back into the [kotlinx.coroutines.CancellationException] the caller expects.
 *
 * [interrupt] is never called once this has returned, so whatever it touches — a native token —
 * may be released straight afterwards.
 */
@OptIn(ExperimentalAtomicApi::class)
internal suspend fun <T> interruptible(interrupt: () -> Unit, block: () -> T): T = coroutineScope {
    val finished = AtomicBoolean(false)
    // Undispatched, so it is waiting before block starts: a watcher that had not started yet
    // when the cancellation came would never run its finally block.
    val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            awaitCancellation()
        } finally {
            if (!finished.load()) interrupt()
        }
    }
    try {
        block()
    } catch (error: TypstNativeException) {
        ensureActive()
        throw error
    } finally {
        finished.store(true)
        withContext(NonCancellable) { watcher.cancelAndJoin() }
    }
}
