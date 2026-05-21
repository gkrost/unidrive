package org.krost.unidrive.http

import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * Two-lane request priority for [HttpRetryBudget]. Installed by the engine via
 * `withContext(Priority.Foreground) { ... }` / `withContext(Priority.Background) { ... }`
 * and read inside the provider's HTTP wrapper through [currentPriority].
 *
 * Default is [Background] — every coroutine that does not opt in is treated as
 * "the engine is making progress on its own timetable." [Foreground] means "a
 * transfer is happening for this exact item right now, the user is waiting for
 * bytes."
 *
 * The element propagates through `async`/`launch` because structured
 * concurrency inherits parent context. It does NOT cross `runBlocking { ... }`
 * boundaries — the only existing site in scope is
 * `InternxtProvider.maxFileSizeBytes()`, which is one-shot at startup and fine
 * to leave as Background.
 *
 * Two static singletons ([Foreground], [Background]) plus a [Promotable] variant
 * used by [InFlightDedup] to share a mutable priority reference between the
 * winner and any later joiners: when a [Foreground] caller arrives at a
 * [Background] in-flight key, the dedup flips the shared reference to
 * Foreground and the winner's spin-on-foreground loop in [HttpRetryBudget.awaitSlot]
 * sees the change on its next iteration.
 */
sealed class Priority : AbstractCoroutineContextElement(Key) {
    /** Resolve to the effective lane. [Foreground] / [Background] return themselves;
     *  [Promotable] dereferences its mutable holder. */
    abstract fun resolve(): Priority

    object Foreground : Priority() {
        override fun resolve(): Priority = this
    }

    object Background : Priority() {
        override fun resolve(): Priority = this
    }

    /** Priority backed by a shared [AtomicReference] so concurrent code can
     *  promote it mid-flight. Only [InFlightDedup] is expected to construct
     *  these; install via `withContext(Promotable(ref)) { ... }` inside the
     *  dedup's loader so [HttpRetryBudget.awaitSlot] reads the latest value
     *  each iteration of its yield loop. */
    class Promotable(val ref: AtomicReference<Priority>) : Priority() {
        override fun resolve(): Priority = ref.get().resolve()
    }

    companion object Key : CoroutineContext.Key<Priority>
}

/**
 * Read the current effective request priority from the surrounding coroutine
 * context. Returns [Priority.Background] when no element has been installed,
 * which is the conservative default the throttle coordinator interprets as
 * "yield to user-driven traffic if any is present." For a
 * [Priority.Promotable] element the resolved underlying lane is returned —
 * callers in a loop see the latest value on each call.
 */
suspend fun currentPriority(): Priority =
    (coroutineContext[Priority] ?: Priority.Background).resolve()
