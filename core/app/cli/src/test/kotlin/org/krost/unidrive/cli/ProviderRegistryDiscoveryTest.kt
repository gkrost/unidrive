package org.krost.unidrive.cli

import org.krost.unidrive.ProviderRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * UD-813 audit follow-up: substantive ProviderRegistry/ServiceLoader checks
 * that need every provider on the test classpath. `:app:cli` depends on all
 * seven provider modules transitively (see core/app/cli/build.gradle.kts), so
 * this is where the "discovery actually works" invariants are enforced.
 *
 * The narrower structural assertions ("every entry has non-blank id and
 * displayName"; "getMetadata is consistent with get") still live in
 * `:app:core`'s SpiDiscoveryTest / ProviderMetadataTest — they pass even when
 * the provider classpath is empty, which is the right contract for the
 * generic registry layer to enforce.
 */
class ProviderRegistryDiscoveryTest {
    @Test
    fun `ServiceLoader discovers at least one provider on cli classpath`() {
        val factories = ProviderRegistry.all()
        assertTrue(
            factories.isNotEmpty(),
            ":app:cli pulls in every provider module; ServiceLoader returning empty means " +
                "the META-INF/services wiring is broken in at least one of them",
        )
    }

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

    @Test
    fun `OneDrive metadata exposes canonical display name tier and CloudAct flag`() {
        val meta = ProviderRegistry.getMetadata("onedrive")
        assertNotNull(meta, "OneDrive metadata must be discoverable via ServiceLoader on :app:cli")
        assertEquals("Microsoft OneDrive", meta.displayName)
        assertEquals("Global", meta.tier)
        assertTrue(meta.cloudActExposure, "OneDrive is hosted by Microsoft — CloudActExposure must be true")
    }

    @Test
    fun `OneDrive declares its backwards-compat sync-root directory name`() {
        // The real factory must declare syncRootDirName = "OneDrive" so
        // SyncConfig.defaultSyncRoot resolves ~/OneDrive (the historically
        // shipped layout) instead of title-casing the id to ~/Onedrive. If
        // this declaration is dropped, existing OneDrive users silently get a
        // new empty directory and their synced data appears to vanish.
        val meta = ProviderRegistry.getMetadata("onedrive")
        assertNotNull(meta, "OneDrive metadata must be discoverable via ServiceLoader on :app:cli")
        assertEquals("OneDrive", meta.syncRootDirName)
    }
}
