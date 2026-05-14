package org.krost.unidrive

import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for default method implementations in [CloudProvider].
 *
 * After UD-301 / ADR-0005, every optional provider method returns a
 * [CapabilityResult] so callers can distinguish "not supported" from a real
 * success with an empty value. This test uses a minimal stub that implements
 * only the abstract members AND declares no optional capabilities; that stub
 * must therefore return [CapabilityResult.Unsupported] from each default
 * implementation.
 *
 * These six "default" tests used to cement silent-defaults (null, emptyList,
 * false, true) and were flagged by UD-704 for rewrite alongside UD-301.
 */
class CloudProviderDefaultsTest {
    private val stubItem =
        CloudItem(
            id = "1",
            name = "file.txt",
            path = "/file.txt",
            size = 42,
            isFolder = false,
            modified = Instant.now(),
            created = Instant.now(),
            hash = "abc",
            mimeType = "text/plain",
        )

    private val stubProvider =
        object : CloudProvider {
            override val id = "stub"
            override val displayName = "Stub Provider"
            override val isAuthenticated = false

            // The stub intentionally declares NO capabilities — every optional
            // method should therefore hit the interface default and return
            // Unsupported.
            override fun capabilities(): Set<Capability> = emptySet()

            override suspend fun authenticate() {}

            override suspend fun logout() {}

            override suspend fun listChildren(path: String) = emptyList<CloudItem>()

            override suspend fun getMetadata(path: String) = stubItem

            override suspend fun download(
                remotePath: String,
                destination: Path,
            ): Long = 42L

            override suspend fun upload(
                localPath: Path,
                remotePath: String,
                existingRemoteId: String?,
                onProgress: ((Long, Long) -> Unit)?,
            ): CloudItem = stubItem

            override suspend fun delete(remotePath: String) {}

            override suspend fun createFolder(path: String) = stubItem

            override suspend fun move(
                fromPath: String,
                toPath: String,
            ) = stubItem

            override suspend fun delta(
                cursor: String?,
                onPageProgress: ((itemsSoFar: Int) -> Unit)?,
            ) = DeltaPage(emptyList(), "cursor1", false)

            override suspend fun quota() = QuotaInfo(100, 50, 50)
        }

    @Test
    fun `canAuthenticate defaults to false`() {
        assertFalse(stubProvider.canAuthenticate)
    }

    @Test
    fun `downloadById delegates to download`() =
        runBlocking {
            val bytes = stubProvider.downloadById("id1", "/file.txt", Path.of("/tmp/out"))
            assertEquals(42L, bytes)
        }

    // ── UD-704 rewrites ──────────────────────────────────────────────────────
    //
    // The six tests below used to cement the silent-defaults (null / emptyList
    // / false / true / delegate-to-delta). Post UD-301 they assert that a
    // provider without the relevant capability returns Unsupported with the
    // correct capability discriminator.

    @Test
    fun `deltaWithShared returns Unsupported when capability absent`() =
        runBlocking {
            val result = stubProvider.deltaWithShared(null)
            assertIs<CapabilityResult.Unsupported>(result)
            assertEquals(Capability.DeltaShared, result.capability)
            assertTrue(Capability.DeltaShared !in stubProvider.capabilities())
        }

    @Test
    fun `verifyItemExists returns Unsupported when capability absent`() =
        runBlocking {
            val result = stubProvider.verifyItemExists("any-id")
            assertIs<CapabilityResult.Unsupported>(result)
            assertEquals(Capability.VerifyItem, result.capability)
        }

    @Test
    fun `share returns Unsupported when capability absent`() =
        runBlocking {
            val result = stubProvider.share("/file.txt")
            assertIs<CapabilityResult.Unsupported>(result)
            assertEquals(Capability.Share, result.capability)
        }

    @Test
    fun `listShares returns Unsupported when capability absent`() =
        runBlocking {
            val result = stubProvider.listShares("/file.txt")
            assertIs<CapabilityResult.Unsupported>(result)
            assertEquals(Capability.ListShares, result.capability)
        }

    @Test
    fun `revokeShare returns Unsupported when capability absent`() =
        runBlocking {
            val result = stubProvider.revokeShare("/file.txt", "share-1")
            assertIs<CapabilityResult.Unsupported>(result)
            assertEquals(Capability.RevokeShare, result.capability)
        }

    @Test
    fun `handleWebhookCallback returns Unsupported when capability absent`() =
        runBlocking {
            val result = stubProvider.handleWebhookCallback(byteArrayOf(1, 2, 3))
            assertIs<CapabilityResult.Unsupported>(result)
            assertEquals(Capability.Webhook, result.capability)
        }

    @Test
    fun `close is a no-op by default`() {
        // Should not throw
        stubProvider.close()
    }
}
