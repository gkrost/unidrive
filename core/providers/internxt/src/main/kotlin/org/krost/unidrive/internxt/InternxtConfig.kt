package org.krost.unidrive.internxt

import org.krost.unidrive.io.defaultTokenPath
import java.nio.file.Path

data class InternxtConfig(
    val tokenPath: Path = defaultTokenPath("internxt"),
    val clientName: String = System.getenv("INTERNXT_CLIENT_NAME") ?: CLIENT_NAME,
    val clientVersion: String = System.getenv("INTERNXT_CLIENT_VERSION") ?: CLIENT_VERSION,
    // Optional in Internxt's own SDK and sent only when set. Not sent by default: unidrive
    // has no such token and does not invent one. INTERNXT_DESKTOP_HEADER overrides for testing.
    val desktopHeader: String? = System.getenv("INTERNXT_DESKTOP_HEADER")?.takeIf { it.isNotBlank() },
    val notificationsUrl: String = System.getenv("INTERNXT_NOTIFICATIONS_URL") ?: NOTIFICATIONS_URL,
    val keepOverwritten: Boolean = false,
    // INTERNXT_NOTIFICATIONS=off (also 0 / false) skips the socket.io wake-signal client;
    // sync then relies on the adaptive poll alone. Anything else, or unset, keeps it on.
    val notificationsEnabled: Boolean = parseNotificationsEnabled(System.getenv("INTERNXT_NOTIFICATIONS")),
) {
    companion object {
        internal fun parseNotificationsEnabled(raw: String?): Boolean = raw?.trim()?.lowercase() !in setOf("off", "0", "false")

        const val API_BASE_URL = "https://gateway.internxt.com/drive"
        const val CLIENT_NAME = "unidrive"
        const val CLIENT_VERSION = "0.0.1"
        const val NOTIFICATIONS_URL = "https://notifications.internxt.com"

        // Public encryption salt from Internxt's open-source desktop client (not a secret).
        // Used in password hashing during auth. Override via env var for custom deployments.
        val CRYPTO_KEY: String = System.getenv("INTERNXT_CRYPTO_KEY") ?: "6KYQBP847D4ATSFA"

        /** Default page size for /files and /folders pagination (no public maximum documented). */
        const val LISTING_PAGE_SIZE: Int = 999

        /**
         * Socket (read-idle) and request timeouts of the listings whose cost grows with what they list: the
         * account-wide `/files` and `/folders`, a page of which needs from 25 s to more than a minute on a large
         * account, and a folder's `/folders/content/{uuid}`, which needed 106 s for a flat folder of 29,000 files
         * (31 MB). That is far beyond the 60 s every other call runs with. These are upper bounds: the gateway ends a
         * request that took about two minutes with its own 524, and the server's public configuration sets a database
         * `statement_timeout` of 300 s, so both answer before this timer fires.
         */
        const val LISTING_SOCKET_TIMEOUT_MS: Long = 330_000L
        const val LISTING_REQUEST_TIMEOUT_MS: Long = 360_000L

        /** swift-core parity — files at/above this size use multipart upload. */
        const val MULTIPART_MIN_SIZE_BYTES: Long = 100L * 1024L * 1024L // 100 MB

        /** swift-core parity — each multipart chunk size. */
        const val MULTIPART_CHUNK_SIZE_BYTES: Long = 50L * 1024L * 1024L // 50 MB

        /** swift-core parity — concurrent part uploads per file. */
        const val MAX_PARALLEL_PARTS: Int = 6

        const val RESUME_TTL_MS: Long = 7L * 24L * 60L * 60L * 1000L

        const val URL_TTL_MS: Long = 10L * 60L * 1000L
    }
}
