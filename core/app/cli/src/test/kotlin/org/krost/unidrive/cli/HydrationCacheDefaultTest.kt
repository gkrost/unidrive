package org.krost.unidrive.cli

import org.krost.unidrive.hydration.HydrationImpl
import org.krost.unidrive.sync.SyncConfig
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #560 U3: :app:hydration no longer depends on :app:sync, so HydrationImpl states the default cache budget
 * itself. The daemon and `unidrive sync` must still agree with the config default (#450).
 */
class HydrationCacheDefaultTest {
    @Test
    fun `the hydration layer's default cache budget is the config default`() {
        assertEquals(SyncConfig.DEFAULT_HYDRATION_CACHE_MAX_BYTES, HydrationImpl.DEFAULT_CACHE_MAX_BYTES)
    }
}
