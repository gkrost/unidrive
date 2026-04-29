package org.krost.unidrive.hidrive

import java.nio.file.Path
import java.nio.file.Paths

data class HiDriveConfig(
    val clientId: String = envOrDefault("HIDRIVE_CLIENT_ID", ""),
    val clientSecret: String = envOrDefault("HIDRIVE_CLIENT_SECRET", ""),
    val tokenPath: Path = defaultTokenPath(),
) {
    companion object {
        const val API_BASE_URL = "https://api.hidrive.strato.com/2.1"
        const val AUTH_ENDPOINT = "https://my.hidrive.com/client/authorize"
        const val TOKEN_ENDPOINT = "https://api.hidrive.strato.com/oauth2/token"
        const val REVOKE_ENDPOINT = "https://api.hidrive.strato.com/oauth2/revoke"
        const val SCOPE = "user,rw"
        const val CALLBACK_PORT = 8666
        const val REDIRECT_URI = "http://localhost:$CALLBACK_PORT"

        fun defaultTokenPath(): Path {
            val home = System.getenv("HOME") ?: System.getProperty("user.home")
            return Paths.get(home, ".config", "unidrive", "hidrive")
        }

        private fun envOrDefault(
            name: String,
            default: String,
        ): String = System.getenv(name) ?: default
    }
}
