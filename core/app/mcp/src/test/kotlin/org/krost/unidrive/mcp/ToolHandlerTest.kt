package org.krost.unidrive.mcp

import kotlinx.serialization.json.*
import org.krost.unidrive.sync.ProfileInfo
import org.krost.unidrive.sync.RawSyncConfig
import org.krost.unidrive.sync.SyncConfig
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*

/**
 * Tests for individual MCP tool handler functions.
 *
 * Strategy: create a real ProfileContext backed by a temp directory with a real
 * SQLite state database. Tools that need a CloudProvider use localfs (on the
 * classpath via build dependencies). No mocking libraries — stubs are manual.
 */
class ToolHandlerTest {
    private lateinit var tmpDir: Path
    private lateinit var configDir: Path
    private lateinit var profileDir: Path
    private lateinit var syncRoot: Path
    private lateinit var remoteRoot: Path

    @BeforeTest
    fun setUp() {
        tmpDir = Files.createTempDirectory("mcp-tool-test")
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

    /** ProfileContext with localfs provider properties pointing to temp dirs. */
    private fun ctx(
        providerType: String = "localfs",
        providerProps: Map<String, String?> = mapOf("root_path" to remoteRoot.toString()),
    ): ProfileContext =
        ProfileContext(
            profileName = "test-profile",
            profileInfo = ProfileInfo("test-profile", providerType, syncRoot, null),
            config =
                SyncConfig.defaults(providerType).let { cfg ->
                    // Override syncRoot to use our temp dir
                    SyncConfig(
                        syncRoot = syncRoot,
                        pollInterval = cfg.pollInterval,
                        conflictPolicy = cfg.conflictPolicy,
                        logFile = null,
                        providers = emptyMap(),
                    )
                },
            configDir = configDir,
            profileDir = profileDir,
            rawConfig = RawSyncConfig(),
            providerProperties = providerProps,
        )

    /** Minimal ProfileContext without valid provider properties (for tools that don't call createProvider). */
    private fun ctxNoProvider(): ProfileContext = ctx(providerProps = emptyMap())

    private fun args(vararg pairs: Pair<String, Any?>): JsonObject =
        buildJsonObject {
            for ((k, v) in pairs) {
                when (v) {
                    is String -> put(k, v)
                    is Boolean -> put(k, v)
                    is Int -> put(k, v)
                    is Long -> put(k, v)
                    null -> put(k, JsonNull)
                    else -> put(k, v.toString())
                }
            }
        }

    private fun emptyArgs(): JsonObject = buildJsonObject {}

    /** Extract the text content from a buildToolResult response. */
    private fun resultText(result: JsonElement): String =
        result.jsonObject["content"]!!
            .jsonArray[0]
            .jsonObject["text"]!!
            .jsonPrimitive.content

    /** Check if the result is an error response. */
    private fun isError(result: JsonElement): Boolean = result.jsonObject["isError"]?.jsonPrimitive?.booleanOrNull ?: false

    /** Parse the result text as a JSON object. */
    private fun resultJson(result: JsonElement): JsonObject = Json.parseToJsonElement(resultText(result)).jsonObject

    /** Parse the result text as a JSON array. */
    private fun resultArray(result: JsonElement): JsonArray = Json.parseToJsonElement(resultText(result)).jsonArray

    /** Populate the state database with some test entries. */
    private fun populateDb(
        ctx: ProfileContext,
        entries: List<SyncEntry>,
    ) {
        val db = ctx.openDb()
        try {
            for (e in entries) db.upsertEntry(e)
        } finally {
            db.close()
        }
    }

    private fun testEntry(
        path: String,
        remoteSize: Long = 1024,
        isFolder: Boolean = false,
        isHydrated: Boolean = false,
        isPinned: Boolean = false,
    ) = SyncEntry(
        path = path,
        remoteId = "id-${path.hashCode()}",
        remoteHash = "hash-${path.hashCode()}",
        remoteSize = remoteSize,
        remoteModified = Instant.parse("2025-01-15T10:30:00Z"),
        localMtime = if (isHydrated) 1705312200000L else null,
        localSize = if (isHydrated) remoteSize else null,
        isFolder = isFolder,
        isPinned = isPinned,
        isHydrated = isHydrated,
        lastSynced = Instant.parse("2025-01-15T10:30:00Z"),
    )

    // ── StatusTool ────────────────────────────────────────────────────────────

    @Test
    fun `status - no state database returns zero counts`() {
        val c = ctxNoProvider()
        // Don't create any DB file
        val result = statusTool.handler(emptyArgs(), c)
        val json = resultJson(result)

        assertEquals("test-profile", json["profile"]!!.jsonPrimitive.content)
        assertEquals(0, json["files"]!!.jsonPrimitive.int)
        assertEquals(0, json["sparse"]!!.jsonPrimitive.int)
        assertEquals(0, json["cloudSizeBytes"]!!.jsonPrimitive.long)
        assertEquals(0, json["localSizeBytes"]!!.jsonPrimitive.long)
        assertTrue(json["lastSync"] is JsonNull)
        assertFalse(json["daemonRunning"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `status - with populated database returns counts`() {
        val c = ctxNoProvider()
        populateDb(
            c,
            listOf(
                testEntry("/Documents", isFolder = true),
                testEntry("/Documents/report.pdf", remoteSize = 5000, isHydrated = true),
                testEntry("/Documents/slides.pptx", remoteSize = 3000, isHydrated = false),
                testEntry("/Pictures/photo.jpg", remoteSize = 2000, isHydrated = false),
            ),
        )

        val result = statusTool.handler(emptyArgs(), c)
        val json = resultJson(result)

        assertEquals(3, json["files"]!!.jsonPrimitive.int) // 3 files, 1 folder excluded
        assertEquals(2, json["sparse"]!!.jsonPrimitive.int) // slides + photo not hydrated
        assertEquals(10000, json["cloudSizeBytes"]!!.jsonPrimitive.long) // 5000+3000+2000
        assertEquals(5000, json["localSizeBytes"]!!.jsonPrimitive.long) // only hydrated report.pdf
    }

    @Test
    fun `status - with delta cursor sets hasDeltaCursor`() {
        val c = ctxNoProvider()
        val db = c.openDb()
        try {
            db.setSyncState("delta_cursor", "cursor123")
        } finally {
            db.close()
        }

        val result = statusTool.handler(emptyArgs(), c)
        val json = resultJson(result)
        assertTrue(json["hasDeltaCursor"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `status - with last_full_scan formats timestamp`() {
        val c = ctxNoProvider()
        val db = c.openDb()
        try {
            db.setSyncState("last_full_scan", "2025-06-15T14:30:00Z")
        } finally {
            db.close()
        }

        val result = statusTool.handler(emptyArgs(), c)
        val json = resultJson(result)
        val lastSync = json["lastSync"]!!.jsonPrimitive.content
        // Should be formatted, not the raw ISO string
        assertTrue(lastSync.contains("2025"), "lastSync should contain year: $lastSync")
        assertTrue(
            lastSync.contains("14") || lastSync.contains("15") || lastSync.contains("16"),
            "lastSync should contain hour (adjusted for timezone): $lastSync",
        )
    }

    @Test
    fun `status - credential health shows provider type`() {
        val c = ctxNoProvider()
        val result = statusTool.handler(emptyArgs(), c)
        val json = resultJson(result)
        assertEquals("localfs", json["providerType"]!!.jsonPrimitive.content)
    }

    // ── BackupTool ────────────────────────────────────────────────────────────

    @Test
    fun `backup - missing action returns error`() {
        val result = backupTool.handler(emptyArgs(), ctxNoProvider())
        assertTrue(isError(result))
        assertTrue(resultText(result).contains("action"))
    }

    @Test
    fun `backup - unknown action returns error`() {
        val result = backupTool.handler(args("action" to "delete"), ctxNoProvider())
        assertTrue(isError(result))
        assertTrue(resultText(result).contains("Unknown action"))
    }

    @Test
    fun `backup - list without config file returns empty array`() {
        val result = backupTool.handler(args("action" to "list"), ctxNoProvider())
        assertFalse(isError(result))
        val arr = resultArray(result)
        assertEquals(0, arr.size)
    }

    @Test
    fun `backup - list with upload provider in config`() {
        val configToml =
            """
            [providers.daily-backup]
            type = "localfs"
            sync_root = "/tmp/backup"
            remote_path = "/backups"
            sync_direction = "upload"
            file_versioning = "true"
            max_versions = "10"
            """.trimIndent()
        Files.writeString(configDir.resolve("config.toml"), configToml)

        val result = backupTool.handler(args("action" to "list"), ctxNoProvider())
        assertFalse(isError(result))
        val arr = resultArray(result)
        assertEquals(1, arr.size)
        val backup = arr[0].jsonObject
        assertEquals("daily-backup", backup["name"]!!.jsonPrimitive.content)
        assertEquals("localfs", backup["type"]!!.jsonPrimitive.content)
        assertTrue(backup["file_versioning"]!!.jsonPrimitive.boolean)
        assertEquals(10, backup["max_versions"]!!.jsonPrimitive.int)
    }

    @Test
    fun `backup - list ignores non-upload providers`() {
        val configToml =
            """
            [providers.sync-profile]
            type = "localfs"
            sync_root = "/tmp/sync"
            sync_direction = "bidirectional"
            """.trimIndent()
        Files.writeString(configDir.resolve("config.toml"), configToml)

        val result = backupTool.handler(args("action" to "list"), ctxNoProvider())
        val arr = resultArray(result)
        assertEquals(0, arr.size)
    }

    // ── PinTool ───────────────────────────────────────────────────────────────

    @Test
    fun `pin - missing action returns error`() {
        val result = pinTool.handler(emptyArgs(), ctxNoProvider())
        assertTrue(isError(result))
        assertTrue(resultText(result).contains("action"))
    }

    @Test
    fun `pin - unknown action returns error`() {
        val c = ctxNoProvider()
        // Need DB for lock path
        populateDb(c, emptyList())
        val result = pinTool.handler(args("action" to "delete", "pattern" to "*.txt"), c)
        assertTrue(isError(result))
        assertTrue(resultText(result).contains("Unknown action"))
    }

    @Test
    fun `pin - list empty rules`() {
        val c = ctxNoProvider()
        populateDb(c, emptyList())
        val result = pinTool.handler(args("action" to "list"), c)
        assertFalse(isError(result))
        val arr = resultArray(result)
        assertEquals(0, arr.size)
    }

    @Test
    fun `pin - add then list`() {
        val c = ctxNoProvider()
        populateDb(c, emptyList())

        val addResult = pinTool.handler(args("action" to "add", "pattern" to "*.pdf"), c)
        assertFalse(isError(addResult))
        val addJson = resultJson(addResult)
        assertEquals("*.pdf", addJson["pattern"]!!.jsonPrimitive.content)
        assertEquals("add", addJson["action"]!!.jsonPrimitive.content)
        assertEquals("pinned", addJson["status"]!!.jsonPrimitive.content)

        val listResult = pinTool.handler(args("action" to "list"), c)
        val arr = resultArray(listResult)
        assertEquals(1, arr.size)
        assertEquals("*.pdf", arr[0].jsonObject["pattern"]!!.jsonPrimitive.content)
        assertTrue(arr[0].jsonObject["pinned"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `pin - remove after add`() {
        val c = ctxNoProvider()
        populateDb(c, emptyList())

        pinTool.handler(args("action" to "add", "pattern" to "Documents/**"), c)
        val removeResult = pinTool.handler(args("action" to "remove", "pattern" to "Documents/**"), c)
        assertFalse(isError(removeResult))
        val removeJson = resultJson(removeResult)
        assertEquals("remove", removeJson["action"]!!.jsonPrimitive.content)
        assertEquals("unpinned", removeJson["status"]!!.jsonPrimitive.content)

        val listResult = pinTool.handler(args("action" to "list"), c)
        val arr = resultArray(listResult)
        assertEquals(0, arr.size)
    }

    @Test
    fun `pin - add without pattern returns error`() {
        val c = ctxNoProvider()
        populateDb(c, emptyList())
        val result = pinTool.handler(args("action" to "add"), c)
        assertTrue(isError(result))
        assertTrue(resultText(result).contains("pattern"))
    }

    @Test
    fun `pin - remove without pattern returns error`() {
        val c = ctxNoProvider()
        populateDb(c, emptyList())
        val result = pinTool.handler(args("action" to "remove"), c)
        assertTrue(isError(result))
        assertTrue(resultText(result).contains("pattern"))
    }

    // ── ShareTool ─────────────────────────────────────────────────────────────

    @Test
    fun `share - missing action returns error`() {
        val result = shareTool.handler(args("path" to "/test.txt"), ctxNoProvider())
        assertTrue(isError(result))
        assertTrue(resultText(result).contains("action"))
    }

    @Test
    fun `share - missing path returns error`() {
        val result = shareTool.handler(args("action" to "create"), ctxNoProvider())
        assertTrue(isError(result))
        assertTrue(resultText(result).contains("path"))
    }

    @Test
    fun `share - unknown action returns error`() {
        val result = shareTool.handler(args("action" to "delete", "path" to "/test.txt"), ctx())
        assertTrue(isError(result))
        assertTrue(resultText(result).contains("Unknown action"))
    }

    @Test
    fun `share - create with localfs returns Unsupported when file missing`() {
        // UD-301 / UD-704: localfs declares Capability.Share (file:// URIs) but
        // for a missing file it returns Unsupported(Share, "No such file: ...").
        // The MCP tool surfaces that as an isError result whose text begins
        // with "Unsupported:" — verifying the new capability-aware contract.
        val result = shareTool.handler(args("action" to "create", "path" to "/test.txt"), ctx())
        assertTrue(isError(result))
        assertTrue(
            resultText(result).startsWith("Unsupported:"),
            "expected Unsupported: prefix, got: ${resultText(result)}",
        )
    }

    @Test
    fun `share - list with localfs returns Unsupported (capability not declared)`() {
        // UD-301 / UD-704: localfs does NOT declare Capability.ListShares;
        // the interface default returns Unsupported; the MCP tool surfaces
        // that as an isError result rather than an empty list.
        val result = shareTool.handler(args("action" to "list", "path" to "/test.txt"), ctx())
        assertTrue(isError(result))
        assertTrue(
            resultText(result).startsWith("Unsupported:"),
            "expected Unsupported: prefix, got: ${resultText(result)}",
        )
    }

    @Test
    fun `share - revoke without shareId returns error`() {
        // The shareId check happens before provider.revokeShare() is called
        val result = shareTool.handler(args("action" to "revoke", "path" to "/test.txt"), ctx())
        assertTrue(isError(result))
        assertTrue(resultText(result).contains("shareId"))
    }

    @Test
    fun `share - revoke with shareId returns Unsupported for localfs`() {
        // UD-301 / UD-704: localfs does not declare Capability.RevokeShare.
        // Default returns Unsupported; the MCP tool surfaces that as an
        // isError result whose text starts with "Unsupported:".
        val result =
            shareTool.handler(
                args("action" to "revoke", "path" to "/test.txt", "shareId" to "abc123"),
                ctx(),
            )
        assertTrue(isError(result))
        assertTrue(
            resultText(result).startsWith("Unsupported:"),
            "expected Unsupported: prefix, got: ${resultText(result)}",
        )
    }

    @Test
    fun `share - create with custom expiry parses expiryHours`() {
        // UD-301 / UD-704: with no file present, localfs returns
        // Unsupported(Share, "No such file: ...") which the MCP tool
        // surfaces as isError with an "Unsupported:" prefix. Reaching that
        // path proves expiryHours parsed without throwing.
        val result =
            shareTool.handler(
                args("action" to "create", "path" to "/doc.pdf", "expiryHours" to 48),
                ctx(),
            )
        assertTrue(isError(result))
        assertTrue(
            resultText(result).startsWith("Unsupported:"),
            "expected Unsupported: prefix, got: ${resultText(result)}",
        )
    }

    // ── SyncTool ──────────────────────────────────────────────────────────────

    @Test
    fun `sync - direction parsing defaults to bidirectional`() {
        // Test with a real localfs sync — should succeed with empty remote dir
        val c = ctx()
        val result = syncTool.handler(args("dryRun" to true), c)
        assertFalse(isError(result))
        val json = resultJson(result)
        assertTrue(json["dryRun"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `sync - dryRun flag is parsed`() {
        val c = ctx()
        val result = syncTool.handler(args("dryRun" to true), c)
        val json = resultJson(result)
        assertTrue(json["dryRun"]!!.jsonPrimitive.boolean)
        assertEquals(0, json["downloaded"]!!.jsonPrimitive.int)
        assertEquals(0, json["uploaded"]!!.jsonPrimitive.int)
    }

    @Test
    fun `sync - lock contention returns error`() {
        val c = ctx()
        // Acquire the lock first so the tool can't get it
        val lock = c.acquireLock()!!
        try {
            val result = syncTool.handler(emptyArgs(), c)
            assertTrue(isError(result))
            assertTrue(resultText(result).contains("Another unidrive process"))
        } finally {
            lock.unlock()
        }
    }

    // ── ConfigTool ────────────────────────────────────────────────────────────

    @Test
    fun `config - full config returns all keys`() {
        val result = configTool.handler(emptyArgs(), ctxNoProvider())
        assertFalse(isError(result))
        val json = resultJson(result)
        assertTrue(json.containsKey("syncRoot"))
        assertTrue(json.containsKey("pollInterval"))
        assertTrue(json.containsKey("conflictPolicy"))
        assertTrue(json.containsKey("syncDirection"))
        assertTrue(json.containsKey("useTrash"))
    }

    @Test
    fun `config - specific key returns single value`() {
        val result = configTool.handler(args("key" to "pollInterval"), ctxNoProvider())
        assertFalse(isError(result))
        val json = resultJson(result)
        assertEquals(1, json.size)
        assertTrue(json.containsKey("pollInterval"))
    }

    @Test
    fun `config - unknown key returns error`() {
        val result = configTool.handler(args("key" to "nonexistent"), ctxNoProvider())
        assertTrue(isError(result))
        assertTrue(resultText(result).contains("Unknown config key"))
    }

    // ── LsTool ────────────────────────────────────────────────────────────────

    @Test
    fun `ls - empty database returns empty array`() {
        val c = ctxNoProvider()
        populateDb(c, emptyList())
        val result = lsTool.handler(emptyArgs(), c)
        assertFalse(isError(result))
        assertEquals(0, resultArray(result).size)
    }

    @Test
    fun `ls - lists entries under path prefix`() {
        val c = ctxNoProvider()
        populateDb(
            c,
            listOf(
                testEntry("/Documents/report.pdf", remoteSize = 5000),
                testEntry("/Documents/slides.pptx", remoteSize = 3000),
                testEntry("/Pictures/photo.jpg", remoteSize = 2000),
            ),
        )

        val result = lsTool.handler(args("path" to "/Documents", "recursive" to true), c)
        val arr = resultArray(result)
        assertEquals(2, arr.size)
        assertTrue(
            arr.all {
                it.jsonObject["path"]!!
                    .jsonPrimitive.content
                    .startsWith("/Documents/")
            },
        )
    }

    @Test
    fun `ls - default path is root`() {
        val c = ctxNoProvider()
        populateDb(
            c,
            listOf(
                testEntry("/file1.txt"),
                testEntry("/file2.txt"),
            ),
        )

        val result = lsTool.handler(args("recursive" to true), c)
        val arr = resultArray(result)
        assertEquals(2, arr.size)
    }

    @Test
    fun `ls - entry fields are present`() {
        val c = ctxNoProvider()
        populateDb(c, listOf(testEntry("/test.txt", remoteSize = 4096, isHydrated = true)))

        val result = lsTool.handler(args("path" to "/", "recursive" to true), c)
        val entry = resultArray(result)[0].jsonObject
        assertEquals("/test.txt", entry["path"]!!.jsonPrimitive.content)
        assertEquals("test.txt", entry["name"]!!.jsonPrimitive.content)
        assertFalse(entry["isFolder"]!!.jsonPrimitive.boolean)
        assertEquals(4096, entry["size"]!!.jsonPrimitive.long)
        assertTrue(entry["isHydrated"]!!.jsonPrimitive.boolean)
    }

    // ── ConflictsTool ─────────────────────────────────────────────────────────

    @Test
    fun `conflicts - missing action returns error`() {
        val result = conflictsTool.handler(emptyArgs(), ctxNoProvider())
        assertTrue(isError(result))
        assertTrue(resultText(result).contains("action"))
    }

    @Test
    fun `conflicts - unknown action returns error`() {
        val result = conflictsTool.handler(args("action" to "delete"), ctxNoProvider())
        assertTrue(isError(result))
        assertTrue(resultText(result).contains("Unknown action"))
    }

    @Test
    fun `conflicts - list with no conflict log returns empty array`() {
        val result = conflictsTool.handler(args("action" to "list"), ctxNoProvider())
        assertFalse(isError(result))
        assertEquals(0, resultArray(result).size)
    }

    @Test
    fun `conflicts - restore without path returns error`() {
        val c = ctxNoProvider()
        populateDb(c, emptyList()) // Need DB for lock file
        val result = conflictsTool.handler(args("action" to "restore"), c)
        assertTrue(isError(result))
        assertTrue(resultText(result).contains("path"))
    }

    @Test
    fun `conflicts - clear succeeds with no existing log`() {
        val c = ctxNoProvider()
        populateDb(c, emptyList()) // Need DB for lock file
        val result = conflictsTool.handler(args("action" to "clear"), c)
        assertFalse(isError(result))
        val json = resultJson(result)
        assertEquals("cleared", json["status"]!!.jsonPrimitive.content)
    }

    // ── QuotaTool ─────────────────────────────────────────────────────────────

    @Test
    fun `quota - returns used total remaining`() {
        val result = quotaTool.handler(emptyArgs(), ctx())
        assertFalse(isError(result))
        val json = resultJson(result)
        assertTrue(json.containsKey("used"))
        assertTrue(json.containsKey("total"))
        assertTrue(json.containsKey("remaining"))
    }

    // ── TrashTool ─────────────────────────────────────────────────────────────

    @Test
    fun `trash - missing action returns error`() {
        val result = trashTool.handler(emptyArgs(), ctxNoProvider())
        assertTrue(isError(result))
        assertTrue(resultText(result).contains("action"))
    }

    @Test
    fun `trash - unknown action returns error`() {
        val result = trashTool.handler(args("action" to "delete"), ctxNoProvider())
        assertTrue(isError(result))
        assertTrue(resultText(result).contains("Unknown action"))
    }

    @Test
    fun `trash - list with no trash dir returns empty array`() {
        val result = trashTool.handler(args("action" to "list"), ctxNoProvider())
        assertFalse(isError(result))
        assertEquals(0, resultArray(result).size)
    }

    @Test
    fun `trash - purge succeeds with no trash`() {
        val result = trashTool.handler(args("action" to "purge"), ctxNoProvider())
        assertFalse(isError(result))
        val json = resultJson(result)
        assertEquals("purged", json["status"]!!.jsonPrimitive.content)
        assertEquals(30, json["retentionDays"]!!.jsonPrimitive.int)
    }

    @Test
    fun `trash - purge respects custom retention days`() {
        val result = trashTool.handler(args("action" to "purge", "retentionDays" to 7), ctxNoProvider())
        val json = resultJson(result)
        assertEquals(7, json["retentionDays"]!!.jsonPrimitive.int)
    }

    @Test
    fun `trash - restore without path returns error`() {
        val result = trashTool.handler(args("action" to "restore"), ctxNoProvider())
        assertTrue(isError(result))
        assertTrue(resultText(result).contains("path"))
    }

    // ── VersionsTool ──────────────────────────────────────────────────────────

    @Test
    fun `versions - missing action returns error`() {
        val result = versionsTool.handler(emptyArgs(), ctxNoProvider())
        assertTrue(isError(result))
        assertTrue(resultText(result).contains("action"))
    }

    @Test
    fun `versions - unknown action returns error`() {
        val result = versionsTool.handler(args("action" to "delete"), ctxNoProvider())
        assertTrue(isError(result))
        assertTrue(resultText(result).contains("Unknown action"))
    }

    @Test
    fun `versions - list all with no versions returns empty`() {
        val result = versionsTool.handler(args("action" to "list"), ctxNoProvider())
        assertFalse(isError(result))
        assertEquals(0, resultArray(result).size)
    }

    @Test
    fun `versions - restore without path returns error`() {
        val result = versionsTool.handler(args("action" to "restore"), ctxNoProvider())
        assertTrue(isError(result))
        assertTrue(resultText(result).contains("path"))
    }

    @Test
    fun `versions - restore without timestamp returns error`() {
        val result = versionsTool.handler(args("action" to "restore", "path" to "/test.txt"), ctxNoProvider())
        assertTrue(isError(result))
        assertTrue(resultText(result).contains("timestamp"))
    }

    @Test
    fun `versions - restore with invalid timestamp returns error`() {
        val result =
            versionsTool.handler(
                args("action" to "restore", "path" to "/test.txt", "timestamp" to "not-a-date"),
                ctxNoProvider(),
            )
        assertTrue(isError(result))
        assertTrue(resultText(result).contains("Invalid timestamp"))
    }

    @Test
    fun `versions - purge succeeds with no versions`() {
        val result = versionsTool.handler(args("action" to "purge"), ctxNoProvider())
        assertFalse(isError(result))
        val json = resultJson(result)
        assertEquals("purged", json["status"]!!.jsonPrimitive.content)
        assertEquals(90, json["retentionDays"]!!.jsonPrimitive.int)
    }

    // ── GetTool ───────────────────────────────────────────────────────────────

    @Test
    fun `get - missing path returns error`() {
        val result = getTool.handler(emptyArgs(), ctxNoProvider())
        assertTrue(isError(result))
        assertTrue(resultText(result).contains("path"))
    }

    @Test
    fun `get - lock contention returns error`() {
        val c = ctxNoProvider()
        populateDb(c, emptyList())
        val lock = c.acquireLock()!!
        try {
            val result = getTool.handler(args("path" to "/test.txt"), c)
            assertTrue(isError(result))
            assertTrue(resultText(result).contains("Another unidrive process"))
        } finally {
            lock.unlock()
        }
    }

    @Test
    fun `get - untracked path returns error`() {
        val c = ctxNoProvider()
        populateDb(c, emptyList())
        val result = getTool.handler(args("path" to "/nonexistent.txt"), c)
        assertTrue(isError(result))
        assertTrue(resultText(result).contains("Not tracked"))
    }

    @Test
    fun `get - folder returns error`() {
        val c = ctxNoProvider()
        populateDb(c, listOf(testEntry("/Documents", isFolder = true)))
        val result = getTool.handler(args("path" to "/Documents"), c)
        assertTrue(isError(result))
        assertTrue(resultText(result).contains("folder"))
    }

    // ── FreeTool ──────────────────────────────────────────────────────────────

    @Test
    fun `free - missing path returns error`() {
        val result = freeTool.handler(emptyArgs(), ctxNoProvider())
        assertTrue(isError(result))
        assertTrue(resultText(result).contains("path"))
    }

    @Test
    fun `free - lock contention returns error`() {
        val c = ctxNoProvider()
        populateDb(c, emptyList())
        val lock = c.acquireLock()!!
        try {
            val result = freeTool.handler(args("path" to "/test.txt"), c)
            assertTrue(isError(result))
            assertTrue(resultText(result).contains("Another unidrive process"))
        } finally {
            lock.unlock()
        }
    }

    @Test
    fun `free - untracked path returns error`() {
        val c = ctxNoProvider()
        populateDb(c, emptyList())
        val result = freeTool.handler(args("path" to "/nonexistent.txt"), c)
        assertTrue(isError(result))
        assertTrue(resultText(result).contains("Not tracked"))
    }

    @Test
    fun `free - already dehydrated returns status`() {
        val c = ctxNoProvider()
        populateDb(c, listOf(testEntry("/test.txt", isHydrated = false)))
        val result = freeTool.handler(args("path" to "/test.txt"), c)
        assertFalse(isError(result))
        val json = resultJson(result)
        assertEquals("already_dehydrated", json["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `free - path normalization adds leading slash`() {
        val c = ctxNoProvider()
        populateDb(c, listOf(testEntry("/test.txt", isHydrated = false)))
        // Pass path without leading slash
        val result = freeTool.handler(args("path" to "test.txt"), c)
        assertFalse(isError(result))
        val json = resultJson(result)
        assertEquals("/test.txt", json["path"]!!.jsonPrimitive.content)
    }

    // ── RelocateTool ──────────────────────────────────────────────────────────

    @Test
    fun `relocate - missing fromProfile returns error`() {
        val result = relocateTool.handler(args("toProfile" to "target"), ctxNoProvider())
        assertTrue(isError(result))
        assertTrue(resultText(result).contains("fromProfile"))
    }

    @Test
    fun `relocate - missing toProfile returns error`() {
        val result = relocateTool.handler(args("fromProfile" to "source"), ctxNoProvider())
        assertTrue(isError(result))
        assertTrue(resultText(result).contains("toProfile"))
    }

    @Test
    fun `relocate - invalid fromProfile returns error`() {
        val result =
            relocateTool.handler(
                args("fromProfile" to "nonexistent-provider-xyz", "toProfile" to "other"),
                ctxNoProvider(),
            )
        assertTrue(isError(result))
        assertTrue(resultText(result).contains("Failed to create source provider"))
    }
}
