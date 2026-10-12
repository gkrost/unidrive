package org.krost.unidrive.hydration

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.sync.FakeCloudProvider
import org.krost.unidrive.sync.ProgressReporter
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.sync.model.ConflictPolicy
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #586: the mount records when the provider refused its credentials (an authentication failure, raised after the
 * provider's own token refresh failed), so `daemon.status` can report `credential_health` as `expired` without
 * waiting for its next check. Any other failure is not a rejection.
 */
class MountEngineAuthRejectionTest {
    private lateinit var db: StateDatabase
    private lateinit var provider: FakeCloudProvider
    private lateinit var engine: SyncEngine
    private lateinit var mount: MountEngine

    @BeforeTest
    fun setUp() {
        db = StateDatabase(Files.createTempDirectory("unidrive-auth-db").resolve("state.db"))
        db.initialize()
        provider = FakeCloudProvider()
        engine =
            SyncEngine(
                provider = provider,
                db = db,
                syncRoot = Files.createTempDirectory("unidrive-auth-root"),
                conflictPolicy = ConflictPolicy.KEEP_BOTH,
                reporter = ProgressReporter.Silent,
                cacheRoot = Files.createTempDirectory("unidrive-auth-cache"),
            )
        mount = MountEngine.over(engine)
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun row(hydrated: Boolean) =
        SyncEntry(
            path = "/foo.txt",
            remoteId = "id-/foo.txt",
            remoteHash = "hash-/foo.txt",
            remoteSize = 5L,
            remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
            localMtime = null,
            localSize = null,
            isFolder = false,
            isPinned = false,
            isHydrated = hydrated,
            lastSynced = Instant.now(),
        )

    @Test
    fun `nothing is recorded before any failure`() {
        assertNull(mount.authRejectedAtMs)
    }

    @Test
    fun `a download rejected for its credentials is recorded`() =
        runTest {
            provider.files["/foo.txt"] = "hello".toByteArray()
            db.upsertEntry(row(hydrated = false))
            provider.authFailOnDownload = true

            assertFailsWith<AuthenticationException> { mount.ensureHydrated("/foo.txt") }

            assertNotNull(mount.authRejectedAtMs)
        }

    @Test
    fun `an upload rejected for its credentials is recorded`() =
        runTest {
            val cacheFile = mount.resolveCachePath("/foo.txt")
            Files.createDirectories(cacheFile.parent)
            Files.writeString(cacheFile, "hello")
            db.upsertEntry(row(hydrated = true))
            provider.authFailOnUpload = true

            assertFailsWith<AuthenticationException> { mount.uploadFromCache("/foo.txt", cacheFile) }

            val at = assertNotNull(mount.authRejectedAtMs)
            assertTrue(at > 0)
        }

    @Test
    fun `an upload that fails for another reason is not a rejection`() =
        runTest {
            val cacheFile = mount.resolveCachePath("/foo.txt")
            Files.createDirectories(cacheFile.parent)
            Files.writeString(cacheFile, "hello")
            db.upsertEntry(row(hydrated = true))
            provider.uploadFailCount = 1

            assertFailsWith<org.krost.unidrive.ProviderException> { mount.uploadFromCache("/foo.txt", cacheFile) }

            assertNull(mount.authRejectedAtMs, "a network timeout says nothing about the credentials")
        }
}
