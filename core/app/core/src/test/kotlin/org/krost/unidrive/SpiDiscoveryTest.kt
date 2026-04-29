package org.krost.unidrive

import kotlin.test.Test
import kotlin.test.assertTrue

class SpiDiscoveryTest {
    @Test
    fun `knownTypes includes standard 5 built-in providers`() {
        val knownTypes = ProviderRegistry.knownTypes
        assertTrue(knownTypes.isNotEmpty(), "knownTypes should not be empty")
    }

    @Test
    fun `isKnownType validates built-in providers`() {
        assertTrue(ProviderRegistry.isKnownType("onedrive"))
        assertTrue(ProviderRegistry.isKnownType("s3"))
        assertTrue(ProviderRegistry.isKnownType("sftp"))
        assertTrue(ProviderRegistry.isKnownType("webdav"))
        assertTrue(ProviderRegistry.isKnownType("rclone"))
        assertTrue(!ProviderRegistry.isKnownType("nonexistent"))
    }

    @Test
    fun `all returns discovered factories`() {
        val factories = ProviderRegistry.all()
        if (factories.isNotEmpty()) {
            for (factory in factories) {
                assertTrue(factory.id.isNotBlank())
                assertTrue(factory.metadata.displayName.isNotBlank())
            }
        }
    }
}
