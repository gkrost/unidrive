package org.krost.unidrive.onedrive

import org.krost.unidrive.io.defaultTokenPath
import java.nio.file.Path

data class OneDriveConfig(
    val applicationId: String = DEFAULT_APP_ID,
    val tokenPath: Path = defaultTokenPath("onedrive"),
    val userAgent: String = DEFAULT_USER_AGENT,
    val oauthCallbackPort: Int = resolveOAuthCallbackPort(System.getenv(OAUTH_PORT_ENV)),
    val redirectUri: String = "http://localhost:$oauthCallbackPort/callback",
    val authEndpoint: String = DEFAULT_AUTH_ENDPOINT,
    val includeShared: Boolean = false,
    val webhookEnabled: Boolean = false,
    val webhookPort: Int = 8081,
) {
    companion object {
        const val DEFAULT_APP_ID = "aa961a73-f4ac-41d9-8150-0a18f330ff6c"
        const val DEFAULT_USER_AGENT = "ISV|unidrive|Kotlin/1.0"
        const val DEFAULT_OAUTH_CALLBACK_PORT = 8080
        const val DEFAULT_REDIRECT_URI = "http://localhost:$DEFAULT_OAUTH_CALLBACK_PORT/callback"
        const val DEFAULT_AUTH_ENDPOINT = "https://login.microsoftonline.com/consumers/oauth2/v2.0"

        /** Environment variable that moves the browser sign-in callback off the default port. */
        const val OAUTH_PORT_ENV = "UNIDRIVE_ONEDRIVE_OAUTH_PORT"

        const val GRAPH_BASE_URL = "https://graph.microsoft.com"
        const val GRAPH_VERSION = "v1.0"

        private val log = org.slf4j.LoggerFactory.getLogger(OneDriveConfig::class.java)

        /**
         * Parse the [OAUTH_PORT_ENV] value: a port in 1..65535. Absent means the default; an unusable
         * value is reported and also falls back to the default, so a typo cannot silently pick a port.
         */
        fun resolveOAuthCallbackPort(raw: String?): Int {
            if (raw == null) return DEFAULT_OAUTH_CALLBACK_PORT
            val port = raw.trim().toIntOrNull()
            if (port == null || port !in 1..65535) {
                log.warn("Ignoring {}='{}' (expected a port in 1..65535); using {}", OAUTH_PORT_ENV, raw, DEFAULT_OAUTH_CALLBACK_PORT)
                return DEFAULT_OAUTH_CALLBACK_PORT
            }
            return port
        }
    }
}
