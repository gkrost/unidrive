package org.krost.unidrive.internxt

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InternxtIntegrationTest {
    companion object {
        private val shouldRun = System.getenv("UNIDRIVE_INTEGRATION_TESTS")?.toBoolean() ?: false

        private fun checkEnabled(): Boolean {
            if (!shouldRun) return false
            val config = InternxtConfig()
            val credFile = config.tokenPath.resolve("credentials.json")
            return Files.exists(credFile)
        }
    }

    @Test
    fun `list root directory`() {
        if (!checkEnabled()) return

        val provider = InternxtProvider()
        runBlocking {
            provider.authenticate()
            val children = provider.listChildren("/")
            assertTrue(children.isNotEmpty(), "Root directory should not be empty")
        }
    }

    @Test
    fun `get quota`() {
        if (!checkEnabled()) return

        val provider = InternxtProvider()
        runBlocking {
            provider.authenticate()
            val quota = provider.quota()
            assertTrue(quota.total > 0, "Total quota should be > 0")
        }
    }

    @Test
    fun `delta returns items on first call`() {
        if (!checkEnabled()) return

        val provider = InternxtProvider()
        runBlocking {
            provider.authenticate()
            val page = provider.delta(null)
            assertTrue(page.items.isNotEmpty(), "First delta should return all items")
            assertTrue(page.cursor.isNotBlank(), "Cursor should not be blank")
        }
    }

    @Test
    fun `download a known file and verify content`() {
        if (!checkEnabled()) return
        // Set INTERNXT_TEST_FILE_PATH and INTERNXT_TEST_FILE_SHA256 in env to exercise this test
        val testPath = System.getenv("INTERNXT_TEST_FILE_PATH") ?: return
        val expectedSha = System.getenv("INTERNXT_TEST_FILE_SHA256") ?: return

        val provider = InternxtProvider()
        val dest = createTempFile("internxt-dl-test")
        try {
            runBlocking {
                provider.authenticate()
                val bytes = provider.download(testPath, dest)
                assertTrue(bytes > 0, "Download should return > 0 bytes")
                val actualSha =
                    java.security.MessageDigest
                        .getInstance("SHA-256")
                        .digest(Files.readAllBytes(dest))
                        .joinToString("") { "%02x".format(it) }
                assertEquals(expectedSha, actualSha, "SHA-256 of downloaded file must match")
            }
        } finally {
            Files.deleteIfExists(dest)
        }
    }

    @Test
    fun `upload a small file and download it back`() {
        if (!checkEnabled()) return

        val provider = InternxtProvider()
        val content = "unidrive upload test ${System.currentTimeMillis()}".toByteArray()
        val localFile = createTempFile("internxt-ul-test")
        val dest = createTempFile("internxt-dl-roundtrip")
        try {
            Files.write(localFile, content)
            runBlocking {
                provider.authenticate()
                val uploaded = provider.upload(localFile, "/_unidrive_test_upload.txt", null)
                assertFalse(uploaded.id.isBlank(), "Uploaded file should have an ID")
                val downloaded = provider.download("/_unidrive_test_upload.txt", dest)
                assertEquals(content.size.toLong(), downloaded)
                val roundTrip = Files.readAllBytes(dest)
                assertTrue(content.contentEquals(roundTrip), "Round-trip content must match")
                // Clean up remote
                provider.delete("/_unidrive_test_upload.txt")
            }
        } finally {
            Files.deleteIfExists(localFile)
            Files.deleteIfExists(dest)
        }
    }
}
