package org.krost.unidrive.s3

import org.krost.unidrive.ConfigurationException
import org.krost.unidrive.CredentialHealth
import java.nio.file.Files
import kotlin.test.*

class S3ProviderFactoryTest {
    private val factory = S3ProviderFactory()

    private fun fullProps(overrides: Map<String, String?> = emptyMap()): Map<String, String?> {
        val base =
            mapOf(
                "bucket" to "my-bucket",
                "access_key_id" to "AKIAIOSFODNN7EXAMPLE",
                "secret_access_key" to "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
            )
        return base + overrides
    }

    // --- create ---

    @Test
    fun `create with all required properties returns S3Provider`() {
        val tokenDir = Files.createTempDirectory("s3-factory-test")
        try {
            val provider = factory.create(fullProps(), tokenDir)
            assertTrue(provider is S3Provider)
        } finally {
            Files.deleteIfExists(tokenDir)
        }
    }

    @Test
    fun `create uses aws endpoint as default when no endpoint specified`() {
        val tokenDir = Files.createTempDirectory("s3-factory-test")
        try {
            val provider = factory.create(fullProps(), tokenDir) as S3Provider
            assertEquals(S3Config.PRESETS["aws"], provider.config.endpoint)
        } finally {
            Files.deleteIfExists(tokenDir)
        }
    }

    @Test
    fun `create uses auto as default region`() {
        val tokenDir = Files.createTempDirectory("s3-factory-test")
        try {
            val provider = factory.create(fullProps(), tokenDir) as S3Provider
            assertEquals("auto", provider.config.region)
        } finally {
            Files.deleteIfExists(tokenDir)
        }
    }

    @Test
    fun `create respects explicit region and endpoint`() {
        val tokenDir = Files.createTempDirectory("s3-factory-test")
        try {
            val props =
                fullProps(
                    mapOf(
                        "region" to "eu-central-1",
                        "endpoint" to "https://s3.eu-central-1.amazonaws.com",
                    ),
                )
            val provider = factory.create(props, tokenDir) as S3Provider
            assertEquals("eu-central-1", provider.config.region)
            assertEquals("https://s3.eu-central-1.amazonaws.com", provider.config.endpoint)
        } finally {
            Files.deleteIfExists(tokenDir)
        }
    }

    @Test
    fun `create throws when bucket is missing`() {
        val tokenDir = Files.createTempDirectory("s3-factory-test")
        try {
            assertFailsWith<ConfigurationException> {
                factory.create(fullProps(mapOf("bucket" to null)), tokenDir)
            }
        } finally {
            Files.deleteIfExists(tokenDir)
        }
    }

    @Test
    fun `create throws when bucket is blank`() {
        val tokenDir = Files.createTempDirectory("s3-factory-test")
        try {
            assertFailsWith<ConfigurationException> {
                factory.create(fullProps(mapOf("bucket" to "  ")), tokenDir)
            }
        } finally {
            Files.deleteIfExists(tokenDir)
        }
    }

    @Test
    fun `create throws when access_key_id is missing`() {
        val tokenDir = Files.createTempDirectory("s3-factory-test")
        try {
            assertFailsWith<ConfigurationException> {
                factory.create(fullProps(mapOf("access_key_id" to null)), tokenDir)
            }
        } finally {
            Files.deleteIfExists(tokenDir)
        }
    }

    @Test
    fun `create throws when secret_access_key is missing`() {
        val tokenDir = Files.createTempDirectory("s3-factory-test")
        try {
            assertFailsWith<ConfigurationException> {
                factory.create(fullProps(mapOf("secret_access_key" to null)), tokenDir)
            }
        } finally {
            Files.deleteIfExists(tokenDir)
        }
    }

    @Test
    fun `create treats blank region as default auto`() {
        val tokenDir = Files.createTempDirectory("s3-factory-test")
        try {
            val provider = factory.create(fullProps(mapOf("region" to "  ")), tokenDir) as S3Provider
            assertEquals("auto", provider.config.region)
        } finally {
            Files.deleteIfExists(tokenDir)
        }
    }

    @Test
    fun `create treats blank endpoint as aws default`() {
        val tokenDir = Files.createTempDirectory("s3-factory-test")
        try {
            val provider = factory.create(fullProps(mapOf("endpoint" to "")), tokenDir) as S3Provider
            assertEquals(S3Config.PRESETS["aws"], provider.config.endpoint)
        } finally {
            Files.deleteIfExists(tokenDir)
        }
    }

    // --- isAuthenticated ---

    @Test
    fun `isAuthenticated true when all credentials present`() {
        val profileDir = Files.createTempDirectory("s3-auth-test")
        try {
            assertTrue(factory.isAuthenticated(fullProps(), profileDir))
        } finally {
            Files.deleteIfExists(profileDir)
        }
    }

    @Test
    fun `isAuthenticated false when bucket missing`() {
        val profileDir = Files.createTempDirectory("s3-auth-test")
        try {
            assertFalse(factory.isAuthenticated(fullProps(mapOf("bucket" to null)), profileDir))
        } finally {
            Files.deleteIfExists(profileDir)
        }
    }

    @Test
    fun `isAuthenticated false when access_key_id blank`() {
        val profileDir = Files.createTempDirectory("s3-auth-test")
        try {
            assertFalse(factory.isAuthenticated(fullProps(mapOf("access_key_id" to "")), profileDir))
        } finally {
            Files.deleteIfExists(profileDir)
        }
    }

    @Test
    fun `isAuthenticated false when secret_access_key missing`() {
        val profileDir = Files.createTempDirectory("s3-auth-test")
        try {
            assertFalse(factory.isAuthenticated(fullProps(mapOf("secret_access_key" to null)), profileDir))
        } finally {
            Files.deleteIfExists(profileDir)
        }
    }

    // --- checkCredentialHealth ---

    @Test
    fun `checkCredentialHealth Ok when all credentials present`() {
        val profileDir = Files.createTempDirectory("s3-health-test")
        try {
            val result = factory.checkCredentialHealth(fullProps(), profileDir)
            assertTrue(result is CredentialHealth.Ok)
        } finally {
            Files.deleteIfExists(profileDir)
        }
    }

    @Test
    fun `checkCredentialHealth Missing lists all missing fields`() {
        val profileDir = Files.createTempDirectory("s3-health-test")
        try {
            val result = factory.checkCredentialHealth(emptyMap(), profileDir)
            assertTrue(result is CredentialHealth.Missing)
            val msg = (result as CredentialHealth.Missing).message
            assertTrue(msg.contains("bucket"))
            assertTrue(msg.contains("access_key_id"))
            assertTrue(msg.contains("secret_access_key"))
        } finally {
            Files.deleteIfExists(profileDir)
        }
    }

    @Test
    fun `checkCredentialHealth Missing when only bucket provided`() {
        val profileDir = Files.createTempDirectory("s3-health-test")
        try {
            val result = factory.checkCredentialHealth(mapOf("bucket" to "b"), profileDir)
            assertTrue(result is CredentialHealth.Missing)
            val msg = (result as CredentialHealth.Missing).message
            assertFalse(msg.contains("bucket"))
            assertTrue(msg.contains("access_key_id"))
            assertTrue(msg.contains("secret_access_key"))
        } finally {
            Files.deleteIfExists(profileDir)
        }
    }

    // --- describeConnection ---

    @Test
    fun `describeConnection includes bucket region endpoint and masked key`() {
        val profileDir = Files.createTempDirectory("s3-desc-test")
        try {
            val desc =
                factory.describeConnection(
                    fullProps(
                        mapOf(
                            "region" to "eu-central-1",
                            "endpoint" to "https://s3.hetzner.com",
                        ),
                    ),
                    profileDir,
                )
            assertTrue(desc.contains("bucket=my-bucket"))
            assertTrue(desc.contains("region=eu-central-1"))
            assertTrue(desc.contains("endpoint=https://s3.hetzner.com"))
            assertTrue(desc.contains("key=AKIA..."))
            assertTrue(desc.startsWith("s3"))
        } finally {
            Files.deleteIfExists(profileDir)
        }
    }

    @Test
    fun `describeConnection returns fallback when no properties`() {
        val profileDir = Files.createTempDirectory("s3-desc-test")
        try {
            val desc = factory.describeConnection(emptyMap(), profileDir)
            assertEquals("s3 provider", desc)
        } finally {
            Files.deleteIfExists(profileDir)
        }
    }

    @Test
    fun `describeConnection omits key when shorter than 4 chars`() {
        val profileDir = Files.createTempDirectory("s3-desc-test")
        try {
            val desc = factory.describeConnection(mapOf("access_key_id" to "AB"), profileDir)
            assertFalse(desc.contains("key="))
        } finally {
            Files.deleteIfExists(profileDir)
        }
    }

    // --- metadata ---

    @Test
    fun `metadata has correct id and tier`() {
        assertEquals("s3", factory.metadata.id)
        assertEquals("Global", factory.metadata.tier)
        assertEquals("S3 / S3-compatible", factory.metadata.displayName)
    }

    @Test
    fun `factory id is s3`() {
        assertEquals("s3", factory.id)
    }

    // --- S3Config presets ---

    @Test
    fun `S3Config PRESETS contain all expected providers`() {
        val expected = setOf("aws", "hetzner", "backblaze", "wasabi", "ovh", "minio")
        assertEquals(expected, S3Config.PRESETS.keys)
    }

    @Test
    fun `S3Config fromPreset resolves known preset`() {
        val config =
            S3Config.fromPreset(
                "hetzner",
                bucket = "test",
                region = "auto",
                accessKey = "ak",
                secretKey = "sk",
            )
        assertEquals("https://s3.hetzner.com", config.endpoint)
        assertEquals("test", config.bucket)
        assertEquals("auto", config.region)
    }

    @Test
    fun `S3Config fromPreset throws on unknown preset`() {
        assertFailsWith<IllegalStateException> {
            S3Config.fromPreset(
                "nonexistent",
                bucket = "b",
                region = "r",
                accessKey = "ak",
                secretKey = "sk",
            )
        }
    }
}
