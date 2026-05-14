package org.krost.unidrive.e2e.scenarios

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * UD-802 regression guard: bare provider types (e.g. `groundtruth -p s3`) MUST
 * synthesize a populated `[providers.<type>]` block in the temp config so the
 * child unidrive process reaches the sync phase instead of exiting at
 * `Main.resolveCurrentProfile()` with "config missing".
 *
 * The previous code path set `configContent = ""` and skipped writing
 * config.toml — the child then saw an empty providers map and exited at
 * Phase 1. This test exercises the replacement helper via the env-injection
 * seam (no real environment dependence) and asserts the contract per
 * provider type.
 */
class GroundTruthBareProviderConfigTest {
    private val syncRoot = Paths.get("/tmp/ud802-test-root")

    /**
     * `buildBareProviderConfig` doubles every backslash in the syncRoot
     * before embedding it in the TOML (because TOML basic strings escape
     * `\`). On Linux, `Paths.get("/tmp/...").toString()` is
     * `/tmp/...` — no backslashes — so the doubling is a no-op. On
     * Windows, the same call returns `\tmp\...` and the production code
     * emits `\\tmp\\...` in the TOML. Tests must apply the same escape
     * before substring-matching, otherwise the assertion is OS-coupled.
     */
    private val expectedSyncRootInToml = syncRoot.toString().replace("\\", "\\\\")

    @Test
    fun `s3 config inlines bucket and credentials from env`() {
        val env =
            mapOf(
                "S3_BUCKET" to "my-bucket",
                "AWS_ACCESS_KEY_ID" to "AKIAIOSFODNN7EXAMPLE",
                "AWS_SECRET_ACCESS_KEY" to "secretsecret",
                "S3_REGION" to "eu-central-1",
                "S3_ENDPOINT" to "https://s3.example.com",
            )
        val toml = GroundTruthRunner.buildBareProviderConfig("s3", syncRoot, env::get)

        assertTrue("[providers.s3]" in toml)
        assertTrue("type = \"s3\"" in toml)
        assertTrue("sync_root = \"$expectedSyncRootInToml\"" in toml)
        assertTrue("bucket = \"my-bucket\"" in toml)
        assertTrue("region = \"eu-central-1\"" in toml)
        assertTrue("endpoint = \"https://s3.example.com\"" in toml)
        assertTrue("access_key_id = \"AKIAIOSFODNN7EXAMPLE\"" in toml)
        assertTrue("secret_access_key = \"secretsecret\"" in toml)
    }

    @Test
    fun `s3 config defaults region to auto and endpoint to aws when env unset`() {
        val env =
            mapOf(
                "S3_BUCKET" to "b",
                "AWS_ACCESS_KEY_ID" to "ak",
                "AWS_SECRET_ACCESS_KEY" to "sk",
            )
        val toml = GroundTruthRunner.buildBareProviderConfig("s3", syncRoot, env::get)

        assertTrue("region = \"auto\"" in toml)
        assertTrue("endpoint = \"https://s3.amazonaws.com\"" in toml)
    }

    @Test
    fun `s3 config throws when required env var missing`() {
        val env = mapOf("S3_BUCKET" to "b") // missing access keys
        val ex =
            assertFailsWith<IllegalStateException> {
                GroundTruthRunner.buildBareProviderConfig("s3", syncRoot, env::get)
            }
        assertTrue("AWS_ACCESS_KEY_ID" in ex.message!!)
    }

    @Test
    fun `sftp config inlines host and optional password`() {
        val env =
            mapOf(
                "SFTP_HOST" to "sftp.example.com",
                "SFTP_USER" to "alice",
                "SFTP_PASSWORD" to "p@ss",
            )
        val toml = GroundTruthRunner.buildBareProviderConfig("sftp", syncRoot, env::get)

        assertTrue("[providers.sftp]" in toml)
        assertTrue("host = \"sftp.example.com\"" in toml)
        assertTrue("user = \"alice\"" in toml)
        assertTrue("password = \"p@ss\"" in toml)
    }

    @Test
    fun `sftp config omits password when env unset`() {
        val env = mapOf("SFTP_HOST" to "h", "SFTP_USER" to "u")
        val toml = GroundTruthRunner.buildBareProviderConfig("sftp", syncRoot, env::get)

        assertTrue("host = \"h\"" in toml)
        assertFalse("password" in toml, "no password should be emitted when SFTP_PASSWORD unset; saw: $toml")
    }

    @Test
    fun `webdav config inlines url user password`() {
        val env =
            mapOf(
                "WEBDAV_URL" to "https://dav.example.com/remote.php/dav",
                "WEBDAV_USER" to "alice",
                "WEBDAV_PASSWORD" to "hunter2",
            )
        val toml = GroundTruthRunner.buildBareProviderConfig("webdav", syncRoot, env::get)

        assertTrue("[providers.webdav]" in toml)
        assertTrue("url = \"https://dav.example.com/remote.php/dav\"" in toml)
        assertTrue("user = \"alice\"" in toml)
        assertTrue("password = \"hunter2\"" in toml)
    }

    @Test
    fun `webdav config throws when any required env var missing`() {
        for (missing in listOf("WEBDAV_URL", "WEBDAV_USER", "WEBDAV_PASSWORD")) {
            val env =
                mutableMapOf(
                    "WEBDAV_URL" to "https://x",
                    "WEBDAV_USER" to "u",
                    "WEBDAV_PASSWORD" to "p",
                )
            env.remove(missing)
            val ex =
                assertFailsWith<IllegalStateException>("missing $missing should throw") {
                    GroundTruthRunner.buildBareProviderConfig("webdav", syncRoot, env::get)
                }
            assertTrue(missing in ex.message!!, "error should name '$missing'; got: ${ex.message}")
        }
    }

    @Test
    fun `rclone config inlines remote from env`() {
        val env = mapOf("RCLONE_REMOTE" to "my-remote:bucket")
        val toml = GroundTruthRunner.buildBareProviderConfig("rclone", syncRoot, env::get)

        assertTrue("[providers.rclone]" in toml)
        assertTrue("rclone_remote = \"my-remote:bucket\"" in toml)
    }

    @Test
    fun `oauth-only providers emit type and sync_root rows without env`() {
        for (type in listOf("onedrive", "hidrive", "internxt")) {
            val toml = GroundTruthRunner.buildBareProviderConfig(type, syncRoot, { null })
            assertTrue("[providers.$type]" in toml, "missing section for $type: $toml")
            assertTrue("type = \"$type\"" in toml)
            assertTrue("sync_root" in toml)
        }
    }

    @Test
    fun `config string is non-empty for every bare provider type`() {
        val knownTypes = setOf("onedrive", "hidrive", "internxt", "rclone", "s3", "sftp", "webdav")
        val fullEnv =
            mapOf(
                "S3_BUCKET" to "b", "AWS_ACCESS_KEY_ID" to "ak", "AWS_SECRET_ACCESS_KEY" to "sk",
                "SFTP_HOST" to "h",
                "WEBDAV_URL" to "u", "WEBDAV_USER" to "uu", "WEBDAV_PASSWORD" to "pp",
                "RCLONE_REMOTE" to "r",
            )
        for (type in knownTypes) {
            val toml = GroundTruthRunner.buildBareProviderConfig(type, syncRoot, fullEnv::get)
            assertTrue(
                toml.isNotEmpty() && "[providers.$type]" in toml,
                "bare type '$type' produced empty/wrong config: '$toml'",
            )
        }
    }

    @Test
    fun `unknown bare type throws with actionable error`() {
        val ex =
            assertFailsWith<IllegalStateException> {
                GroundTruthRunner.buildBareProviderConfig("dropbox", syncRoot, { null })
            }
        assertTrue("dropbox" in ex.message!!)
    }

    @Test
    fun `sync_root path is correctly quoted on backslash-containing paths`() {
        // Windows-style path with backslashes — TOML requires them escaped.
        val winRoot = Paths.get("C:\\Users\\test\\sync")
        val env = mapOf("RCLONE_REMOTE" to "r")
        val toml = GroundTruthRunner.buildBareProviderConfig("rclone", winRoot, env::get)
        // Each backslash in the input must appear as TWO characters ("\\")
        // in the emitted TOML. The input path has 3 backslashes, so the
        // emitted sync_root segment must contain 6 backslash characters.
        val syncRootLine = toml.lineSequence().first { it.startsWith("sync_root") }
        val backslashCount = syncRootLine.count { it == '\\' }
        assertEquals(6, backslashCount, "expected 6 backslash chars in sync_root line; got: '$syncRootLine'")
    }
}
