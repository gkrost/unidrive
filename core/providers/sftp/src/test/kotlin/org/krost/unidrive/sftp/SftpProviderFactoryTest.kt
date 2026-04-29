package org.krost.unidrive.sftp

import org.krost.unidrive.ConfigurationException
import org.krost.unidrive.CredentialHealth
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.*

class SftpProviderFactoryTest {
    private val factory = SftpProviderFactory()
    private val tempDir: Path = Files.createTempDirectory("sftp-factory-test")

    @AfterTest
    fun cleanup() {
        Files.walk(tempDir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    // --- create: host resolution ---

    @Test
    fun `create with valid host returns provider`() {
        val props = mapOf("host" to "example.com")
        val provider = factory.create(props, tempDir)
        assertTrue(provider is SftpProvider)
    }

    @Test
    fun `create with missing host throws ConfigurationException`() {
        assertFailsWith<ConfigurationException> {
            factory.create(emptyMap(), tempDir)
        }
    }

    @Test
    fun `create with blank host throws ConfigurationException`() {
        assertFailsWith<ConfigurationException> {
            factory.create(mapOf("host" to "  "), tempDir)
        }
    }

    @Test
    fun `create with null host throws ConfigurationException`() {
        assertFailsWith<ConfigurationException> {
            factory.create(mapOf("host" to null), tempDir)
        }
    }

    // --- create: port parsing ---

    @Test
    fun `create uses default port 22 when port absent`() {
        val props = mapOf("host" to "example.com")
        val provider = factory.create(props, tempDir) as SftpProvider
        assertEquals(22, provider.config.port)
    }

    @Test
    fun `create parses custom port`() {
        val props = mapOf("host" to "example.com", "port" to "2222")
        val provider = factory.create(props, tempDir) as SftpProvider
        assertEquals(2222, provider.config.port)
    }

    @Test
    fun `create falls back to 22 on non-numeric port`() {
        val props = mapOf("host" to "example.com", "port" to "abc")
        val provider = factory.create(props, tempDir) as SftpProvider
        assertEquals(22, provider.config.port)
    }

    // --- create: user resolution ---

    @Test
    fun `create uses explicit user property`() {
        val props = mapOf("host" to "example.com", "user" to "deploy")
        val provider = factory.create(props, tempDir) as SftpProvider
        assertEquals("deploy", provider.config.username)
    }

    @Test
    fun `create falls back to system user when user blank`() {
        val props = mapOf("host" to "example.com", "user" to "")
        val provider = factory.create(props, tempDir) as SftpProvider
        val expected = System.getProperty("user.name") ?: "root"
        assertEquals(expected, provider.config.username)
    }

    // --- create: remote_path ---

    @Test
    fun `create uses explicit remote_path`() {
        val props = mapOf("host" to "example.com", "remote_path" to "/data/sync")
        val provider = factory.create(props, tempDir) as SftpProvider
        assertEquals("/data/sync", provider.config.remotePath)
    }

    @Test
    fun `create defaults remote_path to empty string`() {
        val props = mapOf("host" to "example.com")
        val provider = factory.create(props, tempDir) as SftpProvider
        assertEquals("", provider.config.remotePath)
    }

    // --- create: identity file with tilde expansion ---

    @Test
    fun `create expands tilde in identity path`() {
        val props = mapOf("host" to "example.com", "identity" to "~/.ssh/my_key")
        val provider = factory.create(props, tempDir) as SftpProvider
        val home = System.getenv("HOME") ?: System.getProperty("user.home")
        // Compare Path objects — platform separators differ (\ on Windows, / elsewhere).
        assertEquals(Paths.get(home, ".ssh", "my_key"), provider.config.identityFile)
    }

    @Test
    fun `create uses absolute identity path without expansion`() {
        val props = mapOf("host" to "example.com", "identity" to "/etc/ssh/custom_key")
        val provider = factory.create(props, tempDir) as SftpProvider
        assertEquals(Paths.get("/etc/ssh/custom_key"), provider.config.identityFile)
    }

    @Test
    fun `create uses default identity file when identity blank`() {
        val props = mapOf("host" to "example.com", "identity" to "")
        val provider = factory.create(props, tempDir) as SftpProvider
        assertEquals(SftpConfig.defaultIdentityFile(), provider.config.identityFile)
    }

    // --- create: password ---

    @Test
    fun `create passes password to config`() {
        val props = mapOf("host" to "example.com", "password" to "secret")
        val provider = factory.create(props, tempDir) as SftpProvider
        assertEquals("secret", provider.config.password)
    }

    @Test
    fun `create sets password null when absent`() {
        val props = mapOf("host" to "example.com")
        val provider = factory.create(props, tempDir) as SftpProvider
        assertNull(provider.config.password)
    }

    // --- create: max_concurrency ---

    @Test
    fun `create parses max_concurrency`() {
        val props = mapOf("host" to "example.com", "max_concurrency" to "8")
        val provider = factory.create(props, tempDir) as SftpProvider
        assertEquals(8, provider.config.maxConcurrency)
    }

    @Test
    fun `create defaults max_concurrency to 4`() {
        val props = mapOf("host" to "example.com")
        val provider = factory.create(props, tempDir) as SftpProvider
        assertEquals(4, provider.config.maxConcurrency)
    }

    @Test
    fun `create coerces max_concurrency below 1 to 1`() {
        val props = mapOf("host" to "example.com", "max_concurrency" to "0")
        val provider = factory.create(props, tempDir) as SftpProvider
        assertEquals(1, provider.config.maxConcurrency)
    }

    @Test
    fun `create falls back to 4 on non-numeric max_concurrency`() {
        val props = mapOf("host" to "example.com", "max_concurrency" to "fast")
        val provider = factory.create(props, tempDir) as SftpProvider
        assertEquals(4, provider.config.maxConcurrency)
    }

    // --- isAuthenticated ---

    @Test
    fun `isAuthenticated true when host present`() {
        assertTrue(factory.isAuthenticated(mapOf("host" to "example.com"), tempDir))
    }

    @Test
    fun `isAuthenticated false when host missing`() {
        assertFalse(factory.isAuthenticated(emptyMap(), tempDir))
    }

    @Test
    fun `isAuthenticated false when host blank`() {
        assertFalse(factory.isAuthenticated(mapOf("host" to ""), tempDir))
    }

    // --- checkCredentialHealth ---

    @Test
    fun `checkCredentialHealth Missing when host absent`() {
        val result = factory.checkCredentialHealth(emptyMap(), tempDir)
        assertTrue(result is CredentialHealth.Missing)
    }

    @Test
    fun `checkCredentialHealth Missing when host blank`() {
        val result = factory.checkCredentialHealth(mapOf("host" to "   "), tempDir)
        assertTrue(result is CredentialHealth.Missing)
    }

    @Test
    fun `checkCredentialHealth Ok when password provided`() {
        val props = mapOf("host" to "example.com", "password" to "secret")
        val result = factory.checkCredentialHealth(props, tempDir)
        assertTrue(result is CredentialHealth.Ok)
    }

    @Test
    fun `checkCredentialHealth Ok when identity file exists`() {
        val keyFile = tempDir.resolve("test_key")
        Files.writeString(keyFile, "fake-key-data")
        val props = mapOf("host" to "example.com", "identity" to keyFile.toString())
        val result = factory.checkCredentialHealth(props, tempDir)
        assertTrue(result is CredentialHealth.Ok)
    }

    @Test
    fun `checkCredentialHealth Warning when no key and no password`() {
        val props = mapOf("host" to "example.com", "identity" to "/nonexistent/key")
        val result = factory.checkCredentialHealth(props, tempDir)
        assertTrue(result is CredentialHealth.Warning)
    }

    @Test
    fun `checkCredentialHealth expands tilde in identity`() {
        val props = mapOf("host" to "example.com", "identity" to "~/.ssh/nonexistent_key_xyz")
        val result = factory.checkCredentialHealth(props, tempDir)
        // Key won't exist, but path should be expanded (not start with ~)
        assertTrue(result is CredentialHealth.Warning)
        val warning = result as CredentialHealth.Warning
        assertFalse(warning.message.contains("~"))
    }

    // --- describeConnection ---

    @Test
    fun `describeConnection with host includes user at host and port`() {
        val props = mapOf("host" to "nas.local", "user" to "admin", "port" to "2222")
        val desc = factory.describeConnection(props, tempDir)
        assertTrue(desc.contains("admin@nas.local:2222"))
        assertTrue(desc.startsWith("sftp"))
    }

    @Test
    fun `describeConnection with identity includes key info`() {
        val props = mapOf("host" to "nas.local", "identity" to "/home/user/.ssh/id_ed25519")
        val desc = factory.describeConnection(props, tempDir)
        assertTrue(desc.contains("key="))
    }

    @Test
    fun `describeConnection without host returns generic label`() {
        val desc = factory.describeConnection(emptyMap(), tempDir)
        assertEquals("sftp provider", desc)
    }

    @Test
    fun `describeConnection uses default port 22`() {
        val props = mapOf("host" to "nas.local")
        val desc = factory.describeConnection(props, tempDir)
        assertTrue(desc.contains(":22"))
    }

    // --- metadata ---

    @Test
    fun `metadata has correct id and display name`() {
        assertEquals("sftp", factory.metadata.id)
        assertEquals("SFTP", factory.metadata.displayName)
        assertEquals("Self-hosted", factory.metadata.tier)
    }

    @Test
    fun `factory id is sftp`() {
        assertEquals("sftp", factory.id)
    }
}
