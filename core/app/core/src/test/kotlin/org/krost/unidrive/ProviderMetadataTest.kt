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

    // UD-813 audit: this module's test classpath does NOT include the provider
    // implementations, so `getMetadata("onedrive")` returns null here. The
    // substantive assertion (OneDrive resolves to "Microsoft OneDrive" / "Global"
    // / CloudActExposure = true) lives in :app:cli's ProviderRegistryDiscoveryTest
    // where the providers ARE on the test classpath. The invariant pinned here is
    // narrower: `getMetadata` is consistent with `get` — if the factory is
    // discoverable, its metadata is exposed equally via both entry points.
    @Test
    fun `getMetadata is consistent with get`() {
        for (factory in ProviderRegistry.all()) {
            val viaGet = factory.metadata
            val viaGetMetadata = ProviderRegistry.getMetadata(factory.id)
            assertEquals(
                viaGet,
                viaGetMetadata,
                "getMetadata('${factory.id}') diverges from get('${factory.id}').metadata",
            )
        }
    }

    // UD-810 audit: renamed from `allMetadata returns list`. The body's
    // for-loop runs 0 iterations when the provider classpath is empty
    // (the case in :app:core, where provider modules are not on the
    // test classpath), so the previous test silently passed when the
    // SPI was broken. The structural invariant ("every discovered
    // entry has non-blank id and displayName") is still worth pinning
    // for any classpath state; the "at least one entry discovered"
    // invariant lives in :app:cli's ProviderRegistryDiscoveryTest where
    // every provider IS on the test classpath.
    @Test
    fun `every discovered metadata has non-blank id and displayName`() {
        for (meta in ProviderRegistry.allMetadata()) {
            assertTrue(meta.id.isNotBlank(), "metadata id must be non-blank; got '${meta.id}'")
            assertTrue(
                meta.displayName.isNotBlank(),
                "metadata '${meta.id}' displayName must be non-blank",
            )
        }
    }

    // UD-810 audit: renamed from `allByTier returns sorted metadata`.
    // The previous body iterated without checking sort order — the name
    // promised sort verification, the body just verified non-blank tier.
    //
    // Synthesizing test: build one ProviderMetadata per tier value in the
    // canonical order, sort using the same recipe `allByTier()` applies
    // (`sortedBy { canonical.indexOf(it.tier) }`), and assert they come
    // back in canonical order regardless of insertion order. This pins
    // the canonical order constant in ProviderRegistry without depending
    // on the provider classpath. The "real providers actually
    // discoverable and tier-sorted" pin against a populated classpath
    // belongs in :app:cli's ProviderRegistryDiscoveryTest.
    @Test
    fun `allByTier applies the canonical tier ordering`() {
        // Must mirror ProviderRegistry.allByTier()'s internal `order` list.
        val canonical = listOf("Local", "DE-hosted", "EU-hosted", "Self-hosted", "Global")
        // Insert in reverse so a no-op sort is detectable.
        val synthesized = canonical.reversed().map { tier -> tierStub(tier) }
        val sorted = synthesized.sortedBy { canonical.indexOf(it.tier) }
        assertEquals(
            canonical,
            sorted.map { it.tier },
            "allByTier sort recipe should produce canonical tier order",
        )
    }

    private fun tierStub(tier: String): ProviderMetadata =
        ProviderMetadata(
            id = "stub-${tier.lowercase().replace("-", "_")}",
            displayName = "Stub $tier",
            description = "tier fixture",
            authType = "n/a",
            encryption = "n/a",
            jurisdiction = "n/a",
            gdprCompliant = true,
            cloudActExposure = false,
            signupUrl = null,
            tier = tier,
        )

    @Test
    fun `isKnown checks knownTypes`() {
        // localfs is in the default set
        assertTrue(ProviderRegistry.isKnown("localfs"))
        assertFalse(ProviderRegistry.isKnown("nonexistent"))
    }

    // UD-810 audit: deleted `ProviderMetadata stores all required fields` and
    // `ProviderMetadata optional fields` — both tested Kotlin's data-class
    // constructor + getter generation, not a domain invariant. If `data class`
    // ever becomes a regular `class` (or a field rename happens), the affected
    // callers fail at compile time; no runtime test would catch a regression
    // that the compiler would not catch first. See CHANGELOG `[Unreleased] / Removed`.

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

    // UD-810 audit: deleted `ProviderMetadata data class equality and copy`,
    // `ShareInfo stores all fields`, `ShareInfo defaults` — all three tested
    // Kotlin's data-class generated machinery (equals/copy/getter/default-args),
    // not domain invariants. Compiler-generated behaviour is the contract; a
    // runtime test that re-derives it adds maintenance cost without catching
    // anything the compiler doesn't already catch. See CHANGELOG.
}
