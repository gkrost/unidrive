package org.krost.unidrive.hydration

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HydrationIpcHandlerTest {
    @Test
    fun `open_read request returns JSON with cache_path`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertUnhydratedEntry("/foo.txt", remoteSize = 5)
        env.syncEngine.seedRemoteContent("/foo.txt", "hello")
        val handler = HydrationIpcHandler(env.hydration)

        val reply = handler.handle("conn1", """{"verb":"hydration.open_read","handle_id":"h1","path":"/foo.txt"}""")

        assertTrue(reply.contains("\"ok\":true"))
        assertTrue(reply.contains("\"cache_path\":"))
    }

    @Test
    fun `dehydrate while open returns busy in JSON`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertHydratedEntry("/foo.txt", localSize = 5)
        env.syncEngine.seedCacheContent("/foo.txt", "hello")
        val handler = HydrationIpcHandler(env.hydration)

        handler.handle("conn1", """{"verb":"hydration.open_read","handle_id":"h1","path":"/foo.txt"}""")
        val reply = handler.handle("conn1", """{"verb":"hydration.dehydrate","path":"/foo.txt"}""")

        assertEquals("""{"ok":false,"error":"busy"}""", reply.trim())
    }

    @Test
    fun `close_handle returns ok in JSON`() = runTest {
        val env = HydrationTestEnv()
        val handler = HydrationIpcHandler(env.hydration)

        val reply = handler.handle("conn1", """{"verb":"hydration.close_handle","handle_id":"h1"}""")

        assertEquals("""{"ok":true}""", reply.trim())
    }
}
