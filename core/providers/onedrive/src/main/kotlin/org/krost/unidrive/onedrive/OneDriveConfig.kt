package org.krost.unidrive.onedrive

import java.nio.file.Path
import java.nio.file.Paths

data class OneDriveConfig(
    val applicationId: String = DEFAULT_APP_ID,
    val tokenPath: Path = defaultTokenPath(),
    val userAgent: String = DEFAULT_USER_AGENT,
    val redirectUri: String = DEFAULT_REDIRECT_URI,
    val authEndpoint: String = DEFAULT_AUTH_ENDPOINT,
    val includeShared: Boolean = false,
    val webhookEnabled: Boolean = false,
    val webhookPort: Int = 8081,
) {
    companion object {
        const val DEFAULT_APP_ID = "aa961a73-f4ac-41d9-8150-0a18f330ff6c"
        const val DEFAULT_USER_AGENT = "ISV|unidrive|Kotlin/1.0"
        const val DEFAULT_REDIRECT_URI = "http://localhost:8080/callback"
        const val DEFAULT_AUTH_ENDPOINT = "https://login.microsoftonline.com/consumers/oauth2/v2.0"

        const val GRAPH_BASE_URL = "https://graph.microsoft.com"
        const val GRAPH_VERSION = "v1.0"

        fun defaultTokenPath(): Path {
            val home = System.getenv("HOME") ?: System.getProperty("user.home")
            return Paths.get(home, ".config", "unidrive", "onedrive")
        }
    }
}
