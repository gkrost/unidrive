package org.krost.unidrive.internxt

import kotlinx.serialization.json.Json
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.CredentialHealth
import org.krost.unidrive.ProviderFactory
import org.krost.unidrive.ProviderMetadata
import org.krost.unidrive.internxt.model.InternxtCredentials
import java.nio.file.Files
import java.nio.file.Path

class InternxtProviderFactory : ProviderFactory {
    private val json = Json { ignoreUnknownKeys = true }

    override val id = "internxt"

    override val metadata =
        ProviderMetadata(
            id = "internxt",
            displayName = "Internxt Drive",
            description = "Privacy-first cloud storage with client-side AES-256-GCM encryption",
            authType = "Email + password",
            encryption = "Client-side AES-256-GCM (zero-knowledge)",
            jurisdiction = "EU (Spain)",
            gdprCompliant = true,
            cloudActExposure = false,
            signupUrl = "https://internxt.com",
            tier = "EU-hosted",
            userRating = 4.3,
            benchmarkGrade = "C",
            affiliateUrl = "https://internxt.com",
        )

    override fun create(
        properties: Map<String, String?>,
        tokenPath: Path,
    ): CloudProvider {
        val config = InternxtConfig(tokenPath = tokenPath)
        return InternxtProvider(config)
    }

    override fun isAuthenticated(
        properties: Map<String, String?>,
        profileDir: Path,
    ): Boolean = Files.exists(profileDir.resolve("credentials.json"))

    override fun checkCredentialHealth(
        properties: Map<String, String?>,
        profileDir: Path,
    ): CredentialHealth {
        val credFile = profileDir.resolve("credentials.json")
        if (!Files.exists(credFile)) {
            return CredentialHealth.Missing("No credentials file — run 'unidrive auth'")
        }

        return try {
            val creds = json.decodeFromString<InternxtCredentials>(Files.readString(credFile))
            // Check JWT expiry directly - decode payload manually
            val jwt = creds.jwt
            val parts = jwt.split(".")
            if (parts.size >= 2) {
                val padded =
                    when (parts[1].length % 4) {
                        2 -> parts[1] + "=="
                        3 -> parts[1] + "="
                        else -> parts[1]
                    }
                val payloadJson =
                    String(
                        java.util.Base64
                            .getUrlDecoder()
                            .decode(padded),
                    )
                // Simple regex to extract "exp": value
                val expRegex = """"exp"\s*:\s*(\d+)""".toRegex()
                val match = expRegex.find(payloadJson)
                if (match != null) {
                    val exp = match.groupValues[1].toLong()
                    val now = System.currentTimeMillis() / 1000
                    if (now > exp) {
                        return CredentialHealth.ExpiresIn(0, "JWT expired — run 'unidrive auth'")
                    }
                }
            }
            CredentialHealth.Ok
        } catch (e: Exception) {
            CredentialHealth.Missing("Invalid credentials: ${e.message}")
        }
    }
}
