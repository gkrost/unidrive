package org.krost.unidrive.onedrive

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.Capability
import org.krost.unidrive.CapabilityResult
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.ShareInfo
import kotlin.test.*

class OneDriveShareManageTest {
    @Test
    fun `ShareInfo data class construction`() {
        val info =
            ShareInfo(
                id = "perm-123",
                url = "https://example.com/share/abc",
                type = "view",
                scope = "anonymous",
                hasPassword = true,
                expiration = "2026-04-14T00:00:00Z",
            )
        assertEquals("perm-123", info.id)
        assertEquals("https://example.com/share/abc", info.url)
        assertEquals("view", info.type)
        assertEquals("anonymous", info.scope)
        assertTrue(info.hasPassword)
        assertEquals("2026-04-14T00:00:00Z", info.expiration)
    }

    @Test
    fun `ShareInfo defaults`() {
        val info = ShareInfo(id = "x", url = "u", type = "edit", scope = "organization")
        assertFalse(info.hasPassword)
        assertNull(info.expiration)
    }

    @Test
    fun `listShares returns Unsupported by default (UD-301)`() =
        runTest {
            val provider =
                object : CloudProvider {
                    override val id = "stub"
                    override val displayName = "Stub"
                    override val isAuthenticated = false

                    override fun capabilities(): Set<Capability> = emptySet()

                    override suspend fun authenticate() {}

                    override suspend fun logout() {}

                    override suspend fun listChildren(path: String) = emptyList<org.krost.unidrive.CloudItem>()

                    override suspend fun getMetadata(path: String) = throw UnsupportedOperationException()

                    override suspend fun download(
                        remotePath: String,
                        destination: java.nio.file.Path,
                    ) = 0L

                    override suspend fun upload(
                        localPath: java.nio.file.Path,
                        remotePath: String,
                        onProgress: ((Long, Long) -> Unit)?,
                    ) = throw UnsupportedOperationException()

                    override suspend fun delete(remotePath: String) {}

                    override suspend fun createFolder(path: String) = throw UnsupportedOperationException()

                    override suspend fun move(
                        fromPath: String,
                        toPath: String,
                    ) = throw UnsupportedOperationException()

                    override suspend fun delta(
                        cursor: String?,
                        onPageProgress: ((itemsSoFar: Int) -> Unit)?,
                    ) = throw UnsupportedOperationException()

                    override suspend fun quota() = throw UnsupportedOperationException()
                }
            val result = provider.listShares("/test")
            assertIs<CapabilityResult.Unsupported>(result)
            assertEquals(Capability.ListShares, result.capability)
        }

    @Test
    fun `revokeShare returns Unsupported by default (UD-301)`() =
        runTest {
            val provider =
                object : CloudProvider {
                    override val id = "stub"
                    override val displayName = "Stub"
                    override val isAuthenticated = false

                    override fun capabilities(): Set<Capability> = emptySet()

                    override suspend fun authenticate() {}

                    override suspend fun logout() {}

                    override suspend fun listChildren(path: String) = emptyList<org.krost.unidrive.CloudItem>()

                    override suspend fun getMetadata(path: String) = throw UnsupportedOperationException()

                    override suspend fun download(
                        remotePath: String,
                        destination: java.nio.file.Path,
                    ) = 0L

                    override suspend fun upload(
                        localPath: java.nio.file.Path,
                        remotePath: String,
                        onProgress: ((Long, Long) -> Unit)?,
                    ) = throw UnsupportedOperationException()

                    override suspend fun delete(remotePath: String) {}

                    override suspend fun createFolder(path: String) = throw UnsupportedOperationException()

                    override suspend fun move(
                        fromPath: String,
                        toPath: String,
                    ) = throw UnsupportedOperationException()

                    override suspend fun delta(
                        cursor: String?,
                        onPageProgress: ((itemsSoFar: Int) -> Unit)?,
                    ) = throw UnsupportedOperationException()

                    override suspend fun quota() = throw UnsupportedOperationException()
                }
            val result = provider.revokeShare("/test", "some-id")
            assertIs<CapabilityResult.Unsupported>(result)
            assertEquals(Capability.RevokeShare, result.capability)
        }
}
