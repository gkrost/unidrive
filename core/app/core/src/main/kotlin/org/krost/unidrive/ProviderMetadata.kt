package org.krost.unidrive

import java.util.ServiceLoader

data class ProviderMetadata(
    val id: String,
    val displayName: String,
    val description: String,
    val authType: String,
    val encryption: String,
    val jurisdiction: String,
    val gdprCompliant: Boolean,
    val cloudActExposure: Boolean,
    val signupUrl: String?,
    val tier: String,
    val userRating: Double? = null,
    val benchmarkGrade: String? = null,
    val affiliateUrl: String? = null,
    /**
     * UD-263: maximum concurrent in-flight transfers SyncEngine Pass 2 will
     * issue against this provider. Sized per-provider from the audit docs
     * under `docs/providers/<id>-robustness.md` §5 ("Concurrency
     * recommendations"). Default 4 — conservative bucket for un-audited
     * providers; overridden per-factory.
     */
    val maxConcurrentTransfers: Int = 4,
    /**
     * UD-263: minimum spacing (ms) the provider should leave between two
     * back-to-back request starts. 0 for providers without a documented
     * pacing requirement; > 0 for endpoints whose throttle policy is
     * rate-bucket rather than concurrency-bucket (Graph 200 ms, ratelimited
     * S3 prefixes, etc.). Stateless across SyncEngine invocations —
     * acceptable simplicity tradeoff per UD-263 acceptance #4.
     */
    val minRequestSpacingMs: Long = 0L,
)

object ProviderRegistry {
    private val loader: ServiceLoader<ProviderFactory> = ServiceLoader.load(ProviderFactory::class.java)

    private val defaultTypes = setOf("localfs", "onedrive", "rclone", "s3", "sftp", "webdav")

    val knownTypes: Set<String> by lazy {
        val discovered = loader.toList().map { it.id }.toSet()
        if (discovered.isEmpty()) defaultTypes else discovered
    }

    fun get(id: String): ProviderFactory? = loader.toList().find { it.id == id }

    fun getMetadata(id: String): ProviderMetadata? = get(id)?.metadata

    /**
     * Normalise a user-supplied provider id to its canonical form.
     *
     * Applies `trim()` + lower-case so that `"ONEDRIVE"`, `" onedrive "`, and
     * `"OneDrive"` all resolve to `"onedrive"`. Returns `null` if the input
     * (after normalisation) is empty or not a known provider.
     */
    fun resolveId(raw: String): String? {
        val normalised = raw.trim().lowercase()
        if (normalised.isEmpty()) return null
        return if (normalised in knownTypes) normalised else null
    }

    fun all(): List<ProviderFactory> = loader.toList()

    fun allMetadata(): List<ProviderMetadata> = loader.toList().map { it.metadata }

    /**
     * Sort the supplied metadata list (default: all discovered providers'
     * metadata) by the canonical tier ordering. Entries with unrecognised
     * tier values are placed at the front (`indexOf` returns -1), which is
     * fine for the current consumer (CLI provider table) — those would
     * also be a configuration error worth surfacing.
     *
     * The `metadata` parameter is the seam UD-810's
     * `ProviderMetadataTest.allByTier applies the canonical tier ordering`
     * uses to exercise the sort against synthesized fixtures in
     * `:app:core`'s test classpath (which doesn't load real providers).
     * Codex review on PR #18 surfaced that the previous test sorted with a
     * local recipe and never invoked this method — refactoring the
     * signature to accept fixtures restores end-to-end coverage of the
     * production API.
     */
    fun allByTier(metadata: List<ProviderMetadata> = allMetadata()): List<ProviderMetadata> =
        metadata.sortedBy { TIER_ORDER.indexOf(it.tier) }

    /**
     * Canonical tier ordering. Exposed for tests that exercise [allByTier]
     * with synthesized fixtures; production callers should use [allByTier]
     * with no arguments.
     */
    val TIER_ORDER: List<String> = listOf("Local", "DE-hosted", "EU-hosted", "Self-hosted", "Global")

    fun isKnown(type: String): Boolean = type in knownTypes

    fun isKnownType(type: String): Boolean = type in defaultTypes

    /**
     * UD-012: the registry's opinion of "what should we default to when the
     * user hasn't pinned a profile?"
     *
     * Returns `"localfs"` when it's discovered on the classpath — it has zero
     * setup cost, never fails the auth step, and is always present in v0.1.0
     * builds. Falls back to the first SPI-discovered provider (deterministic
     * by classpath order) otherwise. Falls back to `"localfs"` (the literal
     * string, even if no factory is discovered) when nothing is loaded — this
     * is the test-classpath path, and downstream `resolveProfile()` will
     * surface a readable error if the choice is unusable.
     *
     * Replaces the historical `"onedrive"` literal that
     * `SyncConfig.kt` carried as a single-provider-era artefact. Production
     * callers (`SyncConfig.load / defaults / parse / resolveDefaultProfile`)
     * delegate here instead of hardcoding a provider id.
     */
    fun defaultProvider(): String {
        val discovered = loader.toList().map { it.id }
        return when {
            "localfs" in discovered -> "localfs"
            discovered.isNotEmpty() -> discovered.first()
            else -> "localfs"
        }
    }
}
