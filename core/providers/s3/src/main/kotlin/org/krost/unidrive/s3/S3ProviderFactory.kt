package org.krost.unidrive.s3

import org.krost.unidrive.CloudProvider
import org.krost.unidrive.ConfigurationException
import org.krost.unidrive.CredentialHealth
import org.krost.unidrive.ProviderFactory
import org.krost.unidrive.ProviderMetadata
import java.nio.file.Path

class S3ProviderFactory : ProviderFactory {
    override val id = "s3"

    override fun describeConnection(
        properties: Map<String, String?>,
        profileDir: Path,
    ): String {
        val parts = mutableListOf<String>()
        properties["bucket"]?.takeIf { it.isNotBlank() }?.let { parts += "bucket=$it" }
        properties["region"]?.takeIf { it.isNotBlank() }?.let { parts += "region=$it" }
        properties["endpoint"]?.takeIf { it.isNotBlank() }?.let { parts += "endpoint=$it" }
        properties["access_key_id"]?.takeIf { it.length >= 4 }?.let {
            parts += "key=${it.take(4)}..."
        }
        return if (parts.isEmpty()) "s3 provider" else "s3 (${parts.joinToString(", ")})"
    }

    override val metadata =
        ProviderMetadata(
            id = "s3",
            displayName = "S3 / S3-compatible",
            description = "AWS S3 or compatible: Hetzner Object Storage, Backblaze B2, Wasabi, MinIO, OVH",
            authType = "Access key + secret key",
            encryption = "Varies by provider (server-side typical)",
            jurisdiction = "Varies by provider and region",
            gdprCompliant = true,
            cloudActExposure = false,
            signupUrl = null,
            tier = "Global",
        )

    override fun create(
        properties: Map<String, String?>,
        tokenPath: Path,
    ): CloudProvider {
        val bucket =
            properties["bucket"]?.takeIf { it.isNotBlank() }
                ?: throw ConfigurationException("s3", "Required property 'bucket' is missing")
        val accessKey =
            properties["access_key_id"]?.takeIf { it.isNotBlank() }
                ?: throw ConfigurationException("s3", "Required property 'access_key_id' is missing")
        val secretKey =
            properties["secret_access_key"]?.takeIf { it.isNotBlank() }
                ?: throw ConfigurationException("s3", "Required property 'secret_access_key' is missing")

        val region = properties["region"]?.takeIf { it.isNotBlank() } ?: "auto"
        val endpoint =
            properties["endpoint"]?.takeIf { it.isNotBlank() }
                ?: S3Config.PRESETS["aws"]!!

        val config =
            S3Config(
                bucket = bucket,
                region = region,
                endpoint = endpoint,
                accessKey = accessKey,
                secretKey = secretKey,
                tokenPath = tokenPath,
            )
        return S3Provider(config)
    }

    override fun isAuthenticated(
        properties: Map<String, String?>,
        profileDir: Path,
    ): Boolean =
        properties["bucket"]?.isNotBlank() == true &&
            properties["access_key_id"]?.isNotBlank() == true &&
            properties["secret_access_key"]?.isNotBlank() == true

    override fun checkCredentialHealth(
        properties: Map<String, String?>,
        profileDir: Path,
    ): CredentialHealth {
        val missing = mutableListOf<String>()
        if (properties["bucket"].isNullOrBlank()) missing += "bucket"
        if (properties["access_key_id"].isNullOrBlank()) missing += "access_key_id"
        if (properties["secret_access_key"].isNullOrBlank()) missing += "secret_access_key"
        if (missing.isNotEmpty()) {
            return CredentialHealth.Missing("Missing: ${missing.joinToString(", ")}")
        }
        return CredentialHealth.Ok
    }
}
