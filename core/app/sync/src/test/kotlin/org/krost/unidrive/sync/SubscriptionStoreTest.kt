package org.krost.unidrive.sync

import java.nio.file.Files
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.*

class SubscriptionStoreTest {
    private lateinit var store: SubscriptionStore

    @BeforeTest
    fun setUp() {
        val tmpDir = Files.createTempDirectory("sub-store-test")
        store = SubscriptionStore(tmpDir.resolve("state.db"))
        store.initialize()
    }

    @AfterTest
    fun tearDown() {
        store.close()
    }

    @Test
    fun `save and get subscription`() {
        val expiry = Instant.now().plus(3, ChronoUnit.DAYS)
        store.save("onedrive", "sub-123", expiry)
        val info = store.get("onedrive")
        assertNotNull(info)
        assertEquals("sub-123", info.subscriptionId)
        assertEquals(expiry.epochSecond, info.expiresAt.epochSecond)
    }

    @Test
    fun `get returns null for missing profile`() {
        assertNull(store.get("nonexistent"))
    }

    @Test
    fun `save overwrites existing subscription`() {
        val expiry1 = Instant.now().plus(1, ChronoUnit.DAYS)
        val expiry2 = Instant.now().plus(3, ChronoUnit.DAYS)
        store.save("onedrive", "sub-111", expiry1)
        store.save("onedrive", "sub-222", expiry2)
        val info = store.get("onedrive")
        assertNotNull(info)
        assertEquals("sub-222", info.subscriptionId)
    }

    @Test
    fun `delete removes subscription`() {
        store.save("onedrive", "sub-123", Instant.now().plus(1, ChronoUnit.DAYS))
        store.delete("onedrive")
        assertNull(store.get("onedrive"))
    }

    @Test
    fun `multiple profiles are independent`() {
        val now = Instant.now()
        store.save("profile-a", "sub-a", now.plus(1, ChronoUnit.DAYS))
        store.save("profile-b", "sub-b", now.plus(2, ChronoUnit.DAYS))
        assertEquals("sub-a", store.get("profile-a")!!.subscriptionId)
        assertEquals("sub-b", store.get("profile-b")!!.subscriptionId)
        store.delete("profile-a")
        assertNull(store.get("profile-a"))
        assertNotNull(store.get("profile-b"))
    }
}
