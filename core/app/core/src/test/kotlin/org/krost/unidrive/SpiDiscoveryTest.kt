package org.krost.unidrive

import kotlin.test.Test
import kotlin.test.assertTrue

class SpiDiscoveryTest {
    // UD-813 audit: invariant — the 5 canonical built-in provider ids all resolve to
    // known. Previously asserted only that knownTypes was non-empty (the original name
    // promised "includes 5", the body checked nothing of the kind).
    @Test
    fun `knownTypes includes standard 5 built-in providers`() {
        val canonical = listOf("onedrive", "s3", "sftp", "webdav", "rclone")
        for (id in canonical) {
            assertTrue(ProviderRegistry.isKnownType(id), "expected '$id' to be a known type")
        }
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

    // UD-813 audit: this module's test classpath does NOT include the provider
    // implementations (they live in separate Gradle modules), so
    // ProviderRegistry.all() returns an empty list here. That makes a meaningful
    // assertion impossible from :app:core alone. The substantive "every discovered
    // factory has non-blank id + displayName" check lives in :app:cli's
    // ProviderRegistryDiscoveryTest where every provider is on the test classpath.
    // What we CAN assert here is the structural invariant of `all()` itself:
    // every returned entry, whatever the size, must have non-blank id + displayName.
    // The list being empty is acceptable; finding a malformed entry is not.
    @Test
    fun `every discovered factory has non-blank id and displayName`() {
        for (factory in ProviderRegistry.all()) {
            assertTrue(factory.id.isNotBlank(), "factory id must be non-blank; got '${factory.id}'")
            assertTrue(
                factory.metadata.displayName.isNotBlank(),
                "factory '${factory.id}' metadata.displayName must be non-blank",
            )
        }
    }
}
