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

    fun allByTier(): List<ProviderMetadata> {
        val order = listOf("Local", "DE-hosted", "EU-hosted", "Self-hosted", "Global")
        return allMetadata().sortedBy { order.indexOf(it.tier) }
    }

    fun isKnown(type: String): Boolean = type in knownTypes

    fun isKnownType(type: String): Boolean = type in defaultTypes
}
