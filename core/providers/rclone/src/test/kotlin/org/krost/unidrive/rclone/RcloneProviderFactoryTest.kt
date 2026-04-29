package org.krost.unidrive.rclone

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression guard for the colon-normalisation fix: rclone's path-syntax is
 * `remote:path`, but a TOML author naturally writes `rclone_remote = "minio"`
 * (matching the `[minio]` section header in `rclone.conf`). The factory has
 * to smuggle the colon in before construction, otherwise
 * `RcloneCliService.remotePath` produces `miniounidrive-test` and every
 * command exits with "directory not found".
 *
 * Originally discovered via the docker provider contract harness (UD-712).
 */
class RcloneProviderFactoryTest {
    private val factory = RcloneProviderFactory()

    // `config` is `private` on RcloneProvider; reflection is cheaper than
    // widening visibility just for these two assertions.
    private fun configOf(provider: RcloneProvider): RcloneConfig {
        val f = RcloneProvider::class.java.getDeclaredField("config")
        f.isAccessible = true
        return f.get(provider) as RcloneConfig
    }

    @Test
    fun `create normalises rclone_remote without trailing colon`() {
        val tokenDir = Files.createTempDirectory("rclone-factory-test")
        try {
            val provider =
                factory.create(
                    mapOf("rclone_remote" to "minio", "rclone_path" to "bucket"),
                    tokenDir,
                ) as RcloneProvider
            // RcloneCliService concatenates `config.remote` + combined path
            // directly, so the colon has to end up on `config.remote`.
            val config = configOf(provider)
            assertEquals("minio:", config.remote)
            assertEquals("bucket", config.path)
        } finally {
            Files.deleteIfExists(tokenDir)
        }
    }

    @Test
    fun `create preserves rclone_remote that already ends with colon`() {
        val tokenDir = Files.createTempDirectory("rclone-factory-test")
        try {
            val provider =
                factory.create(
                    mapOf("rclone_remote" to "minio:", "rclone_path" to ""),
                    tokenDir,
                ) as RcloneProvider
            assertEquals("minio:", configOf(provider).remote)
        } finally {
            Files.deleteIfExists(tokenDir)
        }
    }
}
