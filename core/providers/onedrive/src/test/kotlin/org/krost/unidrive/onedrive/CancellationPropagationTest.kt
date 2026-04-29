package org.krost.unidrive.onedrive

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * UD-300: retry loops must not absorb [CancellationException]. Kotlin's
 * structured concurrency relies on cancellation propagating cleanly. If a
 * generic `catch (e: Exception)` absorbs a CancellationException, the next
 * `delay()` inside the retry throws another CancellationException, the same
 * handler absorbs it, and the retry budget gets chewed through while the log
 * fills with "ScopeCoroutine was cancelled" noise.
 *
 * These tests don't instantiate a real [GraphApiService] — they verify the
 * pattern: a suspend function built with the same retry shape as
 * `downloadFile` / `getDelta` / `postWithFlakeRetry` must NOT swallow
 * cancellation under any of the catch branches.
 *
 * If this test ever fails, the first place to look is whether a new catch
 * clause was added in one of those three sites without the
 * `catch (e: CancellationException) { throw e }` carve-out above the generic
 * catch.
 */
class CancellationPropagationTest {
    @Test
    fun `retry loop propagates cancellation instead of absorbing it`() =
        runBlocking {
            // Mimics downloadFile's shape: AuthenticationException/GraphApiException
            // rethrow, CancellationException rethrows (the UD-300 carve-out),
            // everything else retries.
            val reachedCatch = CompletableDeferred<String>()
            val job: Job =
                launch {
                    var attempts = 0
                    try {
                        while (true) {
                            try {
                                attempts++
                                delay(10_000) // will be cancelled
                                fail("delay should be cancelled")
                            } catch (e: CancellationException) {
                                reachedCatch.complete("cancelled-propagated")
                                throw e
                            } catch (e: Exception) {
                                // simulated generic-exception retry path
                                if (attempts > 5) throw e
                                delay(100)
                            }
                        }
                    } catch (e: CancellationException) {
                        // expected on outer propagation
                    }
                }
            delay(50)
            job.cancel()
            job.join()
            val outcome = withTimeoutOrNull(500) { reachedCatch.await() }
            assertTrue(
                outcome == "cancelled-propagated",
                "inner catch should have seen CancellationException and rethrown. outcome=$outcome",
            )
        }

    @Test
    fun `sibling coroutine cancellation propagates to other siblings`() =
        runBlocking {
            // Reproduces the 09:16:51 log pattern: one sibling throws
            // AuthenticationException-like, scope cancels, other siblings get
            // CancellationException. Assert that retry loops in the siblings
            // propagate cancellation promptly (within 1s) rather than spending
            // ~6s (2s + 4s flake backoff) retrying through the cancelled scope.
            val cancelledBy = CompletableDeferred<Throwable>()
            val start = System.currentTimeMillis()
            try {
                coroutineScope {
                    val winners =
                        (1..4).map { i ->
                            async {
                                if (i == 1) {
                                    delay(20)
                                    throw IllegalStateException("sibling-1 failed — cancelling scope")
                                }
                                try {
                                    while (true) {
                                        try {
                                            delay(10_000)
                                        } catch (e: CancellationException) {
                                            if (!cancelledBy.isCompleted) cancelledBy.complete(e)
                                            throw e // UD-300: propagate
                                        } catch (e: Exception) {
                                            delay(2_000) // simulated flake retry
                                        }
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                }
                            }
                        }
                    winners.awaitAll()
                }
                fail("scope should have thrown")
            } catch (_: IllegalStateException) {
                val elapsed = System.currentTimeMillis() - start
                assertTrue(elapsed < 1_500, "cancellation should propagate within 1.5s, took ${elapsed}ms")
                assertTrue(cancelledBy.isCompleted, "at least one sibling must have seen CancellationException")
                val cause = cancelledBy.await()
                assertTrue(cause is CancellationException, "siblings must see CancellationException, got ${cause::class.simpleName}")
            }
        }
}
