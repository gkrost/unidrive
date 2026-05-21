package org.krost.unidrive.sync

import java.nio.file.Files
import javax.crypto.AEADBadTagException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VaultTest {
    private fun tempVaultPath() = Files.createTempFile("vault-test-", ".enc")

    @Test
    fun `init creates vault file`() {
        val path = tempVaultPath()
        Files.deleteIfExists(path)
        val vault = Vault(path)
        assertFalse(vault.exists())
        vault.init("testpassword".toCharArray())
        assertTrue(vault.exists())
        Files.deleteIfExists(path)
    }

    @Test
    fun `read after write round-trips credentials`() {
        val path = tempVaultPath()
        val vault = Vault(path)
        val pass = "my-passphrase!".toCharArray()
        val data =
            mapOf(
                "hetzner-s3" to mapOf("access_key_id" to "AKIA123", "secret_access_key" to "secret"),
                "my-sftp" to mapOf("password" to "hunter2"),
            )
        vault.write(pass, data)
        val read = vault.read("my-passphrase!".toCharArray())
        assertEquals(data, read)
        Files.deleteIfExists(path)
    }

    @Test
    fun `wrong passphrase throws AEADBadTagException`() {
        val path = tempVaultPath()
        val vault = Vault(path)
        vault.write("correct-pass".toCharArray(), mapOf("p" to mapOf("k" to "v")))
        assertFails {
            vault.read("wrong-pass!".toCharArray())
        }.also { e ->
            assertTrue(
                e is AEADBadTagException || e.cause is AEADBadTagException,
                "Expected AEADBadTagException but got ${e::class.simpleName}",
            )
        }
        Files.deleteIfExists(path)
    }

    @Test
    fun `empty vault round-trips to empty map`() {
        val path = tempVaultPath()
        val vault = Vault(path)
        val pass = "testpassword".toCharArray()
        vault.init(pass)
        val data = vault.read("testpassword".toCharArray())
        assertEquals(emptyMap(), data)
        Files.deleteIfExists(path)
    }

    @Test
    fun `multiple profiles coexist`() {
        val path = tempVaultPath()
        val vault = Vault(path)
        val pass = "longpassphrase".toCharArray()
        val data =
            mapOf(
                "profile-a" to mapOf("key1" to "val1"),
                "profile-b" to mapOf("key2" to "val2", "key3" to "val3"),
                "profile-c" to mapOf("password" to "abc"),
            )
        vault.write(pass, data)
        assertEquals(data, vault.read("longpassphrase".toCharArray()))
        Files.deleteIfExists(path)
    }

    @Test
    fun `delete removes file`() {
        val path = tempVaultPath()
        val vault = Vault(path)
        vault.init("testpassword".toCharArray())
        assertTrue(vault.exists())
        vault.delete()
        assertFalse(vault.exists())
    }

    @Test
    fun `minimum passphrase length enforced`() {
        val path = tempVaultPath()
        Files.deleteIfExists(path)
        val vault = Vault(path)
        assertFails {
            vault.init("short".toCharArray())
        }.also { e ->
            assertTrue(e is IllegalArgumentException)
            assertTrue(e.message!!.contains("at least"))
        }
        Files.deleteIfExists(path)
    }

    @Test
    fun `overwrite vault with new data`() {
        val path = tempVaultPath()
        val vault = Vault(path)
        val pass = "testpassword".toCharArray()

        vault.write(pass, mapOf("old" to mapOf("k" to "v")))
        vault.write("testpassword".toCharArray(), mapOf("new" to mapOf("k2" to "v2")))

        val read = vault.read("testpassword".toCharArray())
        assertEquals(mapOf("new" to mapOf("k2" to "v2")), read)
        assertFalse(read.containsKey("old"))
        Files.deleteIfExists(path)
    }
}
