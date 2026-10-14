package org.krost.unidrive.s3

import org.krost.unidrive.CloudProvider
import org.krost.unidrive.ConfigurationException
import org.krost.unidrive.CredentialHealth
import org.krost.unidrive.PromptSpec
import org.krost.unidrive.ProviderFactory
import org.krost.unidrive.ProviderMetadata
import java.nio.file.Path

class S3ProviderFactory : ProviderFactory {
    override val id = "s3"

    override val metadata =
        ProviderMetadata(
            id = "s3",
            displayName = "S3 / S3-compatible",
            description = "Amazon S3 or an S3-compatible service: Scaleway, Hetzner, Backblaze B2, Wasabi, OVH, MinIO",
            authType = "Access key + secret key",
            encryption = "Varies by provider (server-side typical)",
            jurisdiction = "Varies by provider and region",
            gdprCompliant = true,
            cloudActExposure = false,
            signupUrl = null,
            tier = "Global",
            maxConcurrentTransfers = 8,
            // S3 has no account plan to report: a bucket has no quota.
            hasQuota = false,
        )

    override fun create(
        properties: Map<String, String?>,
        tokenPath: Path,
    ): CloudProvider {
        val bucket = required(properties, "bucket")
        val accessKey = required(properties, "access_key_id")
        val secretKey = required(properties, "secret_access_key")
        val region = properties["region"]?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_REGION
        val endpoint = properties["endpoint"]?.trim()?.takeIf { it.isNotEmpty() } ?: S3Config.awsEndpoint(region)
        if (!endpoint.startsWith("https://") && !endpoint.startsWith("http://")) {
            throw ConfigurationException(id, "'endpoint' must be an http(s) URL, got '$endpoint'")
        }
        return S3Provider(S3Config(bucket, region, endpoint, accessKey, secretKey, tokenPath))
    }

    private fun required(
        properties: Map<String, String?>,
        key: String,
    ): String = properties[key]?.trim()?.takeIf { it.isNotEmpty() } ?: throw ConfigurationException(id, "Required property '$key' is missing")

    override fun isAuthenticated(
        properties: Map<String, String?>,
        profileDir: Path,
    ): Boolean = listOf("bucket", "access_key_id", "secret_access_key").all { !properties[it].isNullOrBlank() }

    override fun checkCredentialHealth(
        properties: Map<String, String?>,
        profileDir: Path,
    ): CredentialHealth {
        val missing = listOf("bucket", "access_key_id", "secret_access_key").filter { properties[it].isNullOrBlank() }
        return if (missing.isEmpty()) CredentialHealth.Ok else CredentialHealth.Missing("Missing: ${missing.joinToString(", ")}")
    }

    override fun describeConnection(
        properties: Map<String, String?>,
        profileDir: Path,
    ): String {
        val parts =
            listOfNotNull(
                properties["bucket"]?.takeIf { it.isNotBlank() }?.let { "bucket=$it" },
                properties["region"]?.takeIf { it.isNotBlank() }?.let { "region=$it" },
                properties["endpoint"]?.takeIf { it.isNotBlank() }?.let { "endpoint=$it" },
                properties["access_key_id"]?.takeIf { it.length >= 4 }?.let { "key=${it.take(4)}..." },
            )
        return if (parts.isEmpty()) "s3 provider" else "s3 (${parts.joinToString(", ")})"
    }

    override fun credentialPrompts(): List<PromptSpec> =
        listOf(
            PromptSpec(key = "bucket", label = "S3 bucket"),
            PromptSpec(key = "region", label = "S3 region", default = DEFAULT_REGION, required = false),
            PromptSpec(key = "endpoint", label = "S3 endpoint (empty for AWS in that region)", required = false),
            PromptSpec(key = "access_key_id", label = "Access key ID"),
            PromptSpec(key = "secret_access_key", label = "Secret access key", isMasked = true),
        )

    override fun envVarMappings(): Map<String, String> =
        mapOf(
            "S3_BUCKET" to "bucket",
            "AWS_ACCESS_KEY_ID" to "access_key_id",
            "AWS_SECRET_ACCESS_KEY" to "secret_access_key",
        )

    private companion object {
        const val DEFAULT_REGION = "us-east-1"
    }
}
