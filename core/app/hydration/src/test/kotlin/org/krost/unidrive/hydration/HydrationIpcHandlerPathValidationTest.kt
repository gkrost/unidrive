package org.krost.unidrive.hydration

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Every logical path a hydration verb takes is validated once, at the IPC boundary, before the verb runs: a `.` or
 * `..` segment, a NUL or another control character, or a segment longer than 255 UTF-16 units is refused with
 * `invalid_path` and the verb is never called. The empty segments of a leading or trailing slash stay accepted, and
 * so do names that merely contain dots.
 */
class HydrationIpcHandlerPathValidationTest {
    private companion object {
        // The JSON escapes are built at runtime from a backslash character, so the request text really carries the
        // six-character escape form.
        val BS: Char = Char(92)

        fun escaped(code: Int): String = "${BS}u" + "%04x".format(code)

        const val INVALID = """{"ok":false,"error":"invalid_path"}"""

        /** Logical paths as they appear inside the JSON string literal of a request. */
        val refused: List<Pair<String, String>> =
            listOf(
                "a dot-dot segment in the middle" to "/docs/../secret.txt",
                "a dot-dot segment at the end" to "/docs/..",
                "a dot-dot segment alone" to "/..",
                "a relative dot-dot start" to "../x.txt",
                "a dot segment in the middle" to "/docs/./x.txt",
                "a dot segment at the end" to "/docs/.",
                "a NUL character" to "/docs/a${escaped(0)}b.txt",
                "a control character" to "/docs/a${escaped(0x1f)}b.txt",
                "a line feed" to "/docs/a${BS}nb.txt",
                "a tab" to "/docs/a${BS}tb.txt",
                "a segment of 256 units" to "/docs/" + "n".repeat(256),
            )

        val accepted: List<Pair<String, String>> =
            listOf(
                "the root" to "/",
                "a trailing slash" to "/docs/",
                "a segment of 255 units" to "/docs/" + "n".repeat(255),
                "a name of three dots" to "/docs/...",
                "a hidden name" to "/docs/.hidden",
                "two dots inside a name" to "/docs/a..b.txt",
                "a name ending in a dot" to "/docs/x.",
            )
    }

    private data class Call(
        val verb: String,
        val args: List<String?>,
    )

    /** Records what reaches [Hydration]; every verb answers Ok. */
    private class RecordingHydration : Hydration {
        val calls = mutableListOf<Call>()
        private val cache: Path = Paths.get("cache", "recorded")

        override suspend fun openForRead(
            connectionId: String,
            handleId: String,
            path: String,
        ): OpenResult {
            calls += Call("open_read", listOf(path))
            return OpenResult.Ok(cache)
        }

        override suspend fun openForWrite(
            connectionId: String,
            handleId: String,
            path: String,
            cachePath: Path,
            baseEtag: String?,
        ): OpenResult {
            calls += Call("open_write", listOf(path))
            return OpenResult.Ok(cache)
        }

        override suspend fun closeHandle(
            connectionId: String,
            handleId: String,
        ) {
            calls += Call("close_handle", listOf(handleId))
        }

        override suspend fun cancelUpload(path: String): Boolean {
            calls += Call("cancel", listOf(path))
            return false
        }

        override suspend fun hydrate(path: String): HydrateResult {
            calls += Call("hydrate", listOf(path))
            return HydrateResult.Ok
        }

        override suspend fun dehydrate(path: String): DehydrateResult {
            calls += Call("dehydrate", listOf(path))
            return DehydrateResult.Ok
        }

        override suspend fun lastSynced(path: String): LastSyncedResult {
            calls += Call("last_synced", listOf(path))
            return LastSyncedResult.Ok(1L)
        }

        override suspend fun list(prefix: String): ListResult {
            calls += Call("list", listOf(prefix))
            return ListResult.Ok(emptyList())
        }

        override suspend fun mkdir(path: String): MkdirResult {
            calls += Call("mkdir", listOf(path))
            return MkdirResult.Ok
        }

        override suspend fun unlink(path: String): UnlinkResult {
            calls += Call("unlink", listOf(path))
            return UnlinkResult.Ok
        }

        override suspend fun rmdir(path: String): RmdirResult {
            calls += Call("rmdir", listOf(path))
            return RmdirResult.Ok
        }

        override suspend fun create(
            connectionId: String,
            handleId: String,
            path: String,
        ): CreateResult {
            calls += Call("create", listOf(path))
            return CreateResult.Ok(cache, handleId)
        }

        override suspend fun openWriteBegin(
            connectionId: String,
            path: String,
            handleId: String?,
        ): OpenResult {
            calls += Call("open_write_begin", listOf(path))
            return OpenResult.Ok(cache)
        }

        override suspend fun rename(
            oldPath: String,
            newPath: String,
            replace: Boolean,
        ): RenameResult {
            calls += Call("rename", listOf(oldPath, newPath))
            return RenameResult.Ok
        }

        override val events: Flow<HydrationEvent> = emptyFlow()

        override fun onConnectionClosed(connectionId: String) {}
    }

    // One request per path-taking verb, the logical path [p] (JSON string content) in its path field.
    private fun requests(p: String): Map<String, String> =
        linkedMapOf(
            "open_read" to """{"verb":"hydration.open_read","handle_id":"h1","path":"$p"}""",
            "open_write" to """{"verb":"hydration.open_write","handle_id":"h1","path":"$p","cache_path":"cache/recorded"}""",
            "open_write_begin" to """{"verb":"hydration.open_write_begin","path":"$p"}""",
            "hydrate" to """{"verb":"hydration.hydrate","path":"$p"}""",
            "dehydrate" to """{"verb":"hydration.dehydrate","path":"$p"}""",
            "last_synced" to """{"verb":"hydration.last_synced","path":"$p"}""",
            "list" to """{"verb":"hydration.list","prefix":"$p"}""",
            "mkdir" to """{"verb":"hydration.mkdir","path":"$p"}""",
            "unlink" to """{"verb":"hydration.unlink","path":"$p"}""",
            "rmdir" to """{"verb":"hydration.rmdir","path":"$p"}""",
            "create" to """{"verb":"hydration.create","handle_id":"h1","path":"$p"}""",
            "cancel" to """{"verb":"hydration.cancel","path":"$p"}""",
            "rename (old_path)" to """{"verb":"hydration.rename","old_path":"$p","new_path":"/docs/target.txt"}""",
            "rename (new_path)" to """{"verb":"hydration.rename","old_path":"/docs/source.txt","new_path":"$p"}""",
        )

    @Test
    fun `every path-taking verb refuses an invalid logical path before the verb runs`() =
        runTest {
            for ((what, p) in refused) {
                for ((verb, request) in requests(p)) {
                    val fake = RecordingHydration()

                    val reply = HydrationIpcHandler(fake).handle("c1", request)

                    assertEquals(INVALID, reply.trim(), "$verb with $what")
                    assertEquals(emptyList(), fake.calls, "$verb with $what must not reach the verb")
                }
            }
        }

    @Test
    fun `valid logical paths still reach every verb`() =
        runTest {
            for ((what, p) in accepted) {
                for ((verb, request) in requests(p)) {
                    val fake = RecordingHydration()

                    val reply = HydrationIpcHandler(fake).handle("c1", request)

                    assertNotEquals(INVALID, reply.trim(), "$verb with $what")
                    assertEquals(1, fake.calls.size, "$verb with $what must reach the verb: ${fake.calls}")
                }
            }
        }

    @Test
    fun `verbs without a path field are not affected`() =
        runTest {
            val fake = RecordingHydration()
            val handler = HydrationIpcHandler(fake)

            assertEquals("""{"ok":true}""", handler.handle("c1", """{"verb":"hydration.close_handle","handle_id":"../h"}""").trim())
            assertEquals("""{"ok":true}""", handler.handle("c1", """{"verb":"hydration.subscribe"}""").trim())
        }
}
