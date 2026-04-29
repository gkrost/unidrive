package org.krost.unidrive.webdav

import org.krost.unidrive.ConfigurationException
import org.krost.unidrive.CredentialHealth
import java.nio.file.Files
import kotlin.test.*

class WebDavProviderFactoryTest {
    private val factory = WebDavProviderFactory()

    private fun fullProps(overrides: Map<String, String?> = emptyMap()): Map<String, String?> {
        val base =
            mapOf(
                "url" to "https://dav.example.com/webdav",
                "user" to "alice",
                "password" to "secret123",
            )
        return base + overrides
    }

    // --- id & metadata ---

    @Test
    fun `factory id is webdav`() {
        assertEquals("webdav", factory.id)
    }

    @Test
    fun `metadata has correct id and display name`() {
        assertEquals("webdav", factory.metadata.id)
        assertEquals("WebDAV", factory.metadata.displayName)
        assertEquals("Self-hosted", factory.metadata.tier)
        assertTrue(factory.metadata.gdprCompliant)
        assertFalse(factory.metadata.cloudActExposure)
        assertNull(factory.metadata.signupUrl)
    }

    // --- create ---

    @Test
    fun `create with all required properties returns WebDavProvider`() {
        val tokenDir = Files.createTempDirectory("webdav-factory-test")
        try {
            val provider = factory.create(fullProps(), tokenDir)
            assertTrue(provider is WebDavProvider)
        } finally {
            Files.deleteIfExists(tokenDir)
        }
    }

    @Test
    fun `create passes config values through to provider`() {
        val tokenDir = Files.createTempDirectory("webdav-factory-test")
        try {
            val provider = factory.create(fullProps(), tokenDir) as WebDavProvider
            assertEquals("https://dav.example.com/webdav", provider.config.baseUrl)
            assertEquals("alice", provider.config.username)
            assertEquals("secret123", provider.config.password)
            assertEquals(tokenDir, provider.config.tokenPath)
        } finally {
            Files.deleteIfExists(tokenDir)
        }
    }

    @Test
    fun `create throws when url is missing`() {
        val tokenDir = Files.createTempDirectory("webdav-factory-test")
        try {
            val ex =
                assertFailsWith<ConfigurationException> {
                    factory.create(fullProps(mapOf("url" to null)), tokenDir)
                }
            assertEquals("webdav", ex.providerId)
            assertTrue(ex.message.contains("url", ignoreCase = true))
        } finally {
            Files.deleteIfExists(tokenDir)
        }
    }

    @Test
    fun `create throws when url is blank`() {
        val tokenDir = Files.createTempDirectory("webdav-factory-test")
        try {
            assertFailsWith<ConfigurationException> {
                factory.create(fullProps(mapOf("url" to "   ")), tokenDir)
            }
        } finally {
            Files.deleteIfExists(tokenDir)
        }
    }

    @Test
    fun `create throws when user is missing`() {
        val tokenDir = Files.createTempDirectory("webdav-factory-test")
        try {
            val ex =
                assertFailsWith<ConfigurationException> {
                    factory.create(fullProps(mapOf("user" to null)), tokenDir)
                }
            assertTrue(ex.message.contains("user", ignoreCase = true))
        } finally {
            Files.deleteIfExists(tokenDir)
        }
    }

    @Test
    fun `create throws when user is blank`() {
        val tokenDir = Files.createTempDirectory("webdav-factory-test")
        try {
            assertFailsWith<ConfigurationException> {
                factory.create(fullProps(mapOf("user" to "")), tokenDir)
            }
        } finally {
            Files.deleteIfExists(tokenDir)
        }
    }

    @Test
    fun `create throws when password is missing`() {
        val tokenDir = Files.createTempDirectory("webdav-factory-test")
        try {
            val ex =
                assertFailsWith<ConfigurationException> {
                    factory.create(fullProps(mapOf("password" to null)), tokenDir)
                }
            assertTrue(ex.message.contains("password", ignoreCase = true))
        } finally {
            Files.deleteIfExists(tokenDir)
        }
    }

    @Test
    fun `create throws when password is blank`() {
        val tokenDir = Files.createTempDirectory("webdav-factory-test")
        try {
            assertFailsWith<ConfigurationException> {
                factory.create(fullProps(mapOf("password" to "  ")), tokenDir)
            }
        } finally {
            Files.deleteIfExists(tokenDir)
        }
    }

    // --- trust_all_certs ---

    @Test
    fun `create defaults trust_all_certs to false for public URL`() {
        val tokenDir = Files.createTempDirectory("webdav-factory-test")
        try {
            val provider = factory.create(fullProps(), tokenDir) as WebDavProvider
            assertFalse(provider.config.trustAllCerts)
        } finally {
            Files.deleteIfExists(tokenDir)
        }
    }

    @Test
    fun `create defaults trust_all_certs to true for LAN URL`() {
        val tokenDir = Files.createTempDirectory("webdav-factory-test")
        try {
            val provider =
                factory.create(
                    fullProps(mapOf("url" to "https://192.168.1.100:5006/webdav")),
                    tokenDir,
                ) as WebDavProvider
            assertTrue(provider.config.trustAllCerts)
        } finally {
            Files.deleteIfExists(tokenDir)
        }
    }

    @Test
    fun `create respects explicit trust_all_certs true`() {
        val tokenDir = Files.createTempDirectory("webdav-factory-test")
        try {
            val provider =
                factory.create(
                    fullProps(mapOf("trust_all_certs" to "true")),
                    tokenDir,
                ) as WebDavProvider
            assertTrue(provider.config.trustAllCerts)
        } finally {
            Files.deleteIfExists(tokenDir)
        }
    }

    @Test
    fun `create respects explicit trust_all_certs false on LAN URL`() {
        val tokenDir = Files.createTempDirectory("webdav-factory-test")
        try {
            val provider =
                factory.create(
                    fullProps(
                        mapOf(
                            "url" to "https://192.168.1.100/webdav",
                            "trust_all_certs" to "false",
                        ),
                    ),
                    tokenDir,
                ) as WebDavProvider
            assertFalse(provider.config.trustAllCerts)
        } finally {
            Files.deleteIfExists(tokenDir)
        }
    }

    @Test
    fun `create falls back to LAN detection when trust_all_certs is not a valid boolean`() {
        val tokenDir = Files.createTempDirectory("webdav-factory-test")
        try {
            // "yes" is not a valid strict boolean → toBooleanStrictOrNull() returns null → falls back to isLanUrl
            val provider =
                factory.create(
                    fullProps(mapOf("trust_all_certs" to "yes")),
                    tokenDir,
                ) as WebDavProvider
            // public URL → false
            assertFalse(provider.config.trustAllCerts)
        } finally {
            Files.deleteIfExists(tokenDir)
        }
    }

    // --- isLanUrl (indirectly via create) ---

    @Test
    fun `LAN detection for localhost`() {
        val tokenDir = Files.createTempDirectory("webdav-factory-test")
        try {
            val provider =
                factory.create(
                    fullProps(mapOf("url" to "http://localhost:8080/webdav")),
                    tokenDir,
                ) as WebDavProvider
            assertTrue(provider.config.trustAllCerts)
        } finally {
            Files.deleteIfExists(tokenDir)
        }
    }

    @Test
    fun `LAN detection for 127-x address`() {
        val tokenDir = Files.createTempDirectory("webdav-factory-test")
        try {
            val provider =
                factory.create(
                    fullProps(mapOf("url" to "https://127.0.0.1/dav")),
                    tokenDir,
                ) as WebDavProvider
            assertTrue(provider.config.trustAllCerts)
        } finally {
            Files.deleteIfExists(tokenDir)
        }
    }

    @Test
    fun `LAN detection for 10-x address`() {
        val tokenDir = Files.createTempDirectory("webdav-factory-test")
        try {
            val provider =
                factory.create(
                    fullProps(mapOf("url" to "https://10.0.0.5/dav")),
                    tokenDir,
                ) as WebDavProvider
            assertTrue(provider.config.trustAllCerts)
        } finally {
            Files.deleteIfExists(tokenDir)
        }
    }

    @Test
    fun `LAN detection for 172-16 through 172-31 address`() {
        val tokenDir = Files.createTempDirectory("webdav-factory-test")
        try {
            val provider16 =
                factory.create(
                    fullProps(mapOf("url" to "https://172.16.0.1/dav")),
                    tokenDir,
                ) as WebDavProvider
            assertTrue(provider16.config.trustAllCerts)

            val provider31 =
                factory.create(
                    fullProps(mapOf("url" to "https://172.31.255.255/dav")),
                    tokenDir,
                ) as WebDavProvider
            assertTrue(provider31.config.trustAllCerts)
        } finally {
            Files.deleteIfExists(tokenDir)
        }
    }

    @Test
    fun `LAN detection false for 172-15 and 172-32`() {
        val tokenDir = Files.createTempDirectory("webdav-factory-test")
        try {
            val provider15 =
                factory.create(
                    fullProps(mapOf("url" to "https://172.15.0.1/dav")),
                    tokenDir,
                ) as WebDavProvider
            assertFalse(provider15.config.trustAllCerts)

            val provider32 =
                factory.create(
                    fullProps(mapOf("url" to "https://172.32.0.1/dav")),
                    tokenDir,
                ) as WebDavProvider
            assertFalse(provider32.config.trustAllCerts)
        } finally {
            Files.deleteIfExists(tokenDir)
        }
    }

    @Test
    fun `LAN detection for dot-local hostname`() {
        val tokenDir = Files.createTempDirectory("webdav-factory-test")
        try {
            val provider =
                factory.create(
                    fullProps(mapOf("url" to "https://nas.local/webdav")),
                    tokenDir,
                ) as WebDavProvider
            assertTrue(provider.config.trustAllCerts)
        } finally {
            Files.deleteIfExists(tokenDir)
        }
    }

    // --- isAuthenticated ---

    @Test
    fun `isAuthenticated true when all credentials present`() {
        val profileDir = Files.createTempDirectory("webdav-auth-test")
        try {
            assertTrue(factory.isAuthenticated(fullProps(), profileDir))
        } finally {
            Files.deleteIfExists(profileDir)
        }
    }

    @Test
    fun `isAuthenticated false when url missing`() {
        val profileDir = Files.createTempDirectory("webdav-auth-test")
        try {
            assertFalse(factory.isAuthenticated(fullProps(mapOf("url" to null)), profileDir))
        } finally {
            Files.deleteIfExists(profileDir)
        }
    }

    @Test
    fun `isAuthenticated false when url blank`() {
        val profileDir = Files.createTempDirectory("webdav-auth-test")
        try {
            assertFalse(factory.isAuthenticated(fullProps(mapOf("url" to "")), profileDir))
        } finally {
            Files.deleteIfExists(profileDir)
        }
    }

    @Test
    fun `isAuthenticated false when user missing`() {
        val profileDir = Files.createTempDirectory("webdav-auth-test")
        try {
            assertFalse(factory.isAuthenticated(fullProps(mapOf("user" to null)), profileDir))
        } finally {
            Files.deleteIfExists(profileDir)
        }
    }

    @Test
    fun `isAuthenticated false when password missing`() {
        val profileDir = Files.createTempDirectory("webdav-auth-test")
        try {
            assertFalse(factory.isAuthenticated(fullProps(mapOf("password" to null)), profileDir))
        } finally {
            Files.deleteIfExists(profileDir)
        }
    }

    @Test
    fun `isAuthenticated false when password blank`() {
        val profileDir = Files.createTempDirectory("webdav-auth-test")
        try {
            assertFalse(factory.isAuthenticated(fullProps(mapOf("password" to "  ")), profileDir))
        } finally {
            Files.deleteIfExists(profileDir)
        }
    }

    // --- checkCredentialHealth ---

    @Test
    fun `checkCredentialHealth Ok when all credentials present`() {
        val profileDir = Files.createTempDirectory("webdav-health-test")
        try {
            val result = factory.checkCredentialHealth(fullProps(), profileDir)
            assertTrue(result is CredentialHealth.Ok)
        } finally {
            Files.deleteIfExists(profileDir)
        }
    }

    @Test
    fun `checkCredentialHealth Missing lists all missing fields`() {
        val profileDir = Files.createTempDirectory("webdav-health-test")
        try {
            val result = factory.checkCredentialHealth(emptyMap(), profileDir)
            assertTrue(result is CredentialHealth.Missing)
            val msg = (result as CredentialHealth.Missing).message
            assertTrue(msg.contains("url"))
            assertTrue(msg.contains("user"))
            assertTrue(msg.contains("password"))
        } finally {
            Files.deleteIfExists(profileDir)
        }
    }

    @Test
    fun `checkCredentialHealth Missing when only url provided`() {
        val profileDir = Files.createTempDirectory("webdav-health-test")
        try {
            val result = factory.checkCredentialHealth(mapOf("url" to "https://dav.example.com"), profileDir)
            assertTrue(result is CredentialHealth.Missing)
            val msg = (result as CredentialHealth.Missing).message
            assertFalse(msg.contains("url"))
            assertTrue(msg.contains("user"))
            assertTrue(msg.contains("password"))
        } finally {
            Files.deleteIfExists(profileDir)
        }
    }

    @Test
    fun `checkCredentialHealth Missing when blank values provided`() {
        val profileDir = Files.createTempDirectory("webdav-health-test")
        try {
            val result =
                factory.checkCredentialHealth(
                    mapOf("url" to "", "user" to "  ", "password" to null),
                    profileDir,
                )
            assertTrue(result is CredentialHealth.Missing)
            val msg = (result as CredentialHealth.Missing).message
            assertTrue(msg.contains("url"))
            assertTrue(msg.contains("user"))
            assertTrue(msg.contains("password"))
        } finally {
            Files.deleteIfExists(profileDir)
        }
    }

    // --- describeConnection ---

    @Test
    fun `describeConnection includes url and user`() {
        val profileDir = Files.createTempDirectory("webdav-desc-test")
        try {
            val desc = factory.describeConnection(fullProps(), profileDir)
            assertTrue(desc.startsWith("webdav"))
            assertTrue(desc.contains("https://dav.example.com/webdav"))
            assertTrue(desc.contains("user=alice"))
            assertTrue(desc.contains("password=****"))
        } finally {
            Files.deleteIfExists(profileDir)
        }
    }

    @Test
    fun `describeConnection returns fallback when no properties`() {
        val profileDir = Files.createTempDirectory("webdav-desc-test")
        try {
            val desc = factory.describeConnection(emptyMap(), profileDir)
            assertEquals("webdav provider", desc)
        } finally {
            Files.deleteIfExists(profileDir)
        }
    }

    @Test
    fun `describeConnection omits password marker when password not set`() {
        val profileDir = Files.createTempDirectory("webdav-desc-test")
        try {
            val desc =
                factory.describeConnection(
                    mapOf("url" to "https://dav.example.com", "user" to "bob"),
                    profileDir,
                )
            assertTrue(desc.contains("user=bob"))
            assertFalse(desc.contains("password"))
        } finally {
            Files.deleteIfExists(profileDir)
        }
    }

    @Test
    fun `describeConnection omits blank password`() {
        val profileDir = Files.createTempDirectory("webdav-desc-test")
        try {
            val desc =
                factory.describeConnection(
                    mapOf("url" to "https://dav.example.com", "password" to "  "),
                    profileDir,
                )
            assertFalse(desc.contains("password"))
        } finally {
            Files.deleteIfExists(profileDir)
        }
    }
}
