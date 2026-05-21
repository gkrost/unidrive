package org.krost.unidrive.onedrive

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CloudItem
import java.nio.file.Files
import kotlin.test.*
import kotlin.time.Duration.Companion.minutes

class OneDriveDeltaIntegrationTest {
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

    @BeforeTest
    fun setUp() =
        runTest {
            if (!checkEnabled()) return@runTest
            provider = OneDriveProvider()
            provider.authenticate()
            ensureTestFolder()
        }

    private suspend fun ensureTestFolder() {
        try {
            provider.getMetadata(TEST_FOLDER)
        } catch (e: Exception) {
            provider.createFolder(TEST_FOLDER)
        }
    }

    /** Follow all pages to get a deltaLink cursor (not a nextLink). */
    private suspend fun fullDelta(cursor: String? = null): Pair<List<CloudItem>, String> {
        val allItems = mutableListOf<CloudItem>()
        var page = provider.delta(cursor)
        allItems.addAll(page.items)
        while (page.hasMore) {
            page = provider.delta(page.cursor)
            allItems.addAll(page.items)
        }
        return allItems to page.cursor
    }

    @Test
    fun `delta detects new file`() =
        runTest(timeout = 2.minutes) {
            if (!checkEnabled()) return@runTest
            val remotePath = "$TEST_FOLDER/delta_new_${System.currentTimeMillis()}.txt"

            val (_, cursor) = fullDelta()

            val tempFile = Files.createTempFile("unidrive-delta", ".txt")
            Files.writeString(tempFile, "Delta test content")
            provider.upload(tempFile, remotePath)
            Files.delete(tempFile)

            delay(3000)
            val (items, _) = fullDelta(cursor)
            val newItem = items.find { it.path == remotePath }
            assertNotNull(newItem, "Delta should contain new file at $remotePath")
            assertFalse(newItem.deleted)

            provider.delete(remotePath)
        }

    @Test
    fun `delta detects deleted file`() =
        runTest(timeout = 2.minutes) {
            if (!checkEnabled()) return@runTest
            val remotePath = "$TEST_FOLDER/delta_del_${System.currentTimeMillis()}.txt"

            val tempFile = Files.createTempFile("unidrive-delta-del", ".txt")
            Files.writeString(tempFile, "To be deleted")
            val uploaded = provider.upload(tempFile, remotePath)
            Files.delete(tempFile)

            val (_, cursor) = fullDelta()

            provider.delete(remotePath)

            delay(3000)
            val (items, _) = fullDelta(cursor)
            // Deleted items may lack name/path; match by id
            val deletedItem = items.find { it.deleted && it.id == uploaded.id }
            assertNotNull(deletedItem, "Delta should contain deleted item with id=${uploaded.id}")
            assertTrue(deletedItem.deleted)
        }

    @Test
    fun `delta detects modified file`() =
        runTest(timeout = 2.minutes) {
            if (!checkEnabled()) return@runTest
            val remotePath = "$TEST_FOLDER/delta_mod_${System.currentTimeMillis()}.txt"

            val tempFile = Files.createTempFile("unidrive-delta-mod", ".txt")
            Files.writeString(tempFile, "Initial content")
            provider.upload(tempFile, remotePath)
            Files.delete(tempFile)

            val (_, cursor) = fullDelta()

            val tempFile2 = Files.createTempFile("unidrive-delta-mod2", ".txt")
            Files.writeString(tempFile2, "Updated content here")
            provider.upload(tempFile2, remotePath)
            Files.delete(tempFile2)

            delay(3000)
            val (items, _) = fullDelta(cursor)
            val modifiedItem = items.find { it.path == remotePath }
            assertNotNull(modifiedItem, "Delta should contain modified file")
            assertFalse(modifiedItem.deleted)

            provider.delete(remotePath)
        }
}
