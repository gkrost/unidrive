package org.krost.unidrive

import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Directly exercises the [Capability] / [CapabilityResult] contract added by
 * UD-301 / ADR-0005. Complements [CloudProviderDefaultsTest] (UD-704 rewrites)
 * by testing the sealed hierarchy itself and the adapter's freedom to return
 * [CapabilityResult.Success] when it does declare the capability.
 */
class CapabilityContractTest {
    private val stubItem =
        CloudItem(
            id = "1",
            name = "f",
            path = "/f",
            size = 0,
            isFolder = false,
            modified = Instant.EPOCH,
            created = Instant.EPOCH,
            hash = null,
            mimeType = null,
        )

    private class CapableProvider : CloudProvider {
        override val id = "capable"
        override val displayName = "Capable"
        override val isAuthenticated = true

        override fun capabilities(): Set<Capability> =
            setOf(
                Capability.Delta,
                Capability.Share,
                Capability.VerifyItem,
            )

        override suspend fun authenticate() {}

        override suspend fun logout() {}

        override suspend fun listChildren(path: String) = emptyList<CloudItem>()

        override suspend fun getMetadata(path: String) = throw UnsupportedOperationException()

        override suspend fun download(
            remotePath: String,
            destination: Path,
        ) = 0L

        override suspend fun upload(
            localPath: Path,
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
        ) = DeltaPage(emptyList(), "", false)

        override suspend fun quota() = QuotaInfo(0, 0, 0)

        override suspend fun share(
            path: String,
            expiryHours: Int,
            password: String?,
        ) = CapabilityResult.Success("https://share.example.com/$path")

        override suspend fun verifyItemExists(remoteId: String) = CapabilityResult.Success(true)
    }

    @Test
    fun `Capability subclasses are singletons`() {
        assertSame(Capability.Share, Capability.Share)
        assertSame(Capability.DeltaShared, Capability.DeltaShared)
    }

    @Test
    fun `Capability toString returns simple class name`() {
        assertEquals("Share", Capability.Share.toString())
        assertEquals("Webhook", Capability.Webhook.toString())
    }

    @Test
    fun `CapabilityResult Success isSuccess`() {
        val r: CapabilityResult<String> = CapabilityResult.Success("x")
        assertTrue(r.isSuccess)
        assertEquals("x", r.valueOrNull())
    }

    @Test
    fun `CapabilityResult Unsupported isUnsupported`() {
        val r: CapabilityResult<String> = CapabilityResult.Unsupported(Capability.Share, "nope")
        assertTrue(r.isUnsupported)
        assertNull(r.valueOrNull())
    }

    @Test
    fun `capable provider returns Success`() =
        runBlocking {
            val p = CapableProvider()
            val share = p.share("/hello.txt")
            assertIs<CapabilityResult.Success<String>>(share)
            assertEquals("https://share.example.com//hello.txt", share.value)

            val verify = p.verifyItemExists("any-id")
            assertIs<CapabilityResult.Success<Boolean>>(verify)
            assertTrue(verify.value)
        }

    @Test
    fun `capable provider still returns Unsupported for capabilities it does not declare`() =
        runBlocking {
            val p = CapableProvider()
            assertTrue(Capability.ListShares !in p.capabilities())
            val result = p.listShares("/x")
            assertIs<CapabilityResult.Unsupported>(result)
            assertEquals(Capability.ListShares, result.capability)
        }

    @Test
    fun `UnsupportedCapabilityException carries capability`() {
        val e = UnsupportedCapabilityException(Capability.Webhook, "boom")
        assertEquals(Capability.Webhook, e.capability)
        assertEquals("boom", e.message)
    }
}
