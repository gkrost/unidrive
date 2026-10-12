package org.krost.unidrive.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.krost.unidrive.sync.ignore.HeldBackDir
import org.krost.unidrive.sync.ignore.IgnoreFileSource
import org.krost.unidrive.sync.ignore.IgnoreSettings
import org.krost.unidrive.sync.ignore.RuleFileProbe
import org.krost.unidrive.sync.ignore.UploadIgnore
import kotlin.test.Test
import kotlin.test.assertEquals

/** #660: `ignore.explain` and the `ignore` object of `daemon.status`, over rule files held in memory. */
class IgnoreRpcHandlerTest {
    private class Files(
        val text: Map<String, String>,
        val unreadable: Map<String, String> = emptyMap(),
    ) : IgnoreFileSource {
        override fun probe(path: String): RuleFileProbe =
            unreadable[path]?.let { RuleFileProbe.Unreadable(it) }
                ?: text[path]?.let { RuleFileProbe.Present(it.length.toLong(), 1L) }
                ?: RuleFileProbe.Absent

        override fun read(
            path: String,
            maxBytes: Long,
        ): ByteArray? = text.getValue(path).toByteArray()
    }

    private val handler =
        IgnoreRpcHandler(
            UploadIgnore(
                Files(
                    text = mapOf(".unidrive.ignore" to "# root\n!.git\n", "proj/.gitignore" to "*.log\n"),
                    unreadable = mapOf("locked/.unidrive.ignore" to "access denied"),
                ),
                IgnoreSettings(honorGitignore = true, accountRules = listOf("/scratch/")),
            ),
        ) { path -> "/Videos/**".takeIf { path.startsWith("/Videos/") } }

    private fun explain(request: String): JsonObject = Json.parseToJsonElement(handler.explain(request)).jsonObject

    private fun JsonObject.str(key: String): String? = getValue(key).let { if (it is JsonNull) null else it.jsonPrimitive.content }

    @Test
    fun eachLayerIsNamedWithItsRule() {
        val log = explain("""{"path":"/proj/app.log"}""")
        assertEquals("true", log.str("ignored"))
        assertEquals("gitignore", log.str("layer"))
        assertEquals("/proj/.gitignore", log.str("file"))
        assertEquals(1, log.getValue("line").jsonPrimitive.int)
        assertEquals("*.log", log.str("pattern"))
        assertEquals("/proj/app.log", log.str("decided_at"))

        assertEquals("false", explain("""{"path":"/repo/.git/HEAD"}""").str("excluded"), "the root rule re-includes .git")
        val git = explain("""{"path":"/repo/.git","is_dir":true}""")
        assertEquals("false", git.str("ignored"))
        assertEquals("unidrive_ignore", git.str("layer"))
        assertEquals("/.unidrive.ignore", git.str("file"))
        assertEquals(2, git.getValue("line").jsonPrimitive.int)
        assertEquals("!.git", git.str("pattern"))

        val scratch = explain("""{"path":"/scratch","is_dir":true}""")
        assertEquals("account", scratch.str("layer"))
        assertEquals(null, scratch.str("file"))
        assertEquals("/scratch/", scratch.str("pattern"))

        val swap = explain("""{"path":"/notes/.a.swp"}""")
        assertEquals("default", swap.str("layer"))
        assertEquals("true", swap.str("excluded"))
    }

    @Test
    fun aHeldBackFolderSaysWhy() {
        val v = explain("""{"path":"/locked/deep/a.txt"}""")
        assertEquals("true", v.str("ignored"))
        assertEquals("unreadable", v.str("layer"))
        assertEquals("/locked/.unidrive.ignore", v.str("file"))
        assertEquals(null, v.str("line"))
        assertEquals(null, v.str("pattern"))
        assertEquals("access denied", v.str("reason"))
    }

    @Test
    fun anExcludePatternIsReportedBesideTheRules() {
        val v = explain("""{"path":"/Videos/clip.mp4"}""")
        assertEquals("false", v.str("ignored"))
        assertEquals("true", v.str("excluded"))
        assertEquals("/Videos/**", v.str("exclude_pattern"))
        val plain = explain("""{"path":"/docs/a.txt"}""")
        assertEquals("false", plain.str("excluded"))
        assertEquals(null, plain.str("layer"))
        assertEquals(null, plain.str("decided_at"))
    }

    @Test
    fun badRequestsAreRefused() {
        assertEquals("""{"ok":false,"error":"missing_path"}""", handler.explain("""{"verb":"ignore.explain"}"""))
        assertEquals("""{"ok":false,"error":"missing_path"}""", handler.explain("""{"path":7}"""))
        for (bad in listOf("/a/../b", "/a/./b", "/a//b", "/a/\u0001")) {
            assertEquals("""{"ok":false,"error":"invalid_path"}""", handler.explain(JsonObject(mapOf("path" to JsonPrimitive(bad))).toString()), bad)
        }
        assertEquals("""{"ok":false,"error":"invalid_is_dir"}""", handler.explain("""{"path":"/a","is_dir":"yes"}"""))
        assertEquals("/a/b", explain("""{"path":"a/b/"}""").str("path"), "the path comes back in its logical form")
    }

    @Test
    fun theStatusObjectCountsHeldBackFoldersAndCapsItsItems() {
        val none = ignoreHealthJson(emptyList()).getValue("held_back").jsonObject
        assertEquals(0, none.getValue("count").jsonPrimitive.int)
        assertEquals(0, none.getValue("items").jsonArray.size)

        val many = (1..HELD_BACK_ITEMS_CAP + 3).map { HeldBackDir("/d$it", "/d$it/.unidrive.ignore", "I/O error") }
        val held = ignoreHealthJson(many).getValue("held_back").jsonObject
        assertEquals(HELD_BACK_ITEMS_CAP + 3, held.getValue("count").jsonPrimitive.int)
        assertEquals(3, held.getValue("more").jsonPrimitive.int)
        assertEquals(HELD_BACK_ITEMS_CAP, held.getValue("items").jsonArray.size)
        assertEquals("/d1/.unidrive.ignore", held.getValue("items").jsonArray[0].jsonObject.str("file"))
    }
}
