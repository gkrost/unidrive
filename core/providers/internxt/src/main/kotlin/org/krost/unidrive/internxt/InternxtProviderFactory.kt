package org.krost.unidrive.internxt

import org.krost.unidrive.CloudProvider
import org.krost.unidrive.CredentialHealth
import org.krost.unidrive.ProviderFactory
import org.krost.unidrive.ProviderMetadata
import org.krost.unidrive.UnidriveJson
import org.krost.unidrive.auth.JwtExtractor
import org.krost.unidrive.internxt.model.InternxtCredentials
import java.nio.file.Files
import java.nio.file.Path

class InternxtProviderFactory : ProviderFactory {
    private val json = UnidriveJson

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
            // UD-263: matches the official Internxt SDK's 2-drive Bottleneck
            // pacer. The encryption-vs-retry boundary in the 5-stage upload
            // pipeline (audit §4.3) makes higher concurrency dangerous —
            // IV-pinning means a parallel-retry race can corrupt files.
            maxConcurrentTransfers = 2,
        )

    override fun create(
        properties: Map<String, String?>,
        tokenPath: Path,
    ): CloudProvider {
        // Destructive-overwrite guard: opt-in per profile via
        // `[providers.<name>] keep_overwritten = true`. ProfileResolver
        // funnels TOML keys through the properties map.
        val keepOverwritten = properties["keep_overwritten"]?.toBooleanStrictOrNull() ?: false
        val config = InternxtConfig(tokenPath = tokenPath, keepOverwritten = keepOverwritten)
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
            // UD-010: JWT body decode + exp extraction lifted to JwtExtractor.
            // UD-308 padding fix is preserved in the shared impl.
            val exp = JwtExtractor.extractExp(creds.jwt)
            if (exp != null && System.currentTimeMillis() / 1000 > exp) {
                return CredentialHealth.ExpiresIn(0, "JWT expired — run 'unidrive auth'")
            }
            CredentialHealth.Ok
        } catch (e: Exception) {
            CredentialHealth.Missing("Invalid credentials: ${e.message}")
        }
    }
}
