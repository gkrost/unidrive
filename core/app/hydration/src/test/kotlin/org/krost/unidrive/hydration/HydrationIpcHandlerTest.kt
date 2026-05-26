package org.krost.unidrive.hydration

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun `hasActiveMountConnection tracks a hydration-verb connection until disconnect`() = runTest {
        // The refresh-route probe: a connection that issues any hydration verb is the FUSE
        // co-daemon (the refresh CLI uses sync.subscribe/refresh.run, never hydration verbs).
        val env = HydrationTestEnv()
        val handler = HydrationIpcHandler(env.hydration)

        assertFalse(handler.hasActiveMountConnection(), "no mount before any hydration verb")
        handler.handle("codaemon", """{"verb":"hydration.list","prefix":"/"}""")
        assertTrue(handler.hasActiveMountConnection(), "a hydration verb marks the connection a mount")
        handler.onSubscriberDisconnect("codaemon")
        assertFalse(handler.hasActiveMountConnection(), "disconnect clears the mount connection")
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
    fun `open_write_begin request returns JSON with cache_path`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertUnhydratedEntry("/x.txt", remoteSize = 5)
        val handler = HydrationIpcHandler(env.hydration)

        val reply = handler.handle("conn1", """{"verb":"hydration.open_write_begin","path":"/x.txt"}""")

        assertTrue(reply.contains("\"ok\":true"))
        assertTrue(reply.contains("\"cache_path\":"))
    }

    @Test
    fun `open_write_begin with handle_id registers an open-set entry`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertHydratedEntry("/live.txt", localSize = 10)
        env.syncEngine.seedCacheContent("/live.txt", "some content")
        val handler = HydrationIpcHandler(env.hydration)

        // IPC request carrying handle_id → live open, must register in open-set.
        val reply = handler.handle("conn1", """{"verb":"hydration.open_write_begin","path":"/live.txt","handle_id":"wh-1"}""")

        assertTrue(reply.contains("\"ok\":true"), "open_write_begin must succeed: $reply")
        // Verify the open-set entry was registered: dehydrate must return busy.
        val dehydrateReply = handler.handle("conn1", """{"verb":"hydration.dehydrate","path":"/live.txt"}""")
        assertEquals("""{"ok":false,"error":"busy"}""", dehydrateReply.trim(),
            "dehydrate must be busy while handle_id wh-1 is in the open-set")
    }

    @Test
    fun `open_write_begin without handle_id does NOT register an open-set entry`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertHydratedEntry("/oneshot.txt", localSize = 10)
        env.syncEngine.seedCacheContent("/oneshot.txt", "some content")
        val handler = HydrationIpcHandler(env.hydration)

        // IPC request WITHOUT handle_id → one-shot / bare-truncate; must NOT register.
        val reply = handler.handle("conn1", """{"verb":"hydration.open_write_begin","path":"/oneshot.txt"}""")

        assertTrue(reply.contains("\"ok\":true"), "open_write_begin must succeed: $reply")
        // Verify no open-set entry: dehydrate must NOT be busy.
        val dehydrateReply = handler.handle("conn1", """{"verb":"hydration.dehydrate","path":"/oneshot.txt"}""")
        assertTrue(
            !dehydrateReply.contains("\"error\":\"busy\""),
            "dehydrate must NOT be busy when handle_id was absent: $dehydrateReply",
        )
    }

    @Test
    fun `open_write_begin without path returns missing_path error`() = runTest {
        val env = HydrationTestEnv()
        val handler = HydrationIpcHandler(env.hydration)

        val reply = handler.handle("conn1", """{"verb":"hydration.open_write_begin"}""")

        assertEquals("""{"ok":false,"error":"missing_path"}""", reply.trim())
    }

    @Test
    fun `unknown verb returns unknown_verb error`() = runTest {
        val env = HydrationTestEnv()
        val handler = HydrationIpcHandler(env.hydration)

        val reply = handler.handle("conn1", """{"verb":"hydration.no_such_verb"}""")

        assertEquals("""{"ok":false,"error":"unknown_verb"}""", reply.trim())
    }

    @Test
    fun `every verb in VERBS is dispatched by handle (not unknown_verb)`() = runTest {
        // Regression test for the bug where SyncCommand's IpcServer registration loop
        // listed six verbs while HydrationIpcHandler.handle dispatched eight. Two verbs
        // (hydration.last_synced, hydration.list) were unreachable over IPC despite
        // passing unit tests that called handler.handle directly.
        //
        // This test pins the contract: every verb declared in VERBS must be a known
        // case in handle's `when`. The handler may reject the request body (missing
        // required field), but it MUST NOT return the "unknown_verb" sentinel — that
        // would indicate VERBS and handle's dispatch table have drifted apart.
        val env = HydrationTestEnv()
        val handler = HydrationIpcHandler(env.hydration)

        for (verb in HydrationIpcHandler.VERBS) {
            val reply = handler.handle("conn1", """{"verb":"$verb"}""")
            assertTrue(
                !reply.contains("\"error\":\"unknown_verb\""),
                "Verb '$verb' declared in VERBS but handle returned unknown_verb: $reply",
            )
        }
    }
}
