package org.krost.unidrive.internxt

import org.krost.unidrive.io.defaultTokenPath
import java.nio.file.Path

data class InternxtConfig(
    val tokenPath: Path = defaultTokenPath("internxt"),
    // The three client-identification headers the Internxt gateway echoes into
    // its allow-list checks. Defaults preserve the current wire shape; env-var
    // overrides let an operator rotate them without rebuilding when (not if)
    // upstream tightens the allowlist. Mirrors the CRYPTO_KEY override pattern.
    val clientName: String = System.getenv("INTERNXT_CLIENT_NAME") ?: CLIENT_NAME,
    val clientVersion: String = System.getenv("INTERNXT_CLIENT_VERSION") ?: CLIENT_VERSION,
    val desktopHeader: String = System.getenv("INTERNXT_DESKTOP_HEADER") ?: DESKTOP_HEADER,
    // Socket.io endpoint for the Internxt change feed. Default is the best-guess
    // hostname per Internxt's naming convention for sibling envvars (BRIDGE_URL,
    // DRIVE_URL, PAYMENTS_URL); the actual production value is supplied at
    // build time by drive-desktop's CI pipeline and isn't in the public repo.
    // Override via INTERNXT_NOTIFICATIONS_URL once confirmed via mitmproxy.
    val notificationsUrl: String = System.getenv("INTERNXT_NOTIFICATIONS_URL") ?: NOTIFICATIONS_URL,
    // Opt-in destructive-overwrite guard. When true, every `replaceFile`
    // first renames the prior file to `${plainName}.unidrive-prev-${utcStamp}`
    // and creates the new content as a fresh file. Pure recovery insurance
    // because Internxt has no server-side versioning. Default off; storage
    // cost doubles per edit with no automatic prune.
    val keepOverwritten: Boolean = false,
) {
    companion object {
        const val API_BASE_URL = "https://gateway.internxt.com/drive"
        const val CLIENT_NAME = "unidrive"
        const val CLIENT_VERSION = "0.0.1"
        const val DESKTOP_HEADER = "internxt-desktop-dev-header"
        const val NOTIFICATIONS_URL = "https://notifications.internxt.com"

        // Public encryption salt from Internxt's open-source desktop client (not a secret).
        // Used in password hashing during auth. Override via env var for custom deployments.
        val CRYPTO_KEY: String = System.getenv("INTERNXT_CRYPTO_KEY") ?: "6KYQBP847D4ATSFA"

        /** Default page size for /files and /folders pagination (no public maximum documented). */
        const val LISTING_PAGE_SIZE: Int = 50

        /** UD-304: swift-core parity — files at/above this size use multipart upload. */
        const val MULTIPART_MIN_SIZE_BYTES: Long = 100L * 1024L * 1024L // 100 MB

        /** UD-304: swift-core parity — each multipart chunk size. */
        const val MULTIPART_CHUNK_SIZE_BYTES: Long = 50L * 1024L * 1024L // 50 MB

        /** UD-304: swift-core parity — concurrent part uploads per file. */
        const val MAX_PARALLEL_PARTS: Int = 6

        /**
         * Overall freshness window for a chunk-tombstone resume. A tombstone
         * whose `started_at_millis` floor is older than this is discarded
         * on the next `upload()` call and the upload runs cold (fresh
         * indexBytes, fresh encrypt). 7 days mirrors what an OS-level
         * `/tmp` sweep would do to an orphaned ciphertext file.
         */
        const val RESUME_TTL_MS: Long = 7L * 24L * 60L * 60L * 1000L

        /**
         * Freshness window for a cached presigned Bridge PUT URL. The
         * Internxt README quotes "~15 min" for OVH presigned URLs;
         * 10 min buys a 5-min safety margin so a resume that races the
         * TTL re-issues `startUpload` (new shardUuid + URL) rather than
         * burning the PUT call on a 403. The ciphertext + indexBytes +
         * hashHex stay pinned across the URL refresh.
         */
        const val URL_TTL_MS: Long = 10L * 60L * 1000L
    }
}
