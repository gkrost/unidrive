package org.krost.unidrive.internxt

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.io.defaultTokenPath
import org.krost.unidrive.sync.ProgressReporter
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.sync.model.ConflictPolicy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Live smoke set for Internxt that closes the delete, delta-change and sync round-trip gaps of the
 * 5+5+2 target. Runs only against the `internxt_test` profile (override with
 * UNIDRIVE_LIVE_INTERNXT_PROFILE), never the primary account, and every test works inside its own
 * throwaway remote folder that it removes again.
 *
 * Gated: UNIDRIVE_INTEGRATION_TESTS=true plus the profile's credentials.json. Without them each
 * test reports as skipped (not as a silent pass).
 *
 * Not yet hand-validated against a live account; run it once with the test profile before relying on it.
 */
class InternxtSmokeLiveTest {
    private val profile = System.getenv("UNIDRIVE_LIVE_INTERNXT_PROFILE") ?: "internxt_test"
    private val tokenPath = defaultTokenPath(profile)

    private fun assumeLive() {
        assumeTrue(
            "Set UNIDRIVE_INTEGRATION_TESTS=true and provide $profile credentials to run this smoke",
            System.getenv("UNIDRIVE_INTEGRATION_TESTS")?.toBoolean() == true &&
                Files.exists(tokenPath.resolve("credentials.json")),
        )
    }

    private suspend fun withProvider(block: suspend (InternxtProvider, String) -> Unit) {
        val provider = InternxtProvider(InternxtConfig(tokenPath = tokenPath))
        provider.authenticate()
        val folder = "/ud-smoke-${System.currentTimeMillis()}"
        provider.createFolder(folder)
        try {
            block(provider, folder)
        } finally {
            runCatching { provider.delete(folder) }
            provider.close()
        }
    }

    private fun tempFile(content: String): Path = Files.createTempFile("ud-smoke", ".txt").also { Files.writeString(it, content) }

    @Test
    fun `delete removes the remote file`() {
        assumeLive()
        runBlocking {
            withProvider { provider, folder ->
                val path = "$folder/to-delete.txt"
                val src = tempFile("delete me")
                try {
                    provider.upload(src, path, existingRemoteId = null)
                    assertTrue(provider.listChildren(folder).any { it.name == "to-delete.txt" })
                    provider.delete(path)
                    assertFalse(
                        provider.listChildren(folder).any { it.name == "to-delete.txt" },
                        "file must be gone from the folder listing after delete",
                    )
                } finally {
                    Files.deleteIfExists(src)
                }
            }
        }
    }

    @Test
    fun `delta reports a file uploaded after the cursor was taken`() {
        assumeLive()
        runBlocking {
            withProvider { provider, folder ->
                var page = provider.delta(null)
                while (page.hasMore) page = provider.delta(page.cursor)
                val cursor = page.cursor

                val path = "$folder/after-cursor.txt"
                val src = tempFile("new after cursor")
                try {
                    provider.upload(src, path, existingRemoteId = null)
                    var next = provider.delta(cursor)
                    val seen = next.items.toMutableList()
                    while (next.hasMore) {
                        next = provider.delta(next.cursor)
                        seen.addAll(next.items)
                    }
                    assertNotNull(
                        seen.find { it.path.removePrefix("/") == path.removePrefix("/") && !it.deleted },
                        "incremental delta must contain the file uploaded after the cursor",
                    )
                } finally {
                    Files.deleteIfExists(src)
                }
            }
        }
    }

    @Test
    fun `sync uploads a new local file and the bytes round-trip`() {
        assumeLive()
        runBlocking {
            withProvider { provider, folder ->
                withEngine(provider, folder) { engine, syncRoot ->
                    val content = "local create ${System.currentTimeMillis()}"
                    val rel = folder.removePrefix("/") + "/local-create.txt"
                    val local = syncRoot.resolve(rel)
                    Files.createDirectories(local.parent)
                    Files.writeString(local, content)

                    engine.syncOnce()

                    val dest = Files.createTempFile("ud-smoke-dl", ".txt")
                    try {
                        provider.download("$folder/local-create.txt", dest)
                        assertContentEquals(content.toByteArray(), Files.readAllBytes(dest))
                    } finally {
                        Files.deleteIfExists(dest)
                    }
                }
            }
        }
    }

    @Test
    fun `sync downloads a remote file and then applies its remote delete`() {
        assumeLive()
        runBlocking {
            withProvider { provider, folder ->
                withEngine(provider, folder) { engine, syncRoot ->
                    val remote = "$folder/remote-create.txt"
                    val src = tempFile("remote create")
                    try {
                        provider.upload(src, remote, existingRemoteId = null)
                    } finally {
                        Files.deleteIfExists(src)
                    }
                    val local = syncRoot.resolve(remote.removePrefix("/"))

                    engine.syncOnce()
                    assertTrue(Files.exists(local), "remote file must appear locally after sync")
                    assertEquals("remote create", Files.readString(local))

                    provider.delete(remote)
                    engine.syncOnce()
                    assertFalse(Files.exists(local), "remote delete must remove the local file after sync")
                }
            }
        }
    }

    private suspend fun withEngine(
        provider: CloudProvider,
        folder: String,
        block: suspend (SyncEngine, Path) -> Unit,
    ) {
        val syncRoot = Files.createTempDirectory("ud-smoke-root")
        val dbDir = Files.createTempDirectory("ud-smoke-db")
        val db = StateDatabase(dbDir.resolve("state.db"))
        db.initialize()
        try {
            val engine =
                SyncEngine(
                    provider = provider,
                    db = db,
                    syncRoot = syncRoot,
                    conflictPolicy = ConflictPolicy.KEEP_BOTH,
                    reporter = ProgressReporter.Silent,
                    syncPaths = listOf(folder),
                    standingScope = listOf(folder),
                )
            block(engine, syncRoot)
        } finally {
            db.close()
            syncRoot.toFile().deleteRecursively()
            dbDir.toFile().deleteRecursively()
        }
    }
}
