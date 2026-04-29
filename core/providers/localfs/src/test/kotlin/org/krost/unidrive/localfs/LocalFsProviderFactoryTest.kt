package org.krost.unidrive.localfs

import org.krost.unidrive.ConfigurationException
import org.krost.unidrive.CredentialHealth
import java.nio.file.Files
import kotlin.test.*

class LocalFsProviderFactoryTest {
    private val factory = LocalFsProviderFactory()

    @Test
    fun `create with valid root_path returns provider`() {
        val tempDir = Files.createTempDirectory("localfs-factory-test")
        try {
            val tokenDir = Files.createTempDirectory("localfs-token-test")
            try {
                val props = mapOf("root_path" to tempDir.toString())
                val provider = factory.create(props, tokenDir)
                assertTrue(provider is LocalFsProvider)
                assertEquals("localfs", provider.id)
            } finally {
                Files.deleteIfExists(tokenDir)
            }
        } finally {
            Files.deleteIfExists(tempDir)
        }
    }

    @Test
    fun `create with missing root_path throws ConfigurationException`() {
        val tokenDir = Files.createTempDirectory("localfs-token-test")
        try {
            assertFailsWith<ConfigurationException> {
                factory.create(emptyMap(), tokenDir)
            }
            assertFailsWith<ConfigurationException> {
                factory.create(mapOf("root_path" to ""), tokenDir)
            }
        } finally {
            Files.deleteIfExists(tokenDir)
        }
    }

    @Test
    fun `checkCredentialHealth Ok when path exists`() {
        val tempDir = Files.createTempDirectory("localfs-health-test")
        try {
            val result = factory.checkCredentialHealth(mapOf("root_path" to tempDir.toString()), tempDir)
            assertTrue(result is CredentialHealth.Ok)
        } finally {
            Files.deleteIfExists(tempDir)
        }
    }

    @Test
    fun `checkCredentialHealth Missing when root_path absent`() {
        val tempDir = Files.createTempDirectory("localfs-health-test")
        try {
            val result = factory.checkCredentialHealth(emptyMap(), tempDir)
            assertTrue(result is CredentialHealth.Missing)
        } finally {
            Files.deleteIfExists(tempDir)
        }
    }

    @Test
    fun `checkCredentialHealth Missing when path does not exist`() {
        val tempDir = Files.createTempDirectory("localfs-health-test")
        try {
            val result =
                factory.checkCredentialHealth(
                    mapOf("root_path" to "/nonexistent/path/that/does/not/exist"),
                    tempDir,
                )
            assertTrue(result is CredentialHealth.Missing)
        } finally {
            Files.deleteIfExists(tempDir)
        }
    }

    @Test
    fun `isAuthenticated true when path exists`() {
        val tempDir = Files.createTempDirectory("localfs-auth-test")
        try {
            assertTrue(factory.isAuthenticated(mapOf("root_path" to tempDir.toString()), tempDir))
        } finally {
            Files.deleteIfExists(tempDir)
        }
    }

    @Test
    fun `isAuthenticated false when root_path missing`() {
        val tempDir = Files.createTempDirectory("localfs-auth-test")
        try {
            assertFalse(factory.isAuthenticated(emptyMap(), tempDir))
        } finally {
            Files.deleteIfExists(tempDir)
        }
    }

    @Test
    fun `describeConnection includes root path`() {
        val tempDir = Files.createTempDirectory("localfs-desc-test")
        try {
            val desc = factory.describeConnection(mapOf("root_path" to "/data/sync"), tempDir)
            assertTrue(desc.contains("/data/sync"))
            assertTrue(desc.startsWith("localfs"))
        } finally {
            Files.deleteIfExists(tempDir)
        }
    }

    @Test
    fun `metadata has correct id and tier`() {
        assertEquals("localfs", factory.metadata.id)
        assertEquals("Local", factory.metadata.tier)
        assertEquals("Local Filesystem", factory.metadata.displayName)
    }
}
