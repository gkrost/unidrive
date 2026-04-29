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
