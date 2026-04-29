package org.krost.unidrive.s3

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Configuration for an S3 or S3-compatible provider.
 *
 * Credentials are read from [accessKey] / [secretKey] (constructor args) or from the
 * standard environment variables AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY when the
 * constructor args are blank.
 *
 * [endpoint] is the full base URL including scheme, e.g.
 *   "https://s3.amazonaws.com"          (AWS)
 *   "https://s3.hetzner.com"            (Hetzner Object Storage)
 *   "https://s3.us-west-001.backblazeb2.com" (Backblaze B2)
 *   "https://s3.eu-central-003.backblazeb2.com" (Backblaze B2 EU)
 *   "https://s3.wasabisys.com"          (Wasabi)
 *   "http://localhost:9000"             (MinIO local)
 *
 * [region] is required for AWS SigV4; for S3-compatible endpoints that don't use
 * region-specific endpoints (Hetzner, Wasabi) pass "auto" or any non-empty string.
 */
data class S3Config(
    val bucket: String,
    val region: String,
    val endpoint: String,
    val accessKey: String = System.getenv("AWS_ACCESS_KEY_ID") ?: "",
    val secretKey: String = System.getenv("AWS_SECRET_ACCESS_KEY") ?: "",
    val tokenPath: Path = defaultTokenPath(),
) {
    companion object {
        /** Known preset endpoints keyed by short name. */
        val PRESETS: Map<String, String> =
            mapOf(
                "aws" to "https://s3.amazonaws.com",
                "hetzner" to "https://s3.hetzner.com",
                "backblaze" to "https://s3.us-west-001.backblazeb2.com",
                "wasabi" to "https://s3.wasabisys.com",
                "ovh" to "https://s3.gra.io.cloud.ovh.net",
                "minio" to "http://localhost:9000",
            )

        fun fromPreset(
            preset: String,
            bucket: String,
            region: String,
            accessKey: String = System.getenv("AWS_ACCESS_KEY_ID") ?: "",
            secretKey: String = System.getenv("AWS_SECRET_ACCESS_KEY") ?: "",
        ): S3Config =
            S3Config(
                bucket = bucket,
                region = region,
                endpoint = PRESETS[preset] ?: error("Unknown S3 preset: $preset"),
                accessKey = accessKey,
                secretKey = secretKey,
            )

        fun defaultTokenPath(): Path {
            val home = System.getenv("HOME") ?: System.getProperty("user.home")
            return Paths.get(home, ".config", "unidrive", "s3")
        }
    }
}
