package org.krost.unidrive.sync

import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SyncConfigScopeTest {
    private fun parse(
        providerBody: String,
        profile: String = "p",
    ) = SyncConfig.parse("[providers.$profile]\ntype = \"internxt\"\n$providerBody\n", profile)

    @Test
    fun `sync_path accepts a single string`() {
        assertEquals(listOf("/_INBOX"), parse("sync_path = \"/_INBOX\"").syncPaths)
    }

    @Test
    fun `sync_path accepts an array and keeps later keys readable`() {
        val config = parse("sync_path = [\"/gernot_ssh\", \"/_INBOX\"]\nexclude_patterns = [\"*.tmp\"]")
        assertEquals(listOf("/_INBOX", "/gernot_ssh"), config.syncPaths)
        assertEquals(listOf("*.tmp"), config.providerExcludePatterns("p"))
    }

    @Test
    fun `absent sync_path means unscoped`() {
        assertEquals(emptyList(), parse("").syncPaths)
    }

    @Test
    fun `sync_path of root means unscoped`() {
        assertEquals(emptyList(), parse("sync_path = \"/\"").syncPaths)
    }

    @Test
    fun `sync_path only applies to its own profile`() {
        val config = SyncConfig.parse("[providers.a]\ntype = \"internxt\"\nsync_path = \"/x\"\n[providers.b]\ntype = \"internxt\"\n", "b")
        assertEquals(emptyList(), config.syncPaths)
    }

    @Test
    fun `invalid sync_path values are a config error naming the key`() {
        val bad =
            listOf(
                "sync_path = \"_INBOX\"",
                "sync_path = \"\"",
                "sync_path = \"/a/../b\"",
                "sync_path = \"/a\\\\b\"",
                "sync_path = [\"/ok\", \"relative\"]",
            )
        for (body in bad) {
            val e = assertFailsWith<IllegalArgumentException>(body) { parse(body) }
            assertTrue("sync_path" in e.message!!, "message should name the key: ${e.message}")
        }
    }

    @Test
    fun `schema property names mirror the raw config model`() {
        val schemaPath = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .map { it.resolve("docs/config-schema/config.schema.json") }
            .first { Files.exists(it) }
        val schema = Json.parseToJsonElement(Files.readString(schemaPath)).jsonObject
        val props = schema["properties"]!!.jsonObject

        val general = props["general"]!!.jsonObject["properties"]!!.jsonObject.keys
        assertEquals(RawGeneral.serializer().descriptor.elementNames.toSet(), general)

        val provider =
            props["providers"]!!.jsonObject["additionalProperties"]!!.jsonObject["properties"]!!.jsonObject.keys
        assertEquals(RawProvider.serializer().descriptor.elementNames.toSet(), provider)
    }
}
