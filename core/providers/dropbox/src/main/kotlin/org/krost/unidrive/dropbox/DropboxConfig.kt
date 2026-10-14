package org.krost.unidrive.dropbox

import org.krost.unidrive.io.defaultTokenPath
import java.nio.file.Path

/**
 * One Dropbox profile. [appKey] is the app key of a Dropbox app registered as a public client (PKCE, no secret) with
 * [redirectUri] among its redirect URIs; unidrive ships none of its own yet, so the profile names it.
 */
data class DropboxConfig(
    val appKey: String,
    val tokenPath: Path = defaultTokenPath("dropbox"),
    val oauthCallbackPort: Int = resolveOAuthCallbackPort(System.getenv(OAUTH_PORT_ENV)),
    val redirectUri: String = "http://localhost:$oauthCallbackPort/callback",
    val userAgent: String = DEFAULT_USER_AGENT,
    val apiBase: String = API_BASE,
    val contentBase: String = CONTENT_BASE,
    val notifyBase: String = NOTIFY_BASE,
    val authorizeUrl: String = AUTHORIZE_URL,
    val tokenUrl: String = TOKEN_URL,
    /** Files above this size go up through an upload session; below it, one `files/upload` call. */
    val simpleUploadMaxBytes: Long = SIMPLE_UPLOAD_MAX_BYTES,
    /** The size of one upload-session append; a multiple of Dropbox's 4 MiB block. */
    val uploadChunkBytes: Int = UPLOAD_CHUNK_BYTES,
) {
    companion object {
        const val API_BASE = "https://api.dropboxapi.com/2"
        const val CONTENT_BASE = "https://content.dropboxapi.com/2"
        const val NOTIFY_BASE = "https://notify.dropboxapi.com/2"
        const val AUTHORIZE_URL = "https://www.dropbox.com/oauth2/authorize"
        const val TOKEN_URL = "https://api.dropboxapi.com/oauth2/token"
        const val DEFAULT_USER_AGENT = "unidrive/1.0"

        // Dropbox takes at most 150 MiB in one files/upload call.
        const val SIMPLE_UPLOAD_MAX_BYTES: Long = 128L * 1024 * 1024
        const val UPLOAD_CHUNK_BYTES: Int = 16 * 1024 * 1024

        const val DEFAULT_OAUTH_CALLBACK_PORT = 8080

        /** Environment variable that moves the browser sign-in callback off the default port. */
        const val OAUTH_PORT_ENV = "UNIDRIVE_DROPBOX_OAUTH_PORT"

        /** Environment variable that supplies the app key (`client_id`) when the profile does not. */
        const val CLIENT_ID_ENV = "UNIDRIVE_DROPBOX_CLIENT_ID"

        private val log = org.slf4j.LoggerFactory.getLogger(DropboxConfig::class.java)

        /** A port in 1..65535 from [OAUTH_PORT_ENV]; absent or unusable means the default (an unusable one is reported). */
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
