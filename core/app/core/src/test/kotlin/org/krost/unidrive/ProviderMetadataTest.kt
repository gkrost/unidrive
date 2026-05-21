package org.krost.unidrive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderMetadataTest {
    // UD-013: deleted `knownTypes includes all 5 built-in providers` (tautological — tested
    // `isKnownType` against the same hardcoded `defaultTypes` set the method consulted) and
    // `knownTypes is not empty` (relied on the silent fallback that UD-013 removed). The
    // populated-classpath equivalents live in :app:cli's ProviderRegistryDiscoveryTest.

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

    // UD-810 audit: renamed from `allByTier returns sorted metadata` and
    // rewritten under Codex PR #18 review feedback.
    //
    // Previous body sorted with a LOCAL `sortedBy { canonical.indexOf(...) }`
    // expression that never invoked `ProviderRegistry.allByTier()` — if
    // `allByTier()` changed to the wrong order or stopped sorting entirely,
    // the test would still pass. Codex flagged this on PR #18.
    //
    // Fix: `ProviderRegistry.allByTier` now takes an optional
    // `metadata: List<ProviderMetadata>` parameter (default = `allMetadata()`)
    // so synthesized fixtures can be injected through the real API in
    // :app:core's classpath-less test environment. The test now exercises
    // the actual production sort recipe.
    //
    // The "real providers tier-sorted" pin against a populated classpath
    // still belongs in :app:cli's ProviderRegistryDiscoveryTest (separate
    // chunk).
    @Test
    fun `allByTier applies the canonical tier ordering`() {
        // Build one fixture per canonical tier value, inserted in reverse so
        // a no-op sort (or wrong-order sort) is detectable.
        val synthesized = ProviderRegistry.TIER_ORDER.reversed().map { tier -> tierStub(tier) }
        val sorted = ProviderRegistry.allByTier(synthesized)
        assertEquals(
            ProviderRegistry.TIER_ORDER,
            sorted.map { it.tier },
            "ProviderRegistry.allByTier must return entries in TIER_ORDER",
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

    // UD-013: deleted `isKnown checks knownTypes` — relied on the silent fallback via
    // `defaultTypes` for the localfs assertion. Populated-classpath equivalent lives in
    // :app:cli's ProviderRegistryDiscoveryTest.

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
