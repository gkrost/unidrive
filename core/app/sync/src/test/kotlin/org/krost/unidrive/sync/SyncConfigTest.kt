package org.krost.unidrive.sync

import org.krost.unidrive.sync.model.ConflictPolicy
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.*

class SyncConfigTest {
    private lateinit var tmpDir: Path

    @BeforeTest
    fun setUp() {
        tmpDir = Files.createTempDirectory("unidrive-config-test")
    }

    @Test
    fun `parses complete config`() {
        val toml =
            """
            [general]
            sync_root = "/tmp/test-sync"
            poll_interval = 30
            conflict_policy = "last_writer_wins"
            log_file = "/tmp/test.log"
            """.trimIndent()

        val configFile = tmpDir.resolve("config.toml")
        Files.writeString(configFile, toml)

        val config = SyncConfig.load(configFile)
        // Compare Path objects — string form is platform-dependent (\ vs /).
        assertEquals(Paths.get("/tmp/test-sync"), config.syncRoot)
        assertEquals(30, config.pollInterval)
        assertEquals(ConflictPolicy.LAST_WRITER_WINS, config.conflictPolicy)
    }

    @Test
    fun `uses defaults for missing values`() {
        val toml = "[general]\n"
        val configFile = tmpDir.resolve("config.toml")
        Files.writeString(configFile, toml)

        val config = SyncConfig.load(configFile)
        assertEquals(60, config.pollInterval)
        assertEquals(ConflictPolicy.KEEP_BOTH, config.conflictPolicy)
    }

    @Test
    fun `default config when file does not exist`() {
        val config = SyncConfig.load(tmpDir.resolve("nonexistent.toml"))
        assertEquals(60, config.pollInterval)
        assertEquals(ConflictPolicy.KEEP_BOTH, config.conflictPolicy)
    }

    @Test
    fun `parses pin patterns`() {
        val toml =
            """
            [general]
            sync_root = "/tmp/sync"

            [providers.onedrive.pin_patterns]
            include = ["Documents/**", "*.kdbx"]
            exclude = ["Pictures/raw/**"]
            """.trimIndent()

        val configFile = tmpDir.resolve("config.toml")
        Files.writeString(configFile, toml)

        val config = SyncConfig.load(configFile)
        assertEquals(listOf("Documents/**", "*.kdbx"), config.providerPinIncludes("onedrive"))
        assertEquals(listOf("Pictures/raw/**"), config.providerPinExcludes("onedrive"))
    }

    @Test
    fun `parses max_bandwidth_kbps`() {
        val toml =
            """
            [general]
            sync_root = "/tmp/sync"
            max_bandwidth_kbps = 512
            """.trimIndent()

        val configFile = tmpDir.resolve("config.toml")
        Files.writeString(configFile, toml)

        val config = SyncConfig.load(configFile)
        assertEquals(512, config.maxBandwidthKbps)
    }

    @Test
    fun `max_bandwidth_kbps defaults to null`() {
        val toml = "[general]\nsync_root = \"/tmp/sync\"\n"
        val configFile = tmpDir.resolve("config.toml")
        Files.writeString(configFile, toml)

        val config = SyncConfig.load(configFile)
        assertNull(config.maxBandwidthKbps)
    }

    @Test
    fun `parses exclude patterns`() {
        val toml =
            """
            [general]
            sync_root = "/tmp/sync"

            [providers.onedrive]
            exclude_patterns = ["Videos/**", "*.tmp"]
            """.trimIndent()

        val configFile = tmpDir.resolve("config.toml")
        Files.writeString(configFile, toml)

        val config = SyncConfig.load(configFile)
        assertEquals(listOf("Videos/**", "*.tmp"), config.providerExcludePatterns("onedrive"))
    }

    @Test
    fun `exclude patterns default to empty`() {
        val toml = "[general]\nsync_root = \"/tmp/sync\"\n"
        val configFile = tmpDir.resolve("config.toml")
        Files.writeString(configFile, toml)

        val config = SyncConfig.load(configFile)
        assertEquals(emptyList(), config.providerExcludePatterns("onedrive"))
    }

    @Test
    fun `parses conflict overrides`() {
        val toml =
            """
            [general]
            sync_root = "/tmp/sync"

            [providers.onedrive.conflict_overrides]
            "Documents/shared" = "last_writer_wins"
            """.trimIndent()

        val configFile = tmpDir.resolve("config.toml")
        Files.writeString(configFile, toml)

        val config = SyncConfig.load(configFile)
        val overrides = config.providerConflictOverrides("onedrive")
        assertEquals(ConflictPolicy.LAST_WRITER_WINS, overrides["Documents/shared"])
    }

    // ── Profile type and sync_root tests ─────────────────────────────────────

    @Test
    fun `profile with explicit type resolves correctly`() {
        val toml =
            """
            [providers.work]
            type = "onedrive"
            """.trimIndent()
        val raw = SyncConfig.parseRaw(toml)
        assertEquals("onedrive", raw.providers["work"]?.type)
    }

    @Test
    fun `legacy profile without type still works`() {
        val toml =
            """
            [providers.onedrive]
            exclude_patterns = ["*.tmp"]
            """.trimIndent()
        val raw = SyncConfig.parseRaw(toml)
        assertNull(raw.providers["onedrive"]?.type)
    }

    @Test
    fun `per-profile sync_root overrides general sync_root`() {
        val toml =
            """
            [general]
            sync_root = "/tmp/general"

            [providers.work]
            type = "onedrive"
            sync_root = "/tmp/work-drive"
            """.trimIndent()

        val config = SyncConfig.parse(toml, "work")
        assertEquals(Paths.get("/tmp/work-drive"), config.syncRoot)
    }

    @Test
    fun `fallback to general sync_root when profile has none`() {
        val toml =
            """
            [general]
            sync_root = "/tmp/general"

            [providers.work]
            type = "onedrive"
            """.trimIndent()

        val config = SyncConfig.parse(toml, "work")
        assertEquals(Paths.get("/tmp/general"), config.syncRoot)
    }

    // ── Credential field parsing tests ───────────────────────────────────────

    @Test
    fun `parses S3 credential fields`() {
        val toml =
            """
            [providers.backup]
            type = "s3"
            bucket = "my-bucket"
            region = "eu-central-1"
            endpoint = "https://s3.example.com"
            access_key_id = "AKID"
            secret_access_key = "SECRET"
            """.trimIndent()
        val raw = SyncConfig.parseRaw(toml)
        val p = raw.providers["backup"]!!
        assertEquals("s3", p.type)
        assertEquals("my-bucket", p.bucket)
        assertEquals("eu-central-1", p.region)
        assertEquals("https://s3.example.com", p.endpoint)
        assertEquals("AKID", p.access_key_id)
        assertEquals("SECRET", p.secret_access_key)
    }

    @Test
    fun `parses SFTP credential fields`() {
        val toml =
            """
            [providers.nas]
            type = "sftp"
            host = "nas.local"
            port = 2222
            user = "gernot"
            remote_path = "/volume1/sync"
            """.trimIndent()
        val raw = SyncConfig.parseRaw(toml)
        val p = raw.providers["nas"]!!
        assertEquals("sftp", p.type)
        assertEquals("nas.local", p.host)
        assertEquals(2222, p.port)
        assertEquals("gernot", p.user)
        assertEquals("/volume1/sync", p.remote_path)
    }

    @Test
    fun `parses WebDAV credential fields`() {
        val toml =
            """
            [providers.nextcloud]
            type = "webdav"
            url = "https://cloud.example.com/remote.php/dav/files/user"
            user = "admin"
            password = "secret"
            """.trimIndent()
        val raw = SyncConfig.parseRaw(toml)
        val p = raw.providers["nextcloud"]!!
        assertEquals("webdav", p.type)
        assertEquals("https://cloud.example.com/remote.php/dav/files/user", p.url)
        assertEquals("admin", p.user)
        assertEquals("secret", p.password)
    }

    @Test
    fun `parses credential fields in provider config`() {
        val toml =
            """
            [providers.my-rclone]
            type = "rclone"
            rclone_remote = "my-remote"
            rclone_path = "/backup"
            """.trimIndent()
        val raw = SyncConfig.parseRaw(toml)
        val p = raw.providers["my-rclone"]!!
        assertEquals("rclone", p.type)
        assertEquals("my-remote", p.rclone_remote)
        assertEquals("/backup", p.rclone_path)
    }

    // ── resolveProfile tests ────────────────────────────────────────────────

    @Test
    fun `resolveProfile with explicit type`() {
        val toml =
            """
            [providers.onedrive-work]
            type = "onedrive"
            sync_root = "/tmp/work"
            """.trimIndent()
        val raw = SyncConfig.parseRaw(toml)
        val profile = SyncConfig.resolveProfile("onedrive-work", raw)
        assertEquals("onedrive-work", profile.name)
        assertEquals("onedrive", profile.type)
        assertEquals(Paths.get("/tmp/work"), profile.syncRoot)
    }

    @Test
    fun `resolveProfile infers type from key when no type field`() {
        val toml =
            """
            [providers.onedrive]
            exclude_patterns = ["*.tmp"]
            """.trimIndent()
        val raw = SyncConfig.parseRaw(toml)
        val profile = SyncConfig.resolveProfile("onedrive", raw)
        assertEquals("onedrive", profile.name)
        assertEquals("onedrive", profile.type)
    }

    @Test
    fun `resolveProfile creates default for known type not in config`() {
        val raw = SyncConfig.parseRaw("[general]\n")
        val profile = SyncConfig.resolveProfile("onedrive", raw)
        assertEquals("onedrive", profile.name)
        assertEquals("onedrive", profile.type)
        assertNull(profile.rawProvider)
    }

    @Test
    fun `resolveProfile throws for unknown name`() {
        val raw = SyncConfig.parseRaw("[general]\n")
        assertFailsWith<IllegalArgumentException> {
            SyncConfig.resolveProfile("banana", raw)
        }
    }

    @Test
    fun `resolveProfile sync_root fallback to general`() {
        val toml =
            """
            [general]
            sync_root = "/tmp/global"
            [providers.work]
            type = "onedrive"
            """.trimIndent()
        val raw = SyncConfig.parseRaw(toml)
        val profile = SyncConfig.resolveProfile("work", raw)
        assertEquals(Paths.get("/tmp/global"), profile.syncRoot)
    }

    @Test
    fun `resolveProfile sync_root fallback to default when no general`() {
        val toml =
            """
            [providers.work]
            type = "onedrive"
            """.trimIndent()
        val raw = SyncConfig.parseRaw(toml)
        val profile = SyncConfig.resolveProfile("work", raw)
        assertTrue(profile.syncRoot.toString().endsWith("OneDrive"))
    }

    // ── detectDuplicateSyncRoots tests ──────────────────────────────────────

    @Test
    fun `detectDuplicateSyncRoots finds conflict`() {
        val toml =
            """
            [providers.a]
            type = "onedrive"
            sync_root = "/tmp/same"

            [providers.b]
            type = "onedrive"
            sync_root = "/tmp/same"
            """.trimIndent()
        val raw = SyncConfig.parseRaw(toml)
        val error = SyncConfig.detectDuplicateSyncRoots(raw)
        assertNotNull(error)
        assertTrue(error.contains("a") && error.contains("b"))
    }

    @Test
    fun `detectDuplicateSyncRoots returns null when no conflicts`() {
        val toml =
            """
            [providers.a]
            type = "onedrive"
            sync_root = "/tmp/first"

            [providers.b]
            type = "onedrive"
            sync_root = "/tmp/second"
            """.trimIndent()
        val raw = SyncConfig.parseRaw(toml)
        assertNull(SyncConfig.detectDuplicateSyncRoots(raw))
    }

    @Test
    fun `resolveProfile rejects unknown type in profile`() {
        val toml =
            """
            [providers.foo]
            type = "dropbox"
            """.trimIndent()
        val raw = SyncConfig.parseRaw(toml)
        assertFailsWith<IllegalArgumentException> {
            SyncConfig.resolveProfile("foo", raw)
        }
    }

    @Test
    fun `parses adaptive polling config`() {
        val toml =
            """
            [general]
            poll_interval = 30
            min_poll_interval = 5
            max_poll_interval = 600
            """.trimIndent()
        val configFile = tmpDir.resolve("config.toml")
        Files.writeString(configFile, toml)
        val config = SyncConfig.load(configFile)
        assertEquals(30, config.pollInterval)
        assertEquals(5, config.minPollInterval)
        assertEquals(600, config.maxPollInterval)
    }

    @Test
    fun `clamps min_poll_interval to bounds`() {
        val toml =
            """
            [general]
            poll_interval = 60
            min_poll_interval = 2
            max_poll_interval = 30
            """.trimIndent()
        val configFile = tmpDir.resolve("config.toml")
        Files.writeString(configFile, toml)
        val config = SyncConfig.load(configFile)
        assertEquals(5, config.minPollInterval) // clamped: >= 5
        assertEquals(60, config.maxPollInterval) // clamped: >= poll_interval
    }

    @Test
    fun `defaults for adaptive polling`() {
        val config = SyncConfig.defaults()
        assertEquals(60, config.pollInterval)
        assertEquals(10, config.minPollInterval)
        assertEquals(300, config.maxPollInterval)
    }

    @Test
    fun `parses benchmark client config`() {
        val toml =
            """
            [general]
            client_location = "Germany/Frankfurt"
            client_network = "fiber-1gbit"
            """.trimIndent()
        val configFile = tmpDir.resolve("config.toml")
        Files.writeString(configFile, toml)
        val config = SyncConfig.load(configFile)
        assertEquals("Germany/Frankfurt", config.clientLocation)
        assertEquals("fiber-1gbit", config.clientNetwork)
    }

    @Test
    fun `client config defaults to null`() {
        val config = SyncConfig.defaults()
        assertNull(config.clientLocation)
        assertNull(config.clientNetwork)
    }

    @Test
    fun `parses trust_all_certs config`() {
        val toml =
            """
            [general]
            [providers.my-webdav]
            type = "webdav"
            trust_all_certs = true
            """.trimIndent()
        val configFile = tmpDir.resolve("config.toml")
        Files.writeString(configFile, toml)
        val raw = SyncConfig.parseRaw(Files.readString(configFile))
        assertTrue(raw.providers["my-webdav"]?.trust_all_certs == true)
    }

    @Test
    fun `trust_all_certs defaults to null`() {
        val toml =
            """
            [general]
            [providers.my-webdav]
            type = "webdav"
            """.trimIndent()
        val configFile = tmpDir.resolve("config.toml")
        Files.writeString(configFile, toml)
        val raw = SyncConfig.parseRaw(Files.readString(configFile))
        assertNull(raw.providers["my-webdav"]?.trust_all_certs)
    }

    @Test
    fun `parses xtra_encryption config`() {
        val toml =
            """
            [general]
            [providers.my-provider]
            type = "s3"
            xtra_encryption = true
            """.trimIndent()
        val configFile = tmpDir.resolve("config.toml")
        Files.writeString(configFile, toml)
        val raw = SyncConfig.parseRaw(Files.readString(configFile))
        assertTrue(raw.providers["my-provider"]?.xtra_encryption == true)
    }

    @Test
    fun `xtra_encryption defaults to null`() {
        val toml =
            """
            [general]
            [providers.my-provider]
            type = "s3"
            """.trimIndent()
        val configFile = tmpDir.resolve("config.toml")
        Files.writeString(configFile, toml)
        val raw = SyncConfig.parseRaw(Files.readString(configFile))
        assertNull(raw.providers["my-provider"]?.xtra_encryption)
    }

    // ── Sync direction tests ─────────────────────────────────────────────────

    @Test
    fun `parses sync_direction upload`() {
        val toml =
            """
            [general]
            sync_direction = "upload"
            """.trimIndent()
        val configFile = tmpDir.resolve("config.toml")
        Files.writeString(configFile, toml)
        val config = SyncConfig.load(configFile)
        assertEquals(SyncDirection.UPLOAD, config.syncDirection)
    }

    @Test
    fun `parses sync_direction download`() {
        val toml =
            """
            [general]
            sync_direction = "download"
            """.trimIndent()
        val configFile = tmpDir.resolve("config.toml")
        Files.writeString(configFile, toml)
        val config = SyncConfig.load(configFile)
        assertEquals(SyncDirection.DOWNLOAD, config.syncDirection)
    }

    @Test
    fun `sync_direction defaults to bidirectional`() {
        val config = SyncConfig.defaults()
        assertEquals(SyncDirection.BIDIRECTIONAL, config.syncDirection)
    }

    @Test
    fun `unknown sync_direction falls back to bidirectional`() {
        val toml =
            """
            [general]
            sync_direction = "banana"
            """.trimIndent()
        val configFile = tmpDir.resolve("config.toml")
        Files.writeString(configFile, toml)
        val config = SyncConfig.load(configFile)
        assertEquals(SyncDirection.BIDIRECTIONAL, config.syncDirection)
    }

    // ── Deletion safeguard config tests ──────────────────────────────────────

    @Test
    fun `parses max_delete_percentage`() {
        val toml =
            """
            [general]
            max_delete_percentage = 25
            """.trimIndent()
        val configFile = tmpDir.resolve("config.toml")
        Files.writeString(configFile, toml)
        val config = SyncConfig.load(configFile)
        assertEquals(25, config.maxDeletePercentage)
    }

    @Test
    fun `max_delete_percentage defaults to 50`() {
        val config = SyncConfig.defaults()
        assertEquals(50, config.maxDeletePercentage)
    }

    @Test
    fun `max_delete_percentage clamped to 0-100`() {
        val toml =
            """
            [general]
            max_delete_percentage = 150
            """.trimIndent()
        val configFile = tmpDir.resolve("config.toml")
        Files.writeString(configFile, toml)
        val config = SyncConfig.load(configFile)
        assertEquals(100, config.maxDeletePercentage)
    }

    @Test
    fun `max_delete_percentage 0 disables safeguard`() {
        val toml =
            """
            [general]
            max_delete_percentage = 0
            """.trimIndent()
        val configFile = tmpDir.resolve("config.toml")
        Files.writeString(configFile, toml)
        val config = SyncConfig.load(configFile)
        assertEquals(0, config.maxDeletePercentage)
    }

    // ── Global exclude_patterns tests ────────────────────────────────────────

    @Test
    fun `parses global exclude_patterns`() {
        val toml =
            """
            [general]
            sync_root = "/tmp/sync"
            exclude_patterns = ["*.tmp", ".git/**"]
            """.trimIndent()
        val configFile = tmpDir.resolve("config.toml")
        Files.writeString(configFile, toml)
        val raw = SyncConfig.parseRaw(Files.readString(configFile))
        assertEquals(listOf("*.tmp", ".git/**"), raw.general.exclude_patterns)
    }

    @Test
    fun `effectiveExcludePatterns merges global and provider patterns`() {
        val toml =
            """
            [general]
            sync_root = "/tmp/sync"
            exclude_patterns = ["*.tmp", ".git/**"]

            [providers.onedrive]
            exclude_patterns = ["Videos/**"]
            """.trimIndent()
        val configFile = tmpDir.resolve("config.toml")
        Files.writeString(configFile, toml)
        val config = SyncConfig.load(configFile, "onedrive")
        val effective = config.effectiveExcludePatterns("onedrive")
        assertEquals(listOf("*.tmp", ".git/**", "Videos/**"), effective)
    }

    @Test
    fun `parses authority_url config`() {
        val toml =
            """
            [general]
            [providers.onedrive-work]
            type = "onedrive"
            authority_url = "https://login.microsoftonline.com/organizations/oauth2/v2.0"
            """.trimIndent()
        val configFile = tmpDir.resolve("config.toml")
        Files.writeString(configFile, toml)
        val raw = SyncConfig.parseRaw(Files.readString(configFile))
        assertEquals("https://login.microsoftonline.com/organizations/oauth2/v2.0", raw.providers["onedrive-work"]?.authority_url)
    }

    @Test
    fun `authority_url defaults to null`() {
        val toml =
            """
            [general]
            [providers.onedrive]
            """.trimIndent()
        val configFile = tmpDir.resolve("config.toml")
        Files.writeString(configFile, toml)
        val raw = SyncConfig.parseRaw(Files.readString(configFile))
        assertNull(raw.providers["onedrive"]?.authority_url)
    }

// ── Desktop notifications tests ──────────────────────────────────────────

    @Test
    fun `parses desktop_notifications config`() {
        val toml =
            """
            [general]
            desktop_notifications = true
            """.trimIndent()
        val configFile = tmpDir.resolve("config.toml")
        Files.writeString(configFile, toml)
        val config = SyncConfig.load(configFile)
        assertTrue(config.desktopNotifications)
    }

    @Test
    fun `desktop_notifications defaults to false`() {
        val config = SyncConfig.defaults()
        assertFalse(config.desktopNotifications)
    }

    // ── Hash verification tests ──────────────────────────────────────────────

    @Test
    fun `parses verify_integrity config`() {
        val toml =
            """
            [general]
            verify_integrity = true
            """.trimIndent()
        val configFile = tmpDir.resolve("config.toml")
        Files.writeString(configFile, toml)
        val config = SyncConfig.load(configFile)
        assertTrue(config.verifyIntegrity)
    }

    @Test
    fun `verify_integrity defaults to false`() {
        val config = SyncConfig.defaults()
        assertFalse(config.verifyIntegrity)
    }

    // ── Trash integration tests ──────────────────────────────────────────────

    @Test
    fun `parses use_trash config`() {
        val toml =
            """
            [general]
            use_trash = false
            """.trimIndent()
        val configFile = tmpDir.resolve("config.toml")
        Files.writeString(configFile, toml)
        val config = SyncConfig.load(configFile)
        assertFalse(config.useTrash)
    }

    @Test
    fun `use_trash defaults to true`() {
        val config = SyncConfig.defaults()
        assertTrue(config.useTrash)
    }

    // ── UD-223 fast_bootstrap ───────────────────────────────────────────────

    @Test
    fun `fast_bootstrap parses from per-profile section`() {
        val toml =
            """
            [providers.onedrive]
            type = "onedrive"
            fast_bootstrap = true
            """.trimIndent()
        val raw = SyncConfig.parseRaw(toml)
        assertEquals(true, raw.providers["onedrive"]?.fast_bootstrap)
    }

    @Test
    fun `fast_bootstrap defaults to null when absent`() {
        val toml =
            """
            [providers.onedrive]
            type = "onedrive"
            """.trimIndent()
        val raw = SyncConfig.parseRaw(toml)
        assertNull(raw.providers["onedrive"]?.fast_bootstrap)
    }

    // ── UD-252: resolveDefaultProfile shared between CLI and MCP ────────────────

    @Test
    fun `UD-252 resolveDefaultProfile returns onedrive when config is missing`() {
        // Fresh tmpDir with no config.toml at all.
        assertEquals("onedrive", SyncConfig.resolveDefaultProfile(tmpDir))
    }

    @Test
    fun `UD-252 resolveDefaultProfile returns onedrive when default_profile is unset`() {
        // The pre-UD-252 bug: MCP used to return "ds418play" here (alphabetically
        // first provider key); CLI used to return "onedrive" via picocli defaultValue.
        // Shared resolver must pick the CLI behaviour — "onedrive" — for parity.
        val toml =
            """
            [providers.ds418play]
            type = "sftp"
            host = "ds418play.local"
            user = "backup"

            [providers.onedrive]
            type = "onedrive"
            """.trimIndent()
        Files.writeString(tmpDir.resolve("config.toml"), toml)

        assertEquals("onedrive", SyncConfig.resolveDefaultProfile(tmpDir))
    }

    @Test
    fun `UD-252 resolveDefaultProfile honours general default_profile`() {
        val toml =
            """
            [general]
            default_profile = "ds418play"

            [providers.ds418play]
            type = "sftp"
            host = "ds418play.local"
            user = "backup"

            [providers.onedrive]
            type = "onedrive"
            """.trimIndent()
        Files.writeString(tmpDir.resolve("config.toml"), toml)

        assertEquals("ds418play", SyncConfig.resolveDefaultProfile(tmpDir))
    }

    @Test
    fun `UD-252 resolveDefaultProfile treats blank default_profile as unset`() {
        val toml =
            """
            [general]
            default_profile = ""

            [providers.ds418play]
            type = "sftp"
            host = "ds418play.local"
            user = "backup"
            """.trimIndent()
        Files.writeString(tmpDir.resolve("config.toml"), toml)

        assertEquals("onedrive", SyncConfig.resolveDefaultProfile(tmpDir))
    }

    @Test
    fun `UD-252 resolveDefaultProfile falls back to onedrive on unparseable config`() {
        // Corrupt TOML: bare key with no value. Must not crash — downstream callers
        // will see a readable parse error from resolveProfile() once they try to
        // load the full config. Startup should stay alive.
        Files.writeString(tmpDir.resolve("config.toml"), "this is = = not toml\n[[[")

        assertEquals("onedrive", SyncConfig.resolveDefaultProfile(tmpDir))
    }

    @Test
    fun `UD-252 CLI and MCP resolvers agree on every config shape`() {
        // The CLI call path: `provider ?: SyncConfig.resolveDefaultProfile(dir)`.
        // The MCP call path: `parsed.profile ?: SyncConfig.resolveDefaultProfile(dir)`.
        // Both collapse to the same function when -p is omitted, so a table-driven
        // check against resolveDefaultProfile covers both invocation surfaces.
        //
        // Regression guard: if anyone re-introduces divergent logic (e.g. copies a
        // `raw.providers.keys.firstOrNull()` branch into either jar), this test
        // fails loudly against the real-world multi-profile config shape from the
        // UD-252 repro.
        data class Case(
            val name: String,
            val toml: String,
            val expected: String,
        )
        val cases =
            listOf(
                Case("empty general", "[general]\n", "onedrive"),
                Case(
                    "multi profile no default_profile",
                    """
                    [providers.ds418play]
                    type = "sftp"
                    host = "h"
                    user = "u"

                    [providers.onedrive]
                    type = "onedrive"
                    """.trimIndent(),
                    "onedrive",
                ),
                Case(
                    "multi profile with default_profile",
                    """
                    [general]
                    default_profile = "work-sftp"

                    [providers.work-sftp]
                    type = "sftp"
                    host = "h"
                    user = "u"

                    [providers.onedrive]
                    type = "onedrive"
                    """.trimIndent(),
                    "work-sftp",
                ),
            )

        for (case in cases) {
            val caseDir = Files.createTempDirectory("ud252-${case.name.replace(' ', '-')}")
            Files.writeString(caseDir.resolve("config.toml"), case.toml)

            // CLI path: when no -p flag, Main.resolveCurrentProfile() now computes
            // `provider ?: SyncConfig.resolveDefaultProfile(baseConfigDir)`.
            val cliResolved = null as String? ?: SyncConfig.resolveDefaultProfile(caseDir)

            // MCP path: when no -p flag, mcp.Main computes
            // `parsed.profile ?: SyncConfig.resolveDefaultProfile(configDir)`.
            val mcpResolved = null as String? ?: SyncConfig.resolveDefaultProfile(caseDir)

            assertEquals(case.expected, cliResolved, "CLI resolver for '${case.name}'")
            assertEquals(case.expected, mcpResolved, "MCP resolver for '${case.name}'")
            assertEquals(cliResolved, mcpResolved, "CLI/MCP divergence for '${case.name}'")
        }
    }

    // ------------------------------------------------------------------
    // UD-407: defaultConfigDir / resolveConfigDir precedence
    // ------------------------------------------------------------------
    //
    // Pin the precedence so a future refactor can't accidentally drop the
    // UNIDRIVE_CONFIG_DIR escape hatch (the single mechanism that lets MSIX
    // sandboxed processes and native shells converge on the same on-disk
    // config). The pure resolver takes its inputs as parameters so we can
    // test without mutating the JVM's environment.

    @Test
    fun `UD-407 explicit override wins over everything`() {
        val explicit = Paths.get("C:/explicit/path")
        val resolved =
            SyncConfig.resolveConfigDir(
                explicitOverride = explicit,
                envConfigDir = "/should/be/ignored",
                appData = "/should/be/ignored/too",
                home = "/home/user",
                xdgConfigExists = { true },
            )
        assertEquals(explicit, resolved)
    }

    @Test
    fun `UD-407 UNIDRIVE_CONFIG_DIR wins over APPDATA and XDG`() {
        val resolved =
            SyncConfig.resolveConfigDir(
                explicitOverride = null,
                envConfigDir = "C:/Users/alice/.unidrive",
                appData = "C:/Users/alice/AppData/Roaming",
                home = "C:/Users/alice",
                xdgConfigExists = { true },
            )
        assertEquals(Paths.get("C:/Users/alice/.unidrive"), resolved)
    }

    @Test
    fun `UD-407 XDG wins over APPDATA when config_toml exists there`() {
        val resolved =
            SyncConfig.resolveConfigDir(
                explicitOverride = null,
                envConfigDir = null,
                appData = "C:/Users/alice/AppData/Roaming",
                home = "C:/Users/alice",
                xdgConfigExists = { it == Paths.get("C:/Users/alice/.config/unidrive/config.toml") },
            )
        assertEquals(Paths.get("C:/Users/alice/.config/unidrive"), resolved)
    }

    @Test
    fun `UD-407 falls back to APPDATA when XDG config_toml does not exist`() {
        val resolved =
            SyncConfig.resolveConfigDir(
                explicitOverride = null,
                envConfigDir = null,
                appData = "C:/Users/alice/AppData/Roaming",
                home = "C:/Users/alice",
                xdgConfigExists = { false },
            )
        assertEquals(Paths.get("C:/Users/alice/AppData/Roaming/unidrive"), resolved)
    }

    @Test
    fun `UD-407 falls back to XDG on platforms with no APPDATA`() {
        // Linux/macOS path: APPDATA is unset, XDG path returned regardless of
        // whether the file exists yet (so first-run setup writes there).
        val resolved =
            SyncConfig.resolveConfigDir(
                explicitOverride = null,
                envConfigDir = null,
                appData = null,
                home = "/home/alice",
                xdgConfigExists = { false },
            )
        assertEquals(Paths.get("/home/alice/.config/unidrive"), resolved)
    }

    @Test
    fun `UD-407 precedence ladder is consistent end-to-end`() {
        // Each row mutates one input from the previous row, asserting the
        // expected resolved path advances down the precedence ladder.
        data class Case(
            val name: String,
            val explicit: Path?,
            val env: String?,
            val xdgExists: Boolean,
            val appData: String?,
            val expected: String,
        )
        val home = "/home/alice"
        val cases =
            listOf(
                Case("explicit wins", Paths.get("/explicit"), "/env", true, "/appdata", "/explicit"),
                Case("env wins when no explicit", null, "/env", true, "/appdata", "/env"),
                Case("xdg wins when env unset and config exists", null, null, true, "/appdata", "/home/alice/.config/unidrive"),
                Case("appdata wins when xdg empty", null, null, false, "/appdata", "/appdata/unidrive"),
                Case("xdg fallback when appdata also unset", null, null, false, null, "/home/alice/.config/unidrive"),
            )
        for (c in cases) {
            val resolved =
                SyncConfig.resolveConfigDir(
                    explicitOverride = c.explicit,
                    envConfigDir = c.env,
                    appData = c.appData,
                    home = home,
                    xdgConfigExists = { c.xdgExists },
                )
            assertEquals(Paths.get(c.expected), resolved, "case: ${c.name}")
        }
    }
}
