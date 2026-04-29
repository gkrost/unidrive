package org.krost.unidrive.hidrive

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class HiDriveIntegrationTest {
    companion object {
        private val shouldRun = System.getenv("UNIDRIVE_INTEGRATION_TESTS")?.toBoolean() ?: false

        private fun checkEnabled(): Boolean {
            if (!shouldRun) return false
            val config = HiDriveConfig()
            if (config.clientId.isBlank() || config.clientSecret.isBlank()) return false
            val tokenFile = config.tokenPath.resolve("token.json")
            return Files.exists(tokenFile)
        }
    }

    @Test
    fun `list root directory`() {
        if (!checkEnabled()) return

        val provider = HiDriveProvider()
        runBlocking {
            provider.authenticate()
            val children = provider.listChildren("/")
            assertTrue(children.isNotEmpty(), "Root directory should not be empty")
        }
    }

    @Test
    fun `get quota`() {
        if (!checkEnabled()) return

        val provider = HiDriveProvider()
        runBlocking {
            provider.authenticate()
            val quota = provider.quota()
            assertTrue(quota.total > 0, "Total quota should be > 0")
        }
    }

    @Test
    fun `delta returns items on first call`() {
        if (!checkEnabled()) return

        val provider = HiDriveProvider()
        runBlocking {
            provider.authenticate()
            val page = provider.delta(null)
            assertTrue(page.items.isNotEmpty(), "First delta should return all items")
            assertTrue(page.cursor.isNotBlank(), "Cursor should not be blank")
        }
    }
}
