package org.krost.unidrive

import kotlin.test.Test
import kotlin.test.assertTrue

class SpiDiscoveryTest {
    // UD-013: deleted `knownTypes includes standard 5 built-in providers` and
    // `isKnownType validates built-in providers`. Both exercised the removed `isKnownType`
    // method, which only ever consulted a hardcoded `defaultTypes` fallback set — making
    // the assertions tautological (the method tested itself against its own constant).
    // The populated-classpath equivalent lives in :app:cli's ProviderRegistryDiscoveryTest.

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
