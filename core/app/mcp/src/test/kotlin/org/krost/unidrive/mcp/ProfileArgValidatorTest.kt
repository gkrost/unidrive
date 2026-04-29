package org.krost.unidrive.mcp

import kotlinx.serialization.json.*
import org.krost.unidrive.sync.ProfileInfo
import org.krost.unidrive.sync.RawProvider
import org.krost.unidrive.sync.RawSyncConfig
import org.krost.unidrive.sync.SyncConfig
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/**
 * UD-283: regression tests for [profileMismatchError]. Pre-fix, every MCP
 * tool silently used the active context's profile regardless of what the
 * caller passed in `args["profile"]` — wrong profile names returned data
 * for the default profile with zero signal that anything was off.
 *
 * The validator pins the contract: pass-through when profile is absent or
 * matches the active ctx; structured JSON-RPC -32602 error otherwise.
 */
class ProfileArgValidatorTest {
    private lateinit var tmpDir: Path
    private lateinit var configDir: Path

    @BeforeTest
    fun setUp() {
        tmpDir = Files.createTempDirectory("mcp-profile-validator-")
        configDir = tmpDir.resolve("config")
        Files.createDirectories(configDir)
    }

    @AfterTest
    fun tearDown() {
        tmpDir.toFile().deleteRecursively()
    }

    /**
     * Build a ProfileContext where `ctx.profileName == active` and `rawConfig`
     * declares the listed profile names with a localfs type each — enough
     * to exercise the three branches of the validator.
     */
    private fun ctx(
        active: String,
        configured: List<String>,
    ): ProfileContext {
        val rawProviders =
            configured.associateWith { RawProvider(type = "localfs", root_path = "/tmp/$it") }
        val raw = RawSyncConfig(providers = rawProviders)
        val syncRoot = tmpDir.resolve("sync-$active")
        Files.createDirectories(syncRoot)
        return ProfileContext(
            profileName = active,
            profileInfo = ProfileInfo(active, "localfs", syncRoot, null),
            config =
                SyncConfig(
                    syncRoot = syncRoot,
                    pollInterval = 60,
                    conflictPolicy = org.krost.unidrive.sync.model.ConflictPolicy.KEEP_BOTH,
                    logFile = null,
                    providers = emptyMap(),
                ),
            configDir = configDir,
            profileDir = configDir.resolve(active),
            rawConfig = raw,
            providerProperties = emptyMap(),
        )
    }

    private fun args(profile: String?): JsonObject =
        buildJsonObject {
            if (profile != null) put("profile", profile)
        }

    private fun parseError(wire: String): JsonObject = Json.parseToJsonElement(wire).jsonObject["error"]!!.jsonObject

    // -- Pass-through cases (validator returns null) ---------------------------

    @Test
    fun `no profile arg returns null - pass-through`() {
        val c = ctx(active = "onedrive", configured = listOf("onedrive", "ds418play"))
        assertNull(profileMismatchError(args(profile = null), c))
    }

    @Test
    fun `profile matching active ctx returns null - pass-through`() {
        val c = ctx(active = "onedrive", configured = listOf("onedrive", "ds418play"))
        assertNull(profileMismatchError(args(profile = "onedrive"), c))
    }

    // -- Mismatch: configured but not active ----------------------------------

    @Test
    fun `requested profile is configured but inactive emits restart hint`() {
        val c = ctx(active = "onedrive", configured = listOf("onedrive", "ds418play", "ssh-local"))
        val errFn = profileMismatchError(args(profile = "ds418play"), c)
        assertNotNull(errFn, "should error when profile is configured but not the active one")

        val err = parseError(errFn(JsonPrimitive(42)))
        assertEquals(ErrorCodes.INVALID_PARAMS, err["code"]!!.jsonPrimitive.int)
        val msg = err["message"]!!.jsonPrimitive.content
        assertTrue(msg.contains("Profile mismatch"), "message should say mismatch; was: $msg")
        assertTrue(msg.contains("ds418play"), "message should name the requested profile")
        assertTrue(msg.contains("onedrive"), "message should name the active profile")
        assertTrue(
            msg.contains("unidrive-mcp -p ds418play"),
            "message should suggest the restart command; was: $msg",
        )
    }

    // -- Mismatch: completely unknown -----------------------------------------

    @Test
    fun `unknown requested profile emits Unknown profile error with configured list`() {
        val c = ctx(active = "onedrive", configured = listOf("onedrive", "ds418play"))
        val errFn = profileMismatchError(args(profile = "typo-xyz"), c)
        assertNotNull(errFn)

        val err = parseError(errFn(JsonPrimitive(7)))
        assertEquals(ErrorCodes.INVALID_PARAMS, err["code"]!!.jsonPrimitive.int)
        val msg = err["message"]!!.jsonPrimitive.content
        assertTrue(msg.startsWith("Unknown profile: typo-xyz"), "expected 'Unknown profile:' prefix; was: $msg")
        assertTrue(msg.contains("Configured profiles"), "should list configured profiles; was: $msg")
        assertTrue(msg.contains("ds418play"), "configured list should include ds418play; was: $msg")
        assertTrue(msg.contains("onedrive"), "configured list should include onedrive; was: $msg")
        assertTrue(
            msg.contains("Supported provider types"),
            "should list supported provider types as recovery hint; was: $msg",
        )
    }

    // -- error.data carries structured recovery context -----------------------

    @Test
    fun `error data carries requestedProfile activeProfile configuredProfiles supportedTypes`() {
        val c = ctx(active = "onedrive", configured = listOf("onedrive", "ds418play"))
        val errFn = profileMismatchError(args(profile = "typo-xyz"), c)
        val err = parseError(errFn!!(JsonPrimitive(99)))
        val data = err["data"]?.jsonObject
        assertNotNull(data, "error.data must be present so LLM clients can recover without parsing the message")
        assertEquals("typo-xyz", data["requestedProfile"]!!.jsonPrimitive.content)
        assertEquals("onedrive", data["activeProfile"]!!.jsonPrimitive.content)
        val configured = data["configuredProfiles"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("ds418play", "onedrive"), configured, "configured list should be sorted")
        val supported = data["supportedProviderTypes"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(
            supported.contains("localfs") && supported.contains("onedrive") && supported.contains("sftp"),
            "supportedProviderTypes should reflect ProviderRegistry; was: $supported",
        )
    }

    @Test
    fun `error response is a valid JSON-RPC 2 envelope with id`() {
        val c = ctx(active = "onedrive", configured = listOf("onedrive"))
        val errFn = profileMismatchError(args(profile = "missing"), c)
        val wireString = errFn!!(JsonPrimitive(123))
        val wire = Json.parseToJsonElement(wireString).jsonObject
        assertEquals("2.0", wire["jsonrpc"]!!.jsonPrimitive.content)
        assertEquals(123, wire["id"]!!.jsonPrimitive.int)
        assertNotNull(wire["error"], "error envelope must be present")
        assertNull(wire["result"], "must be an error response, not a tool result")
    }

    @Test
    fun `empty configured list is handled cleanly when config_toml is missing or empty`() {
        val c = ctx(active = "onedrive", configured = emptyList())
        val errFn = profileMismatchError(args(profile = "anything"), c)
        // active is still "onedrive" (the default fallback) — but rawConfig
        // has zero providers (legitimate fresh install). The error message
        // should still be useful.
        assertNotNull(errFn)
        val err = parseError(errFn(JsonPrimitive(1)))
        val msg = err["message"]!!.jsonPrimitive.content
        assertTrue(msg.contains("Unknown profile: anything"), "msg=$msg")
        // No "Configured profiles:" segment when the list is empty (matches
        // the CLI's SyncConfig.resolveProfile behaviour).
        assertTrue(!msg.contains("Configured profiles: ,"), "should not emit empty configured list; msg=$msg")
    }
}
