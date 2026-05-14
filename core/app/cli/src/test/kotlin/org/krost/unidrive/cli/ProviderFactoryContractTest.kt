package org.krost.unidrive.cli

import org.krost.unidrive.ProviderRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the SPI capability surface each in-tree ProviderFactory exposes.
 * If a provider's credentialPrompts / envVarMappings /
 * supportsInteractiveAuth changes shape, this test fails loudly so
 * the change is deliberate.
 *
 * Lives in `:app:cli` test scope (not `:app:core`) because the
 * ServiceLoader-discovered providers are only on the classpath here —
 * `:app:core` has no provider deps, so `ProviderRegistry.get(...)`
 * returns null in that test scope.
 */
class ProviderFactoryContractTest {
    @Test
    fun `s3 credential prompts schema`() {
        val factory = ProviderRegistry.get("s3")!!
        val keys = factory.credentialPrompts().map { it.key }
        assertEquals(
            listOf("bucket", "region", "endpoint", "access_key_id", "secret_access_key"),
            keys,
        )
        // The secret-key prompt MUST be masked.
        val secret = factory.credentialPrompts().single { it.key == "secret_access_key" }
        assertTrue(secret.isMasked, "secret_access_key prompt must be masked")
    }

    @Test
    fun `s3 env-var mappings schema`() {
        val factory = ProviderRegistry.get("s3")!!
        assertEquals(
            mapOf(
                "S3_BUCKET" to "bucket",
                "AWS_ACCESS_KEY_ID" to "access_key_id",
                "AWS_SECRET_ACCESS_KEY" to "secret_access_key",
            ),
            factory.envVarMappings(),
        )
    }

    @Test
    fun `sftp credential prompts schema`() {
        val factory = ProviderRegistry.get("sftp")!!
        val keys = factory.credentialPrompts().map { it.key }
        assertEquals(listOf("host", "port", "user", "remote_path", "identity"), keys)
    }

    @Test
    fun `webdav credential prompts schema`() {
        val factory = ProviderRegistry.get("webdav")!!
        val keys = factory.credentialPrompts().map { it.key }
        assertEquals(listOf("url", "user", "password"), keys)
        val pwd = factory.credentialPrompts().single { it.key == "password" }
        assertTrue(pwd.isMasked, "password prompt must be masked")
    }

    @Test
    fun `onedrive supports interactive auth`() {
        val factory = ProviderRegistry.get("onedrive")!!
        assertTrue(factory.supportsInteractiveAuth(), "OneDrive must declare interactive auth support")
    }

    @Test
    fun `localfs sftp s3 webdav rclone do not declare interactive auth`() {
        for (id in listOf("localfs", "sftp", "s3", "webdav", "rclone")) {
            val factory = ProviderRegistry.get(id)!!
            assertEquals(
                false,
                factory.supportsInteractiveAuth(),
                "$id must NOT declare interactive auth (config-driven only)",
            )
        }
    }

    @Test
    fun `rclone env-var mapping schema`() {
        val factory = ProviderRegistry.get("rclone")!!
        assertEquals(mapOf("RCLONE_REMOTE" to "rclone_remote"), factory.envVarMappings())
    }

    @Test
    fun `localfs has no provider-specific prompts or env vars`() {
        val factory = ProviderRegistry.get("localfs")!!
        assertEquals(emptyList(), factory.credentialPrompts())
        assertEquals(emptyMap(), factory.envVarMappings())
    }
}
