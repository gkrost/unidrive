package org.krost.unidrive.sync

import kotlin.test.Test
import kotlin.test.assertEquals

/** #450: `hydration_cache_max_bytes` in the profile section. */
class SyncConfigHydrationCacheTest {
    private fun parse(
        providerBody: String,
        profile: String = "p",
    ) = SyncConfig.parse("[providers.$profile]\ntype = \"internxt\"\n$providerBody\n", profile)

    @Test
    fun `absent means the default budget`() {
        assertEquals(SyncConfig.DEFAULT_HYDRATION_CACHE_MAX_BYTES, parse("").hydrationCacheMaxBytes("p"))
        assertEquals(20L * 1024 * 1024 * 1024, SyncConfig.DEFAULT_HYDRATION_CACHE_MAX_BYTES)
    }

    @Test
    fun `an explicit value is read per profile`() {
        val config =
            SyncConfig.parse(
                "[providers.a]\ntype = \"internxt\"\nhydration_cache_max_bytes = 5368709120\n" +
                    "[providers.b]\ntype = \"internxt\"\n",
                "a",
            )
        assertEquals(5368709120L, config.hydrationCacheMaxBytes("a"))
        assertEquals(SyncConfig.DEFAULT_HYDRATION_CACHE_MAX_BYTES, config.hydrationCacheMaxBytes("b"))
    }

    @Test
    fun `zero means unlimited and a negative value is read as zero`() {
        assertEquals(0L, parse("hydration_cache_max_bytes = 0").hydrationCacheMaxBytes("p"))
        assertEquals(0L, parse("hydration_cache_max_bytes = -1").hydrationCacheMaxBytes("p"))
    }
}
