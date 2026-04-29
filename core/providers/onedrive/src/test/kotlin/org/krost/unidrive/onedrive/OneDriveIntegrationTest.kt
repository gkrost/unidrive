package org.krost.unidrive.onedrive

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.sync.ProgressReporter
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.sync.model.ConflictPolicy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class OneDriveIntegrationTest {
    companion object {
        private const val TEST_FOLDER = "/unidrive_ci_test"
        private val shouldRun = System.getenv("UNIDRIVE_INTEGRATION_TESTS")?.toBoolean() ?: false

        private fun checkEnabled(): Boolean {
            if (!shouldRun) return false
            val config = OneDriveConfig()
            val tokenFile = config.tokenPath.resolve("token.json")
            return Files.exists(tokenFile)
        }
    }

    private lateinit var provider: OneDriveProvider
    private lateinit var syncRoot: Path
    private lateinit var db: StateDatabase
    private lateinit var engine: SyncEngine
    private lateinit var dbPath: Path

    @BeforeTest
    fun setUp() =
        runTest {
            if (!checkEnabled()) return@runTest
            provider = OneDriveProvider()
            provider.authenticate()
            ensureTestFolder()
            syncRoot = Files.createTempDirectory("unidrive-integration")
            dbPath = Files.createTempDirectory("unidrive-integration-db").resolve("state.db")
            db = StateDatabase(dbPath)
            db.initialize()
            engine =
                SyncEngine(
                    provider = provider,
                    db = db,
                    syncRoot = syncRoot,
                    conflictPolicy = ConflictPolicy.KEEP_BOTH,
                    reporter = ProgressReporter.Silent,
                )
        }

    @AfterTest
    fun tearDown() {
        if (!checkEnabled()) return
        db.close()
        syncRoot.toFile().deleteRecursively()
        dbPath.parent.toFile().deleteRecursively()
    }

    private suspend fun ensureTestFolder() {
        try {
            provider.getMetadata(TEST_FOLDER)
        } catch (e: Exception) {
            provider.createFolder(TEST_FOLDER)
        }
    }

    @Test
    fun `create remote file syncs locally as placeholder`() =
        runTest {
            if (!checkEnabled()) return@runTest
            val remotePath = "$TEST_FOLDER/sync_create_${System.currentTimeMillis()}.txt"
            val content = "Hello integration test"

            val tempFile = Files.createTempFile("unidrive-upload", ".txt")
            Files.writeString(tempFile, content)
            provider.upload(tempFile, remotePath)
            Files.delete(tempFile)

            engine.syncOnce()

            val localFile = syncRoot.resolve(remotePath.removePrefix("/"))
            assertTrue(Files.exists(localFile), "Local file should exist after sync")

            provider.delete(remotePath)
        }

    @Test
    fun `delete remote file deletes locally after sync`() =
        runTest {
            if (!checkEnabled()) return@runTest
            val remotePath = "$TEST_FOLDER/sync_delete_${System.currentTimeMillis()}.txt"

            val tempFile = Files.createTempFile("unidrive-delete", ".txt")
            Files.writeString(tempFile, "To be deleted")
            provider.upload(tempFile, remotePath)
            Files.delete(tempFile)

            engine.syncOnce()
            val localFile = syncRoot.resolve(remotePath.removePrefix("/"))
            assertTrue(Files.exists(localFile))

            provider.delete(remotePath)

            engine.syncOnce()
            assertFalse(Files.exists(localFile), "Local file should be deleted after remote deletion")
        }
}
