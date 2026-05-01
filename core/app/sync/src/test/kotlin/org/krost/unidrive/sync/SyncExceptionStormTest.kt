package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.ProviderException
import org.krost.unidrive.sync.model.ConflictPolicy
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * UD-249 regression — after UD-248's per-action failure isolation, a sync pass hitting a
 * storm of 500 errors must NOT amplify into tens of thousands of exceptions per second.
 *
 * The original JFR capture (daemon-internxt-final.jfr, 2026-04-19) recorded 13,657
 * exceptions/s during a 7s window — ~96k exceptions. The in-log signal was only 3 WARNs
 * per failing file, so the amplification was invisible to the log stream.
 *
 * Pre-UD-248, Pass 1 threw `ProviderException("Stopping sync after 3 consecutive failures")`
 * once the per-action catch hit 3 failures. The outer watch-loop then caught, backed off,
 * and invoked syncOnce again, producing another cycle of 3 failures, and so on — the
 * retry-and-rescan loop was the amplifier.
 *
 * Post-UD-248, Pass 1 logs+continues on per-action failure, capped by the 2s/4s delay
 * between consecutive failures inside a single syncOnce. There is no internal re-throw
 * path that re-invokes the same action many times per call, so exception count per
 * syncOnce is bounded by (actions × attempts_per_action).
 *
 * This test wires a `FakeCloudProvider` whose `upload` throws on every call and asserts
 * that syncOnce() produces < 30 exceptions for a 3-file workload, well under the 13k/s
 * rate observed in the JFR.
 */
class SyncExceptionStormTest {
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("unidrive-storm-test")
        val dbPath = Files.createTempDirectory("unidrive-storm-db").resolve("state.db")
        db = StateDatabase(dbPath)
        db.initialize()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    @Test
    fun `exception count stays bounded when every upload fails`() =
        runTest {
            val provider = CountingFailProvider()
            provider.deltaItems = emptyList()
            provider.deltaCursor = "cursor-storm"

            val engine =
                SyncEngine(
                    provider = provider,
                    db = db,
                    syncRoot = syncRoot,
                    conflictPolicy = ConflictPolicy.KEEP_BOTH,
                    reporter = ProgressReporter.Silent,
                )

            // Queue 3 local files — reconciler will emit 3 Upload actions (Pass 2).
            Files.writeString(syncRoot.resolve("a.txt"), "payload-a")
            Files.writeString(syncRoot.resolve("b.txt"), "payload-b")
            Files.writeString(syncRoot.resolve("c.txt"), "payload-c")

            // Uploads throw every call. Pre-UD-248 the retry-and-rescan loop could
            // accumulate tens of thousands of throws; post-UD-248 each action is
            // attempted exactly once per syncOnce.
            provider.uploadAlwaysFails = true

            // Must NOT throw — UD-248 swallows per-action failures in Pass 1 & Pass 2.
            engine.syncOnce()

            val throws = provider.uploadThrows.get()

            // Threshold rationale:
            //   3 files × 1 attempt each in Pass 2 = 3 throws baseline.
            //   UD-248 Pass 1's consecutive-failure cap allows up to 3 attempts per
            //     slot × 3 uploads' worth of actions = 9 (conservative upper bound
            //     even though uploads run in Pass 2, not Pass 1).
            //   2× budget for any internal re-throw on cleanup paths = 30.
            //   The pre-UD-248 bug showed ~96,000 throws in 7s → > 10,000 threshold
            //     separation. Even a 10x regression stays under 30 for this workload.
            assertTrue(
                throws in 1..29,
                "Expected upload throws in [1, 30) (UD-248 bound); got $throws. " +
                    "A count above threshold suggests the exception-amplification loop regressed.",
            )

            // All 3 files should have been attempted (not just one).
            assertTrue(
                provider.uploadCalls.get() >= 3,
                "Expected at least 3 upload attempts; got ${provider.uploadCalls.get()}",
            )

            // No file should have succeeded — confirm the provider really did fail.
            assertFalse(
                provider.uploadedPaths.any(),
                "Expected 0 successful uploads; got ${provider.uploadedPaths}",
            )
        }

    /**
     * Provider that counts every `upload` invocation and every throw. Other API surface
     * delegates to the minimum needed by SyncEngine.
     */
    private class CountingFailProvider : org.krost.unidrive.CloudProvider {
        override val id = "fake-counting"
        override val displayName = "FakeCounting"
        override val isAuthenticated = true

        var deltaItems = listOf<CloudItem>()
        var deltaCursor = "cursor-1"
        var uploadAlwaysFails = false

        val uploadCalls = AtomicInteger(0)
        val uploadThrows = AtomicInteger(0)
        val uploadedPaths = mutableListOf<String>()

        override fun capabilities() = setOf(org.krost.unidrive.Capability.Delta)

        override suspend fun authenticate() {}

        override suspend fun logout() {}

        override suspend fun listChildren(path: String) = emptyList<CloudItem>()

        override suspend fun getMetadata(path: String) =
            deltaItems.firstOrNull { it.path == path }
                ?: CloudItem(
                    id = "id-$path",
                    name = path.substringAfterLast("/"),
                    path = path,
                    size = 0,
                    isFolder = false,
                    modified = Instant.now(),
                    created = Instant.now(),
                    hash = null,
                    mimeType = null,
                )

        override suspend fun download(
            remotePath: String,
            destination: Path,
        ): Long {
            Files.createDirectories(destination.parent)
            Files.write(destination, ByteArray(0))
            return 0
        }

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem {
            uploadCalls.incrementAndGet()
            if (uploadAlwaysFails) {
                uploadThrows.incrementAndGet()
                throw ProviderException("500 Internal Server Error")
            }
            uploadedPaths.add(remotePath)
            return CloudItem(
                id = "id-$remotePath",
                name = remotePath.substringAfterLast("/"),
                path = remotePath,
                size = Files.size(localPath),
                isFolder = false,
                modified = Instant.now(),
                created = Instant.now(),
                hash = "uploaded",
                mimeType = null,
            )
        }

        override suspend fun delete(remotePath: String) {}

        override suspend fun createFolder(path: String) =
            CloudItem(
                id = "id-$path",
                name = path.substringAfterLast("/"),
                path = path,
                size = 0,
                isFolder = true,
                modified = Instant.now(),
                created = Instant.now(),
                hash = null,
                mimeType = null,
            )

        override suspend fun move(
            fromPath: String,
            toPath: String,
        ) = createFolder(toPath)

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((itemsSoFar: Int) -> Unit)?,
        ): DeltaPage = DeltaPage(items = deltaItems, cursor = deltaCursor, hasMore = false)

        override suspend fun quota() = org.krost.unidrive.QuotaInfo(total = 1000, used = 0, remaining = 1000)
    }
}
