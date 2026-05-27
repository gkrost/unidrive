package org.krost.unidrive.hydration

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HydrationOpenSetConcurrencyTest {

    // dehydrate scans a connection's inner open-set map with `containsValue(path)`
    // while that same connection's IpcServer handlers — each dispatched on
    // Dispatchers.IO — concurrently put/remove handles on the inner map. If the
    // inner map is a plain HashMap, concurrent puts plus a concurrent containsValue
    // scan corrupt the table: a put-driven rehash can throw inside the scan, or make
    // the live victim entry transiently unreachable so the scan misses it. A miss
    // makes dehydrate return Ok and delete the cache of a path open for write — the
    // data-loss bug. A ConcurrentHashMap inner map is safe under exactly this
    // access pattern: the scan never throws and never misses a present entry.
    //
    // Mutations are driven directly on the real `openSets` field (grabbed by
    // reflection) so the loop carries no per-iteration file IO and runs hot enough
    // for puts and scans to truly interleave. dehydrate remains the real method on
    // the real field, so the inner-map type exercised is the one HydrationImpl ships.
    @Test
    fun `dehydrate of an open path stays Busy under concurrent open-set mutation`() =
        runBlocking(Dispatchers.IO) {
            val env = HydrationTestEnv()
            val impl = env.hydration as HydrationImpl

            env.stateDb.insertHydratedEntry("/victim.txt", localSize = 5)
            env.syncEngine.seedCacheContent("/victim.txt", "hello")
            // Register the victim through the public API so the inner map is the one
            // production creates; mutate that same instance directly below.
            impl.openWriteBegin("conn1", "/victim.txt", handleId = "h-victim")

            @Suppress("UNCHECKED_CAST")
            val openSets = HydrationImpl::class.java
                .getDeclaredField("openSets")
                .apply { isAccessible = true }
                .get(impl) as ConcurrentMap<String, MutableMap<String, String>>

            val inner = openSets.getValue("conn1")

            val writers = 4
            val opsPerWriter = 300_000
            val window = 4_000 // keep the live set bounded so the test terminates
            val caught = AtomicReference<Throwable?>(null)

            coroutineScope {
                // Several writers put/remove handles on the SINGLE shared inner map
                // from distinct IO threads — exactly what concurrent connection
                // handlers do. Each keeps a bounded rotating window of live keys and
                // periodically bulk-removes its window, so there is a continuous mix
                // of put, remove, and iterator-based removeIf in flight while the
                // dehydrator scans. On a plain HashMap that corrupts the table
                // (ConcurrentModificationException / torn read missing the victim).
                // The victim handle is never touched, so it must stay present.
                val writerJobs = (0 until writers).map { w ->
                    launch {
                        try {
                            for (i in 0 until opsPerWriter) {
                                if (caught.get() != null) return@launch
                                inner["w$w-h$i"] = "/churn.txt"
                                if (i >= window) inner.remove("w$w-h${i - window}")
                                if (i % 20_000 == 0) inner.keys.removeIf { it.startsWith("w$w-") }
                            }
                        } catch (t: Throwable) {
                            caught.compareAndSet(null, t)
                        }
                    }
                }

                // Dehydrator: the real dehydrate, scanning the shared inner map,
                // running until the writers finish or one corrupts the map.
                val dehydrator = launch {
                    try {
                        while (writerJobs.any { it.isActive } && caught.get() == null) {
                            val r = env.hydration.dehydrate("/victim.txt")
                            // h-victim is live the whole time, so Busy is the only
                            // correct answer. A torn read that misses it returns Ok
                            // and deletes the victim's cache file.
                            assertTrue(
                                r is DehydrateResult.Busy,
                                "dehydrate of an open path must stay Busy, got $r",
                            )
                        }
                    } catch (t: Throwable) {
                        caught.compareAndSet(null, t)
                    }
                }

                writerJobs.forEach { it.join() }
                dehydrator.join()
            }

            assertNull(caught.get(), "no exception or torn-miss expected, got: ${caught.get()}")
            assertTrue(
                java.nio.file.Files.exists(env.syncEngine.resolveCachePath("/victim.txt")),
                "cache file of a path open for write must never be dehydrated away",
            )
        }
}
