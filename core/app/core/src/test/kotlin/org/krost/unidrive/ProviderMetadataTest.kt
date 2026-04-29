package org.krost.unidrive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderMetadataTest {
    private val builtInTypes = setOf("onedrive", "rclone", "s3", "sftp", "webdav")

    @Test
    fun `knownTypes includes all 5 built-in providers`() {
        for (type in builtInTypes) {
            assertTrue(ProviderRegistry.isKnownType(type), "$type should be a known type")
        }
        assertFalse(ProviderRegistry.isKnownType("nonexistent"), "nonexistent should not be a known type")
    }

    @Test
    fun `knownTypes is not empty`() {
        assertTrue(ProviderRegistry.knownTypes.isNotEmpty())
    }

    @Test
    fun `get returns null for nonexistent`() {
        assertNull(ProviderRegistry.get("nonexistent"))
    }

    @Test
    fun `getMetadata returns null for nonexistent`() {
        assertNull(ProviderRegistry.getMetadata("nonexistent"))
    }

    @Test
    fun `getMetadata returns correct metadata when discovered`() {
        val meta = ProviderRegistry.getMetadata("onedrive")
        if (meta != null) {
            assertEquals("Microsoft OneDrive", meta.displayName)
            assertEquals("Global", meta.tier)
            assertTrue(meta.cloudActExposure)
        }
    }

    @Test
    fun `allMetadata returns list`() {
        val all = ProviderRegistry.allMetadata()
        // may be empty in unit-test classpath without providers, but should not throw
        for (meta in all) {
            assertTrue(meta.id.isNotBlank())
            assertTrue(meta.displayName.isNotBlank())
        }
    }

    @Test
    fun `allByTier returns sorted metadata`() {
        val sorted = ProviderRegistry.allByTier()
        for (meta in sorted) {
            assertTrue(meta.tier.isNotBlank())
        }
    }

    @Test
    fun `isKnown checks knownTypes`() {
        // localfs is in the default set
        assertTrue(ProviderRegistry.isKnown("localfs"))
        assertFalse(ProviderRegistry.isKnown("nonexistent"))
    }

    // --- ProviderMetadata data class construction ---

    @Test
    fun `ProviderMetadata stores all required fields`() {
        val meta =
            ProviderMetadata(
                id = "test",
                displayName = "Test Provider",
                description = "A test provider",
                authType = "oauth2",
                encryption = "TLS",
                jurisdiction = "DE",
                gdprCompliant = true,
                cloudActExposure = false,
                signupUrl = "https://example.com/signup",
                tier = "EU-hosted",
            )
        assertEquals("test", meta.id)
        assertEquals("Test Provider", meta.displayName)
        assertEquals("A test provider", meta.description)
        assertEquals("oauth2", meta.authType)
        assertEquals("TLS", meta.encryption)
        assertEquals("DE", meta.jurisdiction)
        assertTrue(meta.gdprCompliant)
        assertFalse(meta.cloudActExposure)
        assertEquals("https://example.com/signup", meta.signupUrl)
        assertEquals("EU-hosted", meta.tier)
        assertNull(meta.userRating)
        assertNull(meta.benchmarkGrade)
        assertNull(meta.affiliateUrl)
    }

    @Test
    fun `ProviderMetadata optional fields`() {
        val meta =
            ProviderMetadata(
                id = "rated",
                displayName = "Rated Provider",
                description = "Has optional fields",
                authType = "api_key",
                encryption = "AES-256",
                jurisdiction = "US",
                gdprCompliant = false,
                cloudActExposure = true,
                signupUrl = null,
                tier = "Global",
                userRating = 4.5,
                benchmarkGrade = "A",
                affiliateUrl = "https://example.com/aff",
            )
        assertEquals(4.5, meta.userRating)
        assertEquals("A", meta.benchmarkGrade)
        assertEquals("https://example.com/aff", meta.affiliateUrl)
        assertNull(meta.signupUrl)
    }

    // -- UD-263: per-provider concurrency hints --------------------------------

    @Test
    fun `UD-263 - maxConcurrentTransfers defaults to 4`() {
        val meta =
            ProviderMetadata(
                id = "default-conc",
                displayName = "X",
                description = "d",
                authType = "a",
                encryption = "e",
                jurisdiction = "j",
                gdprCompliant = true,
                cloudActExposure = false,
                signupUrl = null,
                tier = "Local",
            )
        assertEquals(
            4,
            meta.maxConcurrentTransfers,
            "default cap is 4 — conservative for un-audited providers",
        )
        assertEquals(
            0L,
            meta.minRequestSpacingMs,
            "default spacing is 0 — most providers don't pace per-request",
        )
    }

    @Test
    fun `UD-263 - maxConcurrentTransfers can be overridden`() {
        val meta =
            ProviderMetadata(
                id = "overrider",
                displayName = "X",
                description = "d",
                authType = "a",
                encryption = "e",
                jurisdiction = "j",
                gdprCompliant = true,
                cloudActExposure = false,
                signupUrl = null,
                tier = "Local",
                maxConcurrentTransfers = 16,
                minRequestSpacingMs = 200L,
            )
        assertEquals(16, meta.maxConcurrentTransfers)
        assertEquals(200L, meta.minRequestSpacingMs)
    }

    @Test
    fun `ProviderMetadata data class equality and copy`() {
        val meta1 =
            ProviderMetadata(
                id = "x",
                displayName = "X",
                description = "d",
                authType = "a",
                encryption = "e",
                jurisdiction = "j",
                gdprCompliant = true,
                cloudActExposure = false,
                signupUrl = null,
                tier = "Local",
            )
        val meta2 = meta1.copy()
        assertEquals(meta1, meta2)
        val meta3 = meta1.copy(id = "y")
        assertFalse(meta1 == meta3)
        assertEquals("y", meta3.id)
    }

    // --- ShareInfo data class ---

    @Test
    fun `ShareInfo stores all fields`() {
        val info =
            ShareInfo(
                id = "s1",
                url = "https://share.example.com/abc",
                type = "view",
                scope = "anonymous",
                hasPassword = true,
                expiration = "2026-05-01T00:00:00Z",
            )
        assertEquals("s1", info.id)
        assertEquals("https://share.example.com/abc", info.url)
        assertEquals("view", info.type)
        assertEquals("anonymous", info.scope)
        assertTrue(info.hasPassword)
        assertEquals("2026-05-01T00:00:00Z", info.expiration)
    }

    @Test
    fun `ShareInfo defaults`() {
        val info = ShareInfo(id = "s2", url = "u", type = "edit", scope = "org")
        assertFalse(info.hasPassword)
        assertNull(info.expiration)
    }
}
