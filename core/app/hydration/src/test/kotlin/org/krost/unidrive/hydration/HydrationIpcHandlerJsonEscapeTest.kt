package org.krost.unidrive.hydration

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.ProviderException
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #416: the IPC request scanner must decode the full JSON string-escape set. The .NET client
 * (System.Text.Json default writer) escapes every non-ASCII char as a backslash-u sequence, so
 * a misread there addresses the wrong name: hydration.list silently returned an empty folder,
 * and the write verbs would create/rename to a name containing the literal text "u00f6".
 *
 * Nothing in this file writes a backslash immediately followed by the letter u: the escapes
 * under test are BUILT at runtime from [BS] so the request strings really carry the literal
 * six-character JSON form (an editor or the compiler can't silently turn them into the char).
 */
class HydrationIpcHandlerJsonEscapeTest {

    private companion object {
        val BS: Char = Char(92)

        /** LATIN SMALL LETTER O WITH DIAERESIS (single UTF-16 unit, NFC). */
        val O_UML: String = Char(0x00F6).toString()

        /** GRINNING FACE: U+1F600, the surrogate pair D83D + DE00. */
        val EMOJI: String = String(Character.toChars(0x1F600))

        val KOELN: String = "K${O_UML}ln"
        val SMILE: String = "smile$EMOJI"
    }

    private enum class Enc { RAW, LOWER_HEX, UPPER_HEX }

    /** Writes [s] the way a JSON client would: raw UTF-8, or every non-ASCII UTF-16 unit as an escape. */
    private fun encode(s: String, enc: Enc): String {
        val sb = StringBuilder()
        for (c in s) {
            if (enc == Enc.RAW || c.code < 0x80) {
                sb.append(c)
                continue
            }
            val hex = "%04x".format(c.code)
            sb.append(BS).append('u').append(if (enc == Enc.UPPER_HEX) hex.uppercase() else hex)
        }
        return sb.toString()
    }

    /** One logical name in one wire encoding (hand-written where the encoder must not be trusted). */
    private class Case(val label: String, val logical: String, val json: String)

    private fun cases(): List<Case> = listOf(
        Case("raw utf-8", KOELN, encode(KOELN, Enc.RAW)),
        Case("lowercase hex escape", KOELN, encode(KOELN, Enc.LOWER_HEX)),
        Case("uppercase hex escape", KOELN, encode(KOELN, Enc.UPPER_HEX)),
        Case("hand-written hex escape", KOELN, "K${BS}u00F6ln"),
        Case("escaped ASCII letters", KOELN, "${BS}u004b${BS}u00f6${BS}u006cn"),
        Case("emoji raw utf-8", SMILE, encode(SMILE, Enc.RAW)),
        Case("emoji surrogate pair, lowercase hex", SMILE, encode(SMILE, Enc.LOWER_HEX)),
        Case("emoji surrogate pair, uppercase hex", SMILE, encode(SMILE, Enc.UPPER_HEX)),
        Case("emoji surrogate pair, mixed-case hex", SMILE, "smile${BS}uD83d${BS}uDe00"),
    )

    private data class Call(val verb: String, val args: List<String?>)

    /** Records what the handler hands to [Hydration]; every verb answers Ok. */
    private class RecordingHydration : Hydration {
        val calls = mutableListOf<Call>()
        private val cache: Path = Paths.get("/cache/recorded")

        override suspend fun openForRead(connectionId: String, handleId: String, path: String): OpenResult {
            calls += Call("open_read", listOf(handleId, path))
            return OpenResult.Ok(cache)
        }

        override suspend fun openForWrite(connectionId: String, handleId: String, path: String, cachePath: Path, baseEtag: String?): OpenResult {
            calls += Call("open_write", listOf(handleId, path, cachePath.toString()) + listOfNotNull(baseEtag))
            return OpenResult.Ok(cache)
        }

        override suspend fun closeHandle(connectionId: String, handleId: String) {
            calls += Call("close_handle", listOf(handleId))
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

        override suspend fun create(connectionId: String, handleId: String, path: String): CreateResult {
            calls += Call("create", listOf(handleId, path))
            return CreateResult.Ok(cache, handleId)
        }

        override suspend fun openWriteBegin(connectionId: String, path: String, handleId: String?): OpenResult {
            calls += Call("open_write_begin", listOf(path, handleId))
            return OpenResult.Ok(cache)
        }

        override suspend fun rename(oldPath: String, newPath: String): RenameResult {
            calls += Call("rename", listOf(oldPath, newPath))
            return RenameResult.Ok
        }

        override val events: Flow<HydrationEvent> = emptyFlow()

        override fun onConnectionClosed(connectionId: String) {}
    }

    // ── request-field decoding: every encoding of one logical name resolves to the same path ──

    @Test
    fun list_prefix_resolves_identically_for_every_encoding() = runTest {
        for (case in cases()) {
            val fake = RecordingHydration()
            val handler = HydrationIpcHandler(fake)

            val reply = handler.handle("c1", """{"verb":"hydration.list","prefix":"/_INBOX/${case.json}"}""")

            assertEquals("""{"ok":true,"entries":[]}""", reply.trim(), "[${case.label}] reply")
            assertEquals(
                listOf(Call("list", listOf("/_INBOX/${case.logical}"))),
                fake.calls,
                "[${case.label}] prefix handed to Hydration.list",
            )
        }
    }

    @Test
    fun create_path_resolves_identically_for_every_encoding() = runTest {
        for (case in cases()) {
            val fake = RecordingHydration()
            val handler = HydrationIpcHandler(fake)

            val reply = handler.handle("c1", """{"verb":"hydration.create","handle_id":"h1","path":"/_INBOX/${case.json}.txt"}""")

            assertTrue(reply.contains("\"ok\":true"), "[${case.label}] reply: $reply")
            assertEquals(
                listOf(Call("create", listOf("h1", "/_INBOX/${case.logical}.txt"))),
                fake.calls,
                "[${case.label}] path handed to Hydration.create",
            )
        }
    }

    @Test
    fun rename_paths_resolve_identically_for_every_encoding() = runTest {
        for (case in cases()) {
            val fake = RecordingHydration()
            val handler = HydrationIpcHandler(fake)

            val reply = handler.handle(
                "c1",
                """{"verb":"hydration.rename","old_path":"/old/${case.json}","new_path":"/new/${case.json}.bak"}""",
            )

            assertEquals("""{"ok":true}""", reply.trim(), "[${case.label}] reply")
            assertEquals(
                listOf(Call("rename", listOf("/old/${case.logical}", "/new/${case.logical}.bak"))),
                fake.calls,
                "[${case.label}] paths handed to Hydration.rename",
            )
        }
    }

    @Test
    fun every_verb_that_carries_a_path_decodes_escapes() = runTest {
        val logical = "/dir/$KOELN"
        val esc = "/dir/K${BS}u00f6ln"
        val cacheLogical = Paths.get("/cache/$KOELN").toString()
        val cacheEsc = "/cache/K${BS}u00f6ln"
        val expectations = listOf(
            """{"verb":"hydration.open_read","handle_id":"h","path":"$esc"}""" to Call("open_read", listOf("h", logical)),
            """{"verb":"hydration.open_write","handle_id":"h","path":"$esc","cache_path":"$cacheEsc"}""" to
                Call("open_write", listOf("h", logical, cacheLogical)),
            """{"verb":"hydration.open_write_begin","path":"$esc","handle_id":"h"}""" to Call("open_write_begin", listOf(logical, "h")),
            """{"verb":"hydration.hydrate","path":"$esc"}""" to Call("hydrate", listOf(logical)),
            """{"verb":"hydration.dehydrate","path":"$esc"}""" to Call("dehydrate", listOf(logical)),
            """{"verb":"hydration.last_synced","path":"$esc"}""" to Call("last_synced", listOf(logical)),
            """{"verb":"hydration.mkdir","path":"$esc"}""" to Call("mkdir", listOf(logical)),
            """{"verb":"hydration.unlink","path":"$esc"}""" to Call("unlink", listOf(logical)),
            """{"verb":"hydration.rmdir","path":"$esc"}""" to Call("rmdir", listOf(logical)),
            """{"verb":"hydration.create","handle_id":"h","path":"$esc"}""" to Call("create", listOf("h", logical)),
            """{"verb":"hydration.list","prefix":"$esc"}""" to Call("list", listOf(logical)),
            """{"verb":"hydration.rename","old_path":"$esc","new_path":"$esc.2"}""" to Call("rename", listOf(logical, "$logical.2")),
        )
        for ((request, expected) in expectations) {
            val fake = RecordingHydration()
            HydrationIpcHandler(fake).handle("c1", request)
            assertEquals(listOf(expected), fake.calls, "request: $request")
        }
    }

    @Test
    fun handle_id_is_decoded_too() = runTest {
        val fake = RecordingHydration()

        HydrationIpcHandler(fake).handle("c1", """{"verb":"hydration.close_handle","handle_id":"h-K${BS}u00f6ln"}""")

        assertEquals(listOf(Call("close_handle", listOf("h-$KOELN"))), fake.calls)
    }

    @Test
    fun escaped_decomposed_form_is_normalised_to_nfc_after_decoding() = runTest {
        // "foo" with combining diaereses on both o's, written as o + backslash-u0308: NFC has
        // to run on the DECODED text, else the escaped NFD form would stay decomposed.
        val fake = RecordingHydration()

        HydrationIpcHandler(fake).handle("c1", """{"verb":"hydration.mkdir","path":"/fo${BS}u0308o${BS}u0308.txt"}""")

        val nfc = "/f${O_UML}${O_UML}.txt"
        assertEquals(listOf(Call("mkdir", listOf(nfc))), fake.calls)
    }

    // ── the rest of the JSON string-escape set ──

    @Test
    fun each_simple_escape_decodes_to_its_character() = runTest {
        val table = listOf(
            "${BS}\"" to "\"",
            "${BS}${BS}" to "\\",
            "${BS}/" to "/",
            "${BS}b" to "\b",
            "${BS}f" to Char(0x0C).toString(),
            "${BS}n" to "\n",
            "${BS}r" to "\r",
            "${BS}t" to "\t",
        )
        for ((json, decoded) in table) {
            val fake = RecordingHydration()

            HydrationIpcHandler(fake).handle("c1", """{"verb":"hydration.mkdir","path":"/x${json}y"}""")

            assertEquals(listOf(Call("mkdir", listOf("/x${decoded}y"))), fake.calls, "escape ${json.last()}")
        }
    }

    @Test
    fun escaped_slash_is_a_path_separator() = runTest {
        val fake = RecordingHydration()

        HydrationIpcHandler(fake).handle("c1", """{"verb":"hydration.list","prefix":"${BS}/dir${BS}/K${BS}u00f6ln"}""")

        assertEquals(listOf(Call("list", listOf("/dir/$KOELN"))), fake.calls)
    }

    @Test
    fun escaped_quote_backslash_and_newline_inside_one_name_survive_create_and_rename() = runTest {
        // Logical name:  a"b\c<LF>d  (quote, backslash, newline in a single segment).
        val json = "a${BS}\"b${BS}${BS}c${BS}nd"
        val logical = "a\"b\\c\nd"

        val created = RecordingHydration()
        HydrationIpcHandler(created).handle("c1", """{"verb":"hydration.create","handle_id":"h1","path":"/dir/$json"}""")
        assertEquals(listOf(Call("create", listOf("h1", "/dir/$logical"))), created.calls)

        val renamed = RecordingHydration()
        HydrationIpcHandler(renamed).handle(
            "c1",
            """{"verb":"hydration.rename","old_path":"/dir/$json","new_path":"/dir/$json.moved"}""",
        )
        assertEquals(listOf(Call("rename", listOf("/dir/$logical", "/dir/$logical.moved"))), renamed.calls)
    }

    // ── key matching: only top-level members of the request object count ──

    @Test
    fun a_value_equal_to_the_key_name_is_not_mistaken_for_the_key() = runTest {
        // handle_id's VALUE is the bare string "path" (a valid opaque id), and it comes before
        // the real "path" key. A first-occurrence scan latches onto it and returns the next
        // member's value ("/c") instead of "/real".
        val fake = RecordingHydration()

        HydrationIpcHandler(fake).handle(
            "c1",
            """{"verb":"hydration.open_write","handle_id":"path","cache_path":"/c","path":"/real"}""",
        )

        assertEquals(
            listOf(Call("open_write", listOf("path", "/real", Paths.get("/c").toString()))),
            fake.calls,
        )
    }

    @Test
    fun a_nested_member_with_the_key_name_is_ignored() = runTest {
        val fake = RecordingHydration()

        HydrationIpcHandler(fake).handle(
            "c1",
            """{"verb":"hydration.mkdir","meta":{"path":"/decoy","tags":["path"]},"list":["path","x"],"path":"/real"}""",
        )

        assertEquals(listOf(Call("mkdir", listOf("/real"))), fake.calls)
    }

    @Test
    fun a_value_containing_quoted_key_text_is_not_mistaken_for_the_key() = runTest {
        // The value holds the text "path":"/decoy" with escaped quotes.
        val fake = RecordingHydration()
        val handleJson = "say ${BS}\"path${BS}\":${BS}\"/decoy${BS}\" ok"

        HydrationIpcHandler(fake).handle(
            "c1",
            """{"verb":"hydration.create","handle_id":"$handleJson","path":"/real"}""",
        )

        assertEquals(listOf(Call("create", listOf("say \"path\":\"/decoy\" ok", "/real"))), fake.calls)
    }

    @Test
    fun a_name_that_is_literally_path_in_quotes_is_a_value_not_a_key() = runTest {
        val fake = RecordingHydration()

        HydrationIpcHandler(fake).handle(
            "c1",
            """{"verb":"hydration.rename","old_path":"path","new_path":"/new/path"}""",
        )

        assertEquals(listOf(Call("rename", listOf("path", "/new/path"))), fake.calls)
    }

    @Test
    fun whitespace_between_tokens_is_accepted() = runTest {
        val fake = RecordingHydration()

        HydrationIpcHandler(fake).handle("c1", "  { \"verb\" : \"hydration.mkdir\" ,\n \"path\" :\t\"/spaced\" }  ")

        assertEquals(listOf(Call("mkdir", listOf("/spaced"))), fake.calls)
    }

    @Test
    fun a_null_optional_field_is_treated_as_absent() = runTest {
        // open_write_begin's handle_id is optional. An explicit JSON null used to be misread:
        // the scanner skipped to the NEXT quote and returned the following KEY ("path") as the id.
        val fake = RecordingHydration()

        HydrationIpcHandler(fake).handle("c1", """{"verb":"hydration.open_write_begin","handle_id":null,"path":"/x"}""")

        assertEquals(listOf(Call("open_write_begin", listOf("/x", null))), fake.calls)
    }

    @Test
    fun a_non_string_value_is_treated_as_missing() = runTest {
        val fake = RecordingHydration()

        val reply = HydrationIpcHandler(fake).handle("c1", """{"verb":"hydration.mkdir","path":123,"note":"/decoy"}""")

        assertEquals("""{"ok":false,"error":"missing_path"}""", reply.trim())
        assertTrue(fake.calls.isEmpty(), "no Hydration call may happen: ${fake.calls}")
    }

    // ── malformed escapes are rejected, never guessed ──

    @Test
    fun malformed_escapes_are_rejected_instead_of_guessed() = runTest {
        val malformed = listOf(
            "/a${BS}u00zzb",     // non-hex digits
            "/a${BS}u00",        // truncated unicode escape at the end of the string
            "/a${BS}u+0f6b",     // sign is not a hex digit
            "/a${BS}xb",         // not a JSON escape
        )
        for (path in malformed) {
            val fake = RecordingHydration()

            val reply = HydrationIpcHandler(fake).handle("c1", """{"verb":"hydration.mkdir","path":"$path"}""")

            assertEquals("""{"ok":false,"error":"missing_path"}""", reply.trim(), "path: $path")
            assertTrue(fake.calls.isEmpty(), "no Hydration call may happen for $path: ${fake.calls}")
        }
    }

    @Test
    fun an_unterminated_string_is_rejected() = runTest {
        val fake = RecordingHydration()

        val reply = HydrationIpcHandler(fake).handle("c1", """{"verb":"hydration.mkdir","path":"/never-closed""")

        assertEquals("""{"ok":false,"error":"missing_path"}""", reply.trim())
        assertTrue(fake.calls.isEmpty())
    }

    // ── end to end through the real HydrationImpl + state.db: the observable outcome ──

    private class E2eProvider : CloudProvider {
        override val id = "fake-json-escape"
        override val displayName = "Fake (JSON escape test)"
        override var isAuthenticated = true

        var lastMoveTo: String? = null

        override fun capabilities(): Set<Capability> = setOf(Capability.Delta)
        override suspend fun authenticate() {}
        override suspend fun listChildren(path: String): List<CloudItem> = emptyList()
        override suspend fun getMetadata(path: String): CloudItem = throw ProviderException("Item not found: $path")
        override suspend fun download(remotePath: String, destination: Path): Long = 0L
        override suspend fun downloadById(remoteId: String, remotePath: String, destination: Path): Long = 0L
        override suspend fun upload(localPath: Path, remotePath: String, existingRemoteId: String?, ifMatchETag: String?, onProgress: ((Long, Long) -> Unit)?): CloudItem = error("not used")
        override suspend fun delete(remotePath: String, ifMatchETag: String?) {}
        override suspend fun createFolder(path: String): CloudItem = error("not used")
        override suspend fun move(fromPath: String, toPath: String): CloudItem {
            lastMoveTo = toPath
            return CloudItem(
                id = "rid-$toPath",
                name = toPath.substringAfterLast('/'),
                path = toPath,
                size = 0L,
                isFolder = false,
                modified = Instant.parse("2026-09-29T09:00:00Z"),
                created = null,
                hash = null,
                mimeType = null,
            )
        }
        override suspend fun delta(cursor: String?, onPageProgress: ((Int) -> Unit)?, scanContext: org.krost.unidrive.ScanContext?): DeltaPage =
            DeltaPage(items = emptyList(), cursor = "cursor", hasMore = false)
        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0L, used = 0L, remaining = 0L)
    }

    private class Env {
        val provider = E2eProvider()
        val db: StateDatabase
        val engine: SyncEngine
        val handler: HydrationIpcHandler

        init {
            val cacheRoot = Files.createTempDirectory("unidrive-jsonesc-cache")
            val dbPath = Files.createTempDirectory("unidrive-jsonesc-db").resolve("state.db")
            db = StateDatabase(dbPath = dbPath, inMemory = true)
            db.initialize()
            engine = SyncEngine(
                provider = provider,
                db = db,
                syncRoot = Files.createTempDirectory("unidrive-jsonesc-sync"),
                cacheRoot = cacheRoot,
            )
            handler = HydrationIpcHandler(HydrationImpl(syncEngine = engine, stateDb = db))
        }

        fun seed(path: String, isFolder: Boolean) {
            db.upsertEntry(
                SyncEntry(
                    path = path,
                    remoteId = "rid-$path",
                    remoteHash = if (isFolder) null else "hash",
                    remoteSize = if (isFolder) 0L else 5L,
                    remoteModified = Instant.parse("2026-09-29T09:00:00Z"),
                    localMtime = null,
                    localSize = null,
                    isFolder = isFolder,
                    isPinned = false,
                    isHydrated = false,
                    lastSynced = Instant.now(),
                ),
            )
        }
    }

    @Test
    fun escaped_umlaut_list_prefix_returns_the_same_entries_as_raw() = runTest {
        // The live symptom: raw prefix listed the folder, the escaped one answered ok + [].
        val env = Env()
        env.seed("/_INBOX", isFolder = true)
        env.seed("/_INBOX/$KOELN", isFolder = true)
        env.seed("/_INBOX/$KOELN/a.txt", isFolder = false)
        env.seed("/_INBOX/$KOELN/b.txt", isFolder = false)

        val raw = env.handler.handle("c1", """{"verb":"hydration.list","prefix":"/_INBOX/${encode(KOELN, Enc.RAW)}"}""")
        assertTrue(raw.contains("a.txt") && raw.contains("b.txt"), "precondition: the raw prefix lists both files: $raw")

        for (enc in listOf(Enc.LOWER_HEX, Enc.UPPER_HEX)) {
            val escaped = env.handler.handle("c1", """{"verb":"hydration.list","prefix":"/_INBOX/${encode(KOELN, enc)}"}""")
            assertEquals(raw, escaped, "$enc prefix must list exactly what the raw prefix lists")
        }
    }

    @Test
    fun escaped_umlaut_create_makes_the_real_named_row_and_cache_file() = runTest {
        val env = Env()
        val name = "/f${O_UML}o.txt"

        val reply = env.handler.handle(
            "c1",
            """{"verb":"hydration.create","handle_id":"h1","path":"/f${BS}u00f6o.txt"}""",
        )

        assertTrue(reply.contains("\"ok\":true"), "create must succeed: $reply")
        assertNotNull(env.db.getEntry(name), "state.db row must carry the real umlaut")
        assertNull(env.db.getEntry("/fu00f6o.txt"), "no row may carry the literal text u00f6")
        assertTrue(Files.exists(env.engine.resolveCachePath(name)), "cache file must carry the real umlaut")
        assertFalse(reply.contains("u00f6"), "returned cache_path must not carry the literal text u00f6: $reply")
    }

    @Test
    fun escaped_umlaut_rename_moves_to_the_real_named_target() = runTest {
        val env = Env()
        env.seed("/a.txt", isFolder = false)

        val reply = env.handler.handle(
            "c1",
            """{"verb":"hydration.rename","old_path":"/a.txt","new_path":"/K${BS}u00f6ln.txt"}""",
        )

        assertEquals("""{"ok":true}""", reply.trim())
        assertEquals("/$KOELN.txt", env.provider.lastMoveTo, "provider.move must receive the real umlaut name")
        assertNotNull(env.db.getEntry("/$KOELN.txt"))
        assertNull(env.db.getEntry("/a.txt"))
        assertNull(env.db.getEntry("/Ku00f6ln.txt"))
    }
}
