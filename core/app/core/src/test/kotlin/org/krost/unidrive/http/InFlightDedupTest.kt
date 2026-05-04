package org.krost.unidrive.http

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * UD-205: pin the dedup primitive's contract.
 *
 * Four invariants, one named test each (per CLAUDE.md "orthogonal invariant
 * decomposition"):
 *  1. Concurrent callers share one loader invocation.
 *  2. Loader failure propagates to all awaiting callers AND clears the map.
 *  3. Distinct keys do not interfere.
 *  4. Map is cleared after success — a second call re-invokes the loader.
 */
class InFlightDedupTest {
    @Test
    fun `concurrent callers for same key share exactly one loader invocation`() =
        runBlocking {
            val dedup = InFlightDedup<String, Int>()
            val loaderInvocations = AtomicInteger(0)
            // Gate so all 100 callers reach the dedup before any loader can complete;
            // ensures we exercise the deduplication race rather than a serial sequence.
            val gate = CompletableDeferred<Unit>()

            val results =
                coroutineScope {
                    val deferreds =
                        (1..100).map {
                            async(Dispatchers.Default) {
                                dedup.load("k") {
                                    loaderInvocations.incrementAndGet()
                                    gate.await()
                                    42
                                }
                            }
                        }
                    // Yield onto a separate dispatcher so the 100 launched coroutines
                    // get a chance to install themselves on the dedup map before we
                    // release the gate.
                    withContext(Dispatchers.IO) {
                        // Small spin until at least one caller has reached the dedup.
                        repeat(50) {
                            if (dedup.inFlightCount() >= 1 && loaderInvocations.get() >= 1) {
                                return@repeat
                            }
                            delay(10)
                        }
                    }
                    gate.complete(Unit)
                    deferreds.awaitAll()
                }

            assertEquals(100, results.size)
            assertTrue(results.all { it == 42 }, "every caller should receive the same value")
            assertEquals(
                1,
                loaderInvocations.get(),
                "exactly one loader invocation should be observed across 100 concurrent callers",
            )
            assertEquals(0, dedup.inFlightCount(), "map should be cleared after completion")
        }

    @Test
    fun `loader failure is rethrown to all callers and the map is cleared for the next call`() =
        runBlocking {
            val dedup = InFlightDedup<String, Int>()
            val loaderInvocations = AtomicInteger(0)
            val gate = CompletableDeferred<Unit>()

            val outcomes =
                coroutineScope {
                    val deferreds =
                        (1..10).map {
                            async(Dispatchers.Default) {
                                runCatching {
                                    dedup.load("k") {
                                        loaderInvocations.incrementAndGet()
                                        gate.await()
                                        throw RuntimeException("boom")
                                    }
                                }
                            }
                        }
                    withContext(Dispatchers.IO) {
                        repeat(50) {
                            if (loaderInvocations.get() >= 1) return@repeat
                            delay(10)
                        }
                    }
                    gate.complete(Unit)
                    deferreds.awaitAll()
                }

            outcomes.forEachIndexed { i, outcome ->
                val ex = outcome.exceptionOrNull()
                assertTrue(ex != null, "caller $i should have seen an exception")
                assertEquals("boom", ex.message, "caller $i should see the loader's exception")
            }
            assertEquals(1, loaderInvocations.get(), "exactly one failing loader run")
            assertEquals(0, dedup.inFlightCount(), "map should be cleared after failure (no negative caching)")

            // The next call must re-run the loader: failure does not poison the entry.
            val recovered =
                dedup.load("k") {
                    loaderInvocations.incrementAndGet()
                    7
                }
            assertEquals(7, recovered)
            assertEquals(2, loaderInvocations.get(), "second load should invoke a fresh loader")
        }

    @Test
    fun `distinct keys do not interfere with each other`() =
        runTest {
            val dedup = InFlightDedup<String, String>()
            val a =
                dedup.load("a") {
                    delay(5)
                    "alpha"
                }
            val b =
                dedup.load("b") {
                    delay(5)
                    "beta"
                }
            assertEquals("alpha", a)
            assertEquals("beta", b)
            assertEquals(0, dedup.inFlightCount())
        }

    @Test
    fun `successful completion clears the map - second call re-invokes the loader`() =
        runTest {
            val dedup = InFlightDedup<String, Int>()
            val loaderInvocations = AtomicInteger(0)

            val first =
                dedup.load("k") {
                    loaderInvocations.incrementAndGet()
                    1
                }
            val second =
                dedup.load("k") {
                    loaderInvocations.incrementAndGet()
                    2
                }
            assertEquals(1, first, "first call returns first loader's result")
            assertEquals(2, second, "second sequential call re-invokes loader, returns its result")
            assertEquals(2, loaderInvocations.get(), "dedup is in-flight only, not a TTL cache")
            assertEquals(0, dedup.inFlightCount())
        }

    @Test
    fun `single caller completes normally without a stuck deferred`() =
        runTest {
            val dedup = InFlightDedup<String, Int>()
            val v = dedup.load("solo") { 99 }
            assertEquals(99, v)
            assertEquals(0, dedup.inFlightCount())
        }
}
