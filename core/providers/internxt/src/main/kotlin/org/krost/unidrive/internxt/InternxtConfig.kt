package org.krost.unidrive.internxt

import java.nio.file.Path
import java.nio.file.Paths

data class InternxtConfig(
    val tokenPath: Path = defaultTokenPath(),
) {
    companion object {
        const val API_BASE_URL = "https://gateway.internxt.com/drive"
        const val CLIENT_NAME = "drive-desktop-linux"
        const val CLIENT_VERSION = "2.5.668"
        const val DESKTOP_HEADER = "internxt-desktop-dev-header"

        // Public encryption salt from Internxt's open-source desktop client (not a secret).
        // Used in password hashing during auth. Override via env var for custom deployments.
        val CRYPTO_KEY: String = System.getenv("INTERNXT_CRYPTO_KEY") ?: "6KYQBP847D4ATSFA"

        fun defaultTokenPath(): Path {
            val home = System.getenv("HOME") ?: System.getProperty("user.home")
            return Paths.get(home, ".config", "unidrive", "internxt")
        }
    }
}
