package org.krost.unidrive.mcp

import kotlinx.serialization.json.*
import org.krost.unidrive.sync.ProfileInfo
import org.krost.unidrive.sync.RawSyncConfig
import org.krost.unidrive.sync.SyncConfig
import org.krost.unidrive.sync.updateProfileKey
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/**
 * UD-216 — tests for the admin verbs added to the MCP surface:
 *   unidrive_auth_begin, unidrive_auth_complete, unidrive_logout,
 *   unidrive_profile_{list,add,remove,set}, unidrive_identity.
 *
 * Tests exercise the pure-path behaviour: confirm rails, argument validation,
 * TOML round-trips, continuation-handle lifecycle. Network-touching paths
 * (actual /devicecode, /token, /me) are left to live-integration tests
 * (UNIDRIVE_INTEGRATION_TESTS=true).
 */
class AdminToolsTest {
    private lateinit var tmpDir: Path
    private lateinit var configDir: Path
    private lateinit var profileDir: Path
    private lateinit var syncRoot: Path
    private lateinit var remoteRoot: Path

    @BeforeTest
    fun setUp() {
        tmpDir = Files.createTempDirectory("mcp-admin-")
        configDir = tmpDir.resolve("config")
        Files.createDirectories(configDir)
        profileDir = configDir.resolve("test-profile")
        Files.createDirectories(profileDir)
        syncRoot = tmpDir.resolve("sync-root")
        Files.createDirectories(syncRoot)
        remoteRoot = tmpDir.resolve("remote-root")
        Files.createDirectories(remoteRoot)
    }

    @AfterTest
    fun tearDown() {
        tmpDir.toFile().deleteRecursively()
    }

    private fun ctx(
        providerType: String = "localfs",
        providerProps: Map<String, String?> = mapOf("root_path" to remoteRoot.toString()),
        profileName: String = "test-profile",
    ): ProfileContext {
        val configFile = configDir.resolve("config.toml")
        val raw =
            if (Files.exists(configFile)) {
                SyncConfig.parseRaw(Files.readString(configFile))
            } else {
                RawSyncConfig()
            }
        return ProfileContext(
            profileName = profileName,
            profileInfo = ProfileInfo(profileName, providerType, syncRoot, null),
            config =
                SyncConfig(
                    syncRoot = syncRoot,
                    pollInterval = 60,
                    conflictPolicy = org.krost.unidrive.sync.model.ConflictPolicy.KEEP_BOTH,
                    logFile = null,
                    providers = emptyMap(),
                ),
            configDir = configDir,
            profileDir = profileDir,
            rawConfig = raw,
            providerProperties = providerProps,
        )
    }

    private fun args(vararg pairs: Pair<String, Any?>): JsonObject =
        buildJsonObject {
            for ((k, v) in pairs) {
                when (v) {
                    is String -> put(k, v)
                    is Boolean -> put(k, v)
                    is Int -> put(k, v)
                    is Long -> put(k, v)
                    is JsonElement -> put(k, v)
                    null -> put(k, JsonNull)
                    else -> put(k, v.toString())
                }
            }
        }

    private fun resultText(result: JsonElement): String =
        result.jsonObject["content"]!!
            .jsonArray[0]
            .jsonObject["text"]!!
            .jsonPrimitive.content

    private fun isError(result: JsonElement): Boolean = result.jsonObject["isError"]?.jsonPrimitive?.booleanOrNull ?: false

    private fun resultJson(result: JsonElement): JsonObject = Json.parseToJsonElement(resultText(result)).jsonObject

    private fun resultArray(result: JsonElement): JsonArray = Json.parseToJsonElement(resultText(result)).jsonArray

    // ── unidrive_auth_begin ───────────────────────────────────────────────────

    @Test
    fun `auth_begin rejects providers that do not support interactive auth`() {
        val result = authBeginTool.handler(buildJsonObject {}, ctx(providerType = "localfs"))
        assertTrue(isError(result))
        assertTrue(resultText(result).contains("does not support interactive auth"))
    }

    // ── unidrive_auth_complete ────────────────────────────────────────────────

    @Test
    fun `auth_complete without continuation_handle returns error`() {
        val result = authCompleteTool.handler(buildJsonObject {}, ctx())
        assertTrue(isError(result))
        assertTrue(resultText(result).contains("continuation_handle"))
    }

    @Test
    fun `auth_complete with unknown handle returns failed status`() {
        val result =
            authCompleteTool.handler(
                args("continuation_handle" to "nope-this-does-not-exist"),
                ctx(),
            )
        // Tool marks it isError (so the LLM notices) but the payload still
        // shapes the failed-status contract.
        assertTrue(isError(result))
        val json = resultJson(result)
        assertEquals("failed", json["status"]!!.jsonPrimitive.content)
        assertTrue(json["error"]!!.jsonPrimitive.content.contains("continuation_handle"))
    }

    // ── unidrive_logout ───────────────────────────────────────────────────────

    @Test
    fun `logout without confirm returns error and touches nothing`() {
        val tokenFile = profileDir.resolve("token.json")
        Files.writeString(tokenFile, "{}")
        val result = logoutTool.handler(buildJsonObject {}, ctx())
        assertTrue(isError(result))
        assertTrue(resultText(result).contains("confirm"))
        assertTrue(Files.exists(tokenFile), "logout without confirm must not delete files")
    }

    @Test
    fun `logout with confirm=false returns error and touches nothing`() {
        val tokenFile = profileDir.resolve("token.json")
        Files.writeString(tokenFile, "{}")
        val result = logoutTool.handler(args("confirm" to false), ctx())
        assertTrue(isError(result))
        assertTrue(Files.exists(tokenFile))
    }

    @Test
    fun `logout with confirm=true deletes token and state`() {
        val tokenFile = profileDir.resolve("token.json")
        val credFile = profileDir.resolve("credentials.json")
        val stateDb = profileDir.resolve("state.db")
        Files.writeString(tokenFile, "{}")
        Files.writeString(credFile, "{}")
        Files.writeString(stateDb, "x")

        val result = logoutTool.handler(args("confirm" to true), ctx())
        assertFalse(isError(result))
        val json = resultJson(result)
        assertEquals("test-profile", json["profile"]!!.jsonPrimitive.content)
        assertFalse(json["keepState"]!!.jsonPrimitive.boolean)
        assertFalse(Files.exists(tokenFile))
        assertFalse(Files.exists(credFile))
        assertFalse(Files.exists(stateDb))
    }

    @Test
    fun `logout with keepState preserves state_db`() {
        val tokenFile = profileDir.resolve("token.json")
        val stateDb = profileDir.resolve("state.db")
        Files.writeString(tokenFile, "{}")
        Files.writeString(stateDb, "x")

        val result = logoutTool.handler(args("confirm" to true, "keepState" to true), ctx())
        assertFalse(isError(result))
        assertFalse(Files.exists(tokenFile), "token should still be removed")
        assertTrue(Files.exists(stateDb), "state.db should survive keepState=true")
    }

    // ── unidrive_profile_list ─────────────────────────────────────────────────

    @Test
    fun `profile_list without config returns empty array`() {
        val result = profileListTool.handler(buildJsonObject {}, ctx())
        assertFalse(isError(result))
        val arr = resultArray(result)
        assertEquals(0, arr.size)
    }

    @Test
    fun `profile_list enumerates configured profiles`() {
        val toml =
            """
            [providers.local]
            type = "localfs"
            sync_root = "/tmp/a"

            [providers.backup]
            type = "localfs"
            sync_root = "/tmp/b"
            """.trimIndent()
        Files.writeString(configDir.resolve("config.toml"), toml)

        val result = profileListTool.handler(buildJsonObject {}, ctx())
        assertFalse(isError(result))
        val arr = resultArray(result)
        assertEquals(2, arr.size)
        val names = arr.map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertTrue(names.containsAll(listOf("local", "backup")))
        for (entry in arr) {
            val e = entry.jsonObject
            assertEquals("localfs", e["type"]!!.jsonPrimitive.content)
            assertTrue(e.containsKey("authenticated"))
            assertTrue(e.containsKey("syncRoot"))
        }
    }

    // ── unidrive_profile_add ──────────────────────────────────────────────────

    @Test
    fun `profile_add rejects invalid name`() {
        val result =
            profileAddTool.handler(
                args("name" to "bad name!", "type" to "localfs", "syncRoot" to syncRoot.toString()),
                ctx(),
            )
        assertTrue(isError(result))
        assertTrue(resultText(result).contains("Invalid profile name"))
    }

    @Test
    fun `profile_add rejects unknown provider type`() {
        val result =
            profileAddTool.handler(
                args("name" to "ok", "type" to "bogus", "syncRoot" to syncRoot.toString()),
                ctx(),
            )
        assertTrue(isError(result))
        assertTrue(resultText(result).contains("Unknown provider type"))
    }

    @Test
    fun `profile_add writes TOML section`() {
        val result =
            profileAddTool.handler(
                buildJsonObject {
                    put("name", "new-local")
                    put("type", "localfs")
                    put("syncRoot", syncRoot.toString())
                    put(
                        "options",
                        buildJsonObject {
                            put("root_path", remoteRoot.toString())
                        },
                    )
                },
                ctx(),
            )
        assertFalse(isError(result), "got: ${resultText(result)}")
        val content = Files.readString(configDir.resolve("config.toml"))
        assertTrue(content.contains("[providers.new-local]"), "section header missing in:\n$content")
        assertTrue(content.contains("type = \"localfs\""))
        assertTrue(content.contains("root_path = "))
    }

    @Test
    fun `profile_add rejects unknown option keys`() {
        val result =
            profileAddTool.handler(
                buildJsonObject {
                    put("name", "xxx")
                    put("type", "localfs")
                    put("syncRoot", syncRoot.toString())
                    put("options", buildJsonObject { put("not_a_real_key", "value") })
                },
                ctx(),
            )
        assertTrue(isError(result))
        assertTrue(resultText(result).contains("Unknown option"))
    }

    @Test
    fun `profile_add rejects duplicate`() {
        val toml =
            """
            [providers.local]
            type = "localfs"
            sync_root = "${syncRoot.toString().replace("\\", "/")}"
            """.trimIndent()
        Files.writeString(configDir.resolve("config.toml"), toml)
        val result =
            profileAddTool.handler(
                args("name" to "local", "type" to "localfs", "syncRoot" to syncRoot.toString()),
                ctx(),
            )
        assertTrue(isError(result))
        assertTrue(resultText(result).contains("already exists"))
    }

    // ── unidrive_profile_remove ───────────────────────────────────────────────

    @Test
    fun `profile_remove without confirm returns error`() {
        val toml =
            """
            [providers.gone]
            type = "localfs"
            """.trimIndent()
        Files.writeString(configDir.resolve("config.toml"), toml)
        val result = profileRemoveTool.handler(args("name" to "gone"), ctx())
        assertTrue(isError(result))
        assertTrue(resultText(result).contains("confirm"))
        assertTrue(Files.readString(configDir.resolve("config.toml")).contains("[providers.gone]"))
    }

    @Test
    fun `profile_remove with confirm strips TOML section`() {
        val toml =
            """
            [providers.keep]
            type = "localfs"
            sync_root = "/tmp/keep"

            [providers.gone]
            type = "localfs"
            sync_root = "/tmp/gone"
            """.trimIndent()
        Files.writeString(configDir.resolve("config.toml"), toml)

        val result = profileRemoveTool.handler(args("name" to "gone", "confirm" to true), ctx())
        assertFalse(isError(result), "got: ${resultText(result)}")
        val content = Files.readString(configDir.resolve("config.toml"))
        assertFalse(content.contains("[providers.gone]"))
        assertTrue(content.contains("[providers.keep]"))
    }

    @Test
    fun `profile_remove unknown profile returns error`() {
        Files.writeString(configDir.resolve("config.toml"), "[general]\n")
        val result = profileRemoveTool.handler(args("name" to "ghost", "confirm" to true), ctx())
        assertTrue(isError(result))
        assertTrue(resultText(result).contains("not found"))
    }

    // ── unidrive_profile_set ──────────────────────────────────────────────────

    @Test
    fun `profile_set rejects unknown key`() {
        val toml =
            """
            [providers.local]
            type = "localfs"
            """.trimIndent()
        Files.writeString(configDir.resolve("config.toml"), toml)
        val result =
            profileSetTool.handler(
                args("name" to "local", "key" to "bogus_key", "value" to "whatever"),
                ctx(),
            )
        assertTrue(isError(result))
        assertTrue(resultText(result).contains("Unknown config key"))
    }

    @Test
    fun `profile_set rejects non-integer value for int key`() {
        val toml =
            """
            [providers.local]
            type = "localfs"
            """.trimIndent()
        Files.writeString(configDir.resolve("config.toml"), toml)
        val result =
            profileSetTool.handler(
                args("name" to "local", "key" to "pollInterval", "value" to "sixty"),
                ctx(),
            )
        assertTrue(isError(result))
        assertTrue(resultText(result).contains("integer"))
    }

    @Test
    fun `profile_set rejects non-bool value for bool key`() {
        val toml =
            """
            [providers.local]
            type = "localfs"
            """.trimIndent()
        Files.writeString(configDir.resolve("config.toml"), toml)
        val result =
            profileSetTool.handler(
                args("name" to "local", "key" to "fast_bootstrap", "value" to "maybe"),
                ctx(),
            )
        assertTrue(isError(result))
        assertTrue(resultText(result).contains("true") || resultText(result).contains("false"))
    }

    @Test
    fun `profile_set updates existing syncRoot in place`() {
        val toml =
            """
            [providers.local]
            type = "localfs"
            sync_root = "/tmp/old"
            """.trimIndent()
        Files.writeString(configDir.resolve("config.toml"), toml)
        val result =
            profileSetTool.handler(
                args("name" to "local", "key" to "syncRoot", "value" to "/tmp/new"),
                ctx(),
            )
        assertFalse(isError(result), "got: ${resultText(result)}")
        val content = Files.readString(configDir.resolve("config.toml"))
        assertTrue(content.contains("sync_root = \"/tmp/new\""), "expected new value in:\n$content")
        assertFalse(content.contains("/tmp/old"), "old value should be gone:\n$content")
    }

    @Test
    fun `profile_set appends missing key to section`() {
        val toml =
            """
            [providers.local]
            type = "localfs"
            sync_root = "/tmp/a"
            """.trimIndent()
        Files.writeString(configDir.resolve("config.toml"), toml)
        val result =
            profileSetTool.handler(
                args("name" to "local", "key" to "poll_interval", "value" to "45"),
                ctx(),
            )
        assertFalse(isError(result), "got: ${resultText(result)}")
        val content = Files.readString(configDir.resolve("config.toml"))
        assertTrue(content.contains("poll_interval = 45"), "expected appended key:\n$content")
    }

    @Test
    fun `profile_set writes boolean unquoted`() {
        val toml =
            """
            [providers.local]
            type = "localfs"
            """.trimIndent()
        Files.writeString(configDir.resolve("config.toml"), toml)
        val result =
            profileSetTool.handler(
                args("name" to "local", "key" to "fast_bootstrap", "value" to "true"),
                ctx(),
            )
        assertFalse(isError(result))
        val content = Files.readString(configDir.resolve("config.toml"))
        assertTrue(content.contains("fast_bootstrap = true"), "expected unquoted bool:\n$content")
    }

    // ── unidrive_identity ─────────────────────────────────────────────────────

    @Test
    fun `identity for non-onedrive provider returns supported=false`() {
        val result = identityTool.handler(buildJsonObject {}, ctx(providerType = "localfs"))
        assertFalse(isError(result), "got: ${resultText(result)}")
        val json = resultJson(result)
        assertFalse(json["supported"]!!.jsonPrimitive.boolean)
        assertEquals("localfs", json["providerType"]!!.jsonPrimitive.content)
    }

    // ── updateProfileKey pure helper ──────────────────────────────────────────

    @Test
    fun `updateProfileKey replaces existing key line`() {
        val lines =
            listOf(
                "[general]",
                "",
                "[providers.p]",
                "type = \"localfs\"",
                "sync_root = \"/tmp/a\"",
                "",
                "[providers.q]",
                "type = \"s3\"",
            )
        val out = updateProfileKey(lines, "p", "sync_root", "sync_root = \"/tmp/b\"")
        assertNotNull(out)
        val text = out.joinToString("\n")
        assertTrue(text.contains("sync_root = \"/tmp/b\""))
        assertFalse(text.contains("/tmp/a"))
        assertTrue(text.contains("[providers.q]"), "later section must still exist")
    }

    @Test
    fun `updateProfileKey appends when key absent`() {
        val lines =
            listOf(
                "[providers.p]",
                "type = \"localfs\"",
                "",
                "[providers.q]",
            )
        val out = updateProfileKey(lines, "p", "poll_interval", "poll_interval = 45")
        assertNotNull(out)
        val text = out.joinToString("\n")
        assertTrue(text.contains("poll_interval = 45"))
        // Insertion must happen inside p, not inside q.
        val pIdx = out.indexOf("[providers.p]")
        val qIdx = out.indexOf("[providers.q]")
        val keyIdx = out.indexOfFirst { it.startsWith("poll_interval ") }
        assertTrue(keyIdx in (pIdx + 1) until qIdx, "poll_interval must land between p and q")
    }

    @Test
    fun `updateProfileKey returns null when section missing`() {
        val lines = listOf("[general]", "[providers.other]")
        assertNull(updateProfileKey(lines, "missing", "sync_root", "sync_root = \"/\""))
    }
}
