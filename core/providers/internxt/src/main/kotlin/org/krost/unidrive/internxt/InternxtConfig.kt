package org.krost.unidrive.internxt

import org.krost.unidrive.io.defaultTokenPath
import java.nio.file.Path

data class InternxtConfig(
    val tokenPath: Path = defaultTokenPath("internxt"),
) {
    companion object {
        const val API_BASE_URL = "https://gateway.internxt.com/drive"
        const val CLIENT_NAME = "unidrive"
        const val CLIENT_VERSION = "0.0.1"
        const val DESKTOP_HEADER = "internxt-desktop-dev-header"

        // Public encryption salt from Internxt's open-source desktop client (not a secret).
        // Used in password hashing during auth. Override via env var for custom deployments.
        val CRYPTO_KEY: String = System.getenv("INTERNXT_CRYPTO_KEY") ?: "6KYQBP847D4ATSFA"

        /** UD-304: swift-core parity — files at/above this size use multipart upload. */
        const val MULTIPART_MIN_SIZE_BYTES: Long = 100L * 1024L * 1024L // 100 MB

        /** UD-304: swift-core parity — each multipart chunk size. */
        const val MULTIPART_CHUNK_SIZE_BYTES: Long = 50L * 1024L * 1024L // 50 MB

        /** UD-304: swift-core parity — concurrent part uploads per file. */
        const val MAX_PARALLEL_PARTS: Int = 6
    }
}
