package org.krost.unidrive.hidrive

import org.krost.unidrive.CloudProvider
import org.krost.unidrive.ProviderFactory
import org.krost.unidrive.ProviderMetadata
import java.nio.file.Files
import java.nio.file.Path

class HiDriveProviderFactory : ProviderFactory {
    override val id = "hidrive"

    override val metadata =
        ProviderMetadata(
            id = "hidrive",
            displayName = "IONOS HiDrive",
            description = "German-hosted cloud storage by IONOS (1&1/United Internet)",
            authType = "OAuth2 (browser)",
            encryption = "Server-side",
            jurisdiction = "Germany",
            gdprCompliant = true,
            cloudActExposure = false,
            signupUrl = "https://www.ionos.de/office-loesungen/hidrive-cloud-speicher",
            tier = "DE-hosted",
            userRating = 4.2,
            benchmarkGrade = "B",
            affiliateUrl = "https://www.ionos.de/office-loesungen/hidrive-cloud-speicher",
        )

    override fun create(
        properties: Map<String, String?>,
        tokenPath: Path,
    ): CloudProvider {
        val clientId = properties["client_id"]
        val clientSecret = properties["client_secret"]
        val config =
            if (!clientId.isNullOrBlank() && !clientSecret.isNullOrBlank()) {
                HiDriveConfig(clientId = clientId, clientSecret = clientSecret, tokenPath = tokenPath)
            } else {
                HiDriveConfig(tokenPath = tokenPath)
            }
        return HiDriveProvider(config)
    }

    override fun isAuthenticated(
        properties: Map<String, String?>,
        profileDir: Path,
    ): Boolean {
        val clientId = properties["client_id"]
        val clientSecret = properties["client_secret"]
        val hasCredentials =
            if (!clientId.isNullOrBlank() && !clientSecret.isNullOrBlank()) {
                true
            } else {
                !System.getenv("HIDRIVE_CLIENT_ID").isNullOrBlank() &&
                    !System.getenv("HIDRIVE_CLIENT_SECRET").isNullOrBlank()
            }
        return hasCredentials && Files.exists(profileDir.resolve("token.json"))
    }
}
