package org.krost.unidrive.cli

import org.krost.unidrive.HashAlgorithm
import org.krost.unidrive.ProviderRegistry
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Pins the runtime SPI capability shape each provider exposes.
 * Verifies overrides differ from the default where expected
 * (otherwise the override is dead).
 *
 * Lives in `:app:cli` test scope (not `:app:core`) because the
 * ServiceLoader-discovered providers are only on the classpath here.
 *
 * Required `properties` keys per provider (for `create()` to succeed
 * without network calls):
 *   - onedrive: none required (auth is via token.json, absent here)
 *   - s3: bucket, access_key_id, secret_access_key
 *   - localfs: root_path (must point to an existing directory)
 *   - webdav: url, user, password
 */
class CloudProviderContractTest {
    @Test
    fun `onedrive declares QuickXor hash algorithm`() {
        val tmp = Files.createTempDirectory("onedrive-contract")
        try {
            val provider =
                ProviderRegistry.get("onedrive")!!.create(
                    properties = mapOf("client_id" to "test-client"),
                    tokenPath = tmp,
                )
            assertEquals(HashAlgorithm.QuickXor, provider.hashAlgorithm())
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun `s3 declares Md5Hex hash algorithm`() {
        val tmp = Files.createTempDirectory("s3-contract")
        try {
            val provider =
                ProviderRegistry.get("s3")!!.create(
                    properties =
                        mapOf(
                            "bucket" to "test-bucket",
                            "access_key_id" to "AKIA",
                            "secret_access_key" to "secret",
                        ),
                    tokenPath = tmp,
                )
            assertEquals(HashAlgorithm.Md5Hex, provider.hashAlgorithm())
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun `localfs has null hash algorithm (no integrity check)`() {
        val tmp = Files.createTempDirectory("localfs-contract")
        try {
            val provider =
                ProviderRegistry.get("localfs")!!.create(
                    properties = mapOf("root_path" to tmp.toString()),
                    tokenPath = tmp,
                )
            assertNull(provider.hashAlgorithm(), "localfs must declare null algorithm")
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun `webdav transport warning fires only above 50 GiB`() {
        val tmp = Files.createTempDirectory("webdav-contract")
        try {
            val provider =
                ProviderRegistry.get("webdav")!!.create(
                    properties =
                        mapOf(
                            "url" to "https://example.invalid/dav",
                            "user" to "u",
                            "password" to "p",
                        ),
                    tokenPath = tmp,
                )
            // Below threshold: no warning.
            assertNull(provider.transportWarning(10L * 1024 * 1024 * 1024))
            // Above threshold: warning present.
            val warning = provider.transportWarning(60L * 1024 * 1024 * 1024)
            assertNotNull(warning)
            assertEquals(true, warning.contains("nginx-mod_dav"))
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun `s3 has no transport warning for any plan size`() {
        val tmp = Files.createTempDirectory("s3-transport")
        try {
            val provider =
                ProviderRegistry.get("s3")!!.create(
                    properties =
                        mapOf(
                            "bucket" to "test-bucket",
                            "access_key_id" to "AKIA",
                            "secret_access_key" to "secret",
                        ),
                    tokenPath = tmp,
                )
            assertNull(provider.transportWarning(1L * 1024 * 1024 * 1024 * 1024)) // 1 TiB
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }
}
