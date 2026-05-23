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

    @Test
    fun `open_read without handle_id returns missing_handle_id error`() = runTest {
        val env = HydrationTestEnv()
        val handler = HydrationIpcHandler(env.hydration)

        val reply = handler.handle("conn1", """{"verb":"hydration.open_read","path":"/foo.txt"}""")

        assertEquals("""{"ok":false,"error":"missing_handle_id"}""", reply.trim())
    }

    @Test
    fun `open_read without path returns missing_path error`() = runTest {
        val env = HydrationTestEnv()
        val handler = HydrationIpcHandler(env.hydration)

        val reply = handler.handle("conn1", """{"verb":"hydration.open_read","handle_id":"h1"}""")

        assertEquals("""{"ok":false,"error":"missing_path"}""", reply.trim())
    }

    @Test
    fun `open_write without cache_path returns missing_cache_path error`() = runTest {
        val env = HydrationTestEnv()
        val handler = HydrationIpcHandler(env.hydration)

        val reply = handler.handle("conn1", """{"verb":"hydration.open_write","handle_id":"h1","path":"/foo.txt"}""")

        assertEquals("""{"ok":false,"error":"missing_cache_path"}""", reply.trim())
    }

    @Test
    fun `open_write with empty cache_path returns missing_cache_path error`() = runTest {
        val env = HydrationTestEnv()
        val handler = HydrationIpcHandler(env.hydration)

        val reply = handler.handle("conn1", """{"verb":"hydration.open_write","handle_id":"h1","path":"/foo.txt","cache_path":""}""")

        assertEquals("""{"ok":false,"error":"missing_cache_path"}""", reply.trim())
    }

    @Test
    fun `last_synced verb round-trips through JSON-line`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertHydratedEntry("/foo.txt", localSize = 5)
        val handler = HydrationIpcHandler(env.hydration)

        val reply = handler.handle("conn1", """{"verb":"hydration.last_synced","path":"/foo.txt"}""")

        assertTrue(reply.contains("\"ok\":true"))
        assertTrue(reply.contains("\"mtime_ms\":"))
    }

    @Test
    fun `last_synced for unknown path returns unknown_path error`() = runTest {
        val env = HydrationTestEnv()
        val handler = HydrationIpcHandler(env.hydration)

        val reply = handler.handle("conn1", """{"verb":"hydration.last_synced","path":"/nope.txt"}""")

        assertEquals("""{"ok":false,"error":"unknown_path"}""", reply.trim())
    }

    @Test
    fun `list verb round-trips through JSON-line`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertFolderEntry("/Documents")
        env.stateDb.insertHydratedEntry("/Documents/foo.txt", localSize = 42)
        env.stateDb.insertFolderEntry("/Documents/sub")
        val handler = HydrationIpcHandler(env.hydration)

        val reply = handler.handle("conn1", """{"verb":"hydration.list","prefix":"/Documents"}""")

        assertTrue(reply.contains("\"ok\":true"), "reply was $reply")
        assertTrue(reply.contains("\"entries\":["), "reply was $reply")
        assertTrue(reply.contains("\"path\":\"/Documents/foo.txt\""), "reply was $reply")
        assertTrue(reply.contains("\"size\":42"), "reply was $reply")
        assertTrue(reply.contains("\"mtime_ms\":"), "reply was $reply")
        assertTrue(reply.contains("\"hydrated\":true"), "reply was $reply")
        assertTrue(reply.contains("\"folder\":false"), "reply was $reply")
        assertTrue(reply.contains("\"path\":\"/Documents/sub\""), "reply was $reply")
        assertTrue(reply.contains("\"folder\":true"), "reply was $reply")
    }

    @Test
    fun `list verb returns empty array for empty prefix`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertFolderEntry("/Empty")
        val handler = HydrationIpcHandler(env.hydration)

        val reply = handler.handle("conn1", """{"verb":"hydration.list","prefix":"/Empty"}""")

        assertEquals("""{"ok":true,"entries":[]}""", reply.trim())
    }

    @Test
    fun `unknown verb returns unknown_verb error`() = runTest {
        val env = HydrationTestEnv()
        val handler = HydrationIpcHandler(env.hydration)

        val reply = handler.handle("conn1", """{"verb":"hydration.no_such_verb"}""")

        assertEquals("""{"ok":false,"error":"unknown_verb"}""", reply.trim())
    }
}
