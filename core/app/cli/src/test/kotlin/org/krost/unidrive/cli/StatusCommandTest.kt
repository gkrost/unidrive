package org.krost.unidrive.cli

import org.krost.unidrive.CredentialHealth
import org.krost.unidrive.sync.SyncConfig
import org.krost.unidrive.sync.model.SyncEntry
import picocli.CommandLine
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StatusCommandTest {
    private val cmd = CommandLine(Main())

    @Test
    fun `status command is registered`() {
        val statusCmd = cmd.subcommands["status"]
        assertNotNull(statusCmd, "status subcommand should be registered")
    }

    @Test
    fun `status command has --all flag`() {
        val statusCmd = cmd.subcommands["status"]!!
        val options = statusCmd.commandSpec.options().map { it.longestName() }
        assertTrue("--all" in options)
    }

    @Test
    fun `status command --all has -a short alias`() {
        val statusCmd = cmd.subcommands["status"]!!
        val allNames = statusCmd.commandSpec.options().flatMap { it.names().toList() }
        assertTrue("-a" in allNames)
    }

    // UD-756 — HYDRATED / PENDING bucket split

    private fun fileEntry(
        path: String,
        size: Long,
        remoteId: String?,
        isHydrated: Boolean,
        isFolder: Boolean = false,
    ) = SyncEntry(
        path = path,
        remoteId = remoteId,
        remoteHash = remoteId?.let { "h" },
        remoteSize = if (remoteId != null) size else 0L,
        remoteModified = remoteId?.let { Instant.now() },
        localMtime = if (isHydrated) 1711627200000 else null,
        localSize = if (isHydrated) size else null,
        isFolder = isFolder,
        isPinned = false,
        isHydrated = isHydrated,
        lastSynced = if (remoteId != null) Instant.now() else Instant.EPOCH,
    )

    @Test
    fun `UD-756 mixed entries split into hydrated and pending buckets`() {
        // Three hydrated-and-uploaded files (HYDRATED bucket)
        // Two pending uploads (PENDING bucket)
        // One sparse placeholder (counted nowhere — bytes aren't on disk)
        // One folder (skipped)
        val entries =
            listOf(
                fileEntry("/a.bin", size = 1_000, remoteId = "r1", isHydrated = true),
                fileEntry("/b.bin", size = 2_000, remoteId = "r2", isHydrated = true),
                fileEntry("/c.bin", size = 3_000, remoteId = "r3", isHydrated = true),
                fileEntry("/pending1.bin", size = 5_000, remoteId = null, isHydrated = true),
                fileEntry("/pending2.bin", size = 7_000, remoteId = null, isHydrated = true),
                fileEntry("/sparse.bin", size = 9_000, remoteId = "r4", isHydrated = false),
                fileEntry("/folder", size = 0, remoteId = "rf", isHydrated = true, isFolder = true),
            )

        val buckets = computeLocalSizeBuckets(entries)

        assertEquals(6_000L, buckets.hydratedBytes, "hydrated = 1000 + 2000 + 3000")
        assertEquals(12_000L, buckets.pendingBytes, "pending = 5000 + 7000")
    }

    @Test
    fun `UD-756 only-pending profile reports zero hydrated`() {
        val entries =
            listOf(
                fileEntry("/p1.bin", size = 100, remoteId = null, isHydrated = true),
                fileEntry("/p2.bin", size = 200, remoteId = null, isHydrated = true),
            )
        val buckets = computeLocalSizeBuckets(entries)
        assertEquals(0L, buckets.hydratedBytes)
        assertEquals(300L, buckets.pendingBytes)
    }

    @Test
    fun `UD-756 only-hydrated profile reports zero pending`() {
        val entries =
            listOf(
                fileEntry("/h1.bin", size = 1_000, remoteId = "r1", isHydrated = true),
                fileEntry("/h2.bin", size = 2_000, remoteId = "r2", isHydrated = true),
            )
        val buckets = computeLocalSizeBuckets(entries)
        assertEquals(3_000L, buckets.hydratedBytes)
        assertEquals(0L, buckets.pendingBytes)
    }

    @Test
    fun `UD-756 sparse placeholder contributes nothing`() {
        // Sparse placeholders have isHydrated=false and localSize=null. They
        // belong in the SPARSE column, not in HYDRATED or PENDING.
        val entries =
            listOf(
                fileEntry("/s.bin", size = 10_000, remoteId = "r", isHydrated = false),
            )
        val buckets = computeLocalSizeBuckets(entries)
        assertEquals(0L, buckets.hydratedBytes)
        assertEquals(0L, buckets.pendingBytes)
    }

    // ── discoverProfilesFromRaw — `status --all` enumeration ────────────────

    @Test
    fun `discoverProfilesFromRaw enumerates two same-type profiles in declaration order`() {
        // Reproduces the BACKLOG scenario: two Internxt profiles configured
        // with the first declared as `[providers.internxt]` and the second
        // as `[providers.gernot_krost_internxt_pst]`. status --all must
        // surface BOTH, in declaration order (file top-to-bottom), not
        // alphabetical order and not just the second one.
        val toml =
            """
            |[general]
            |
            |[providers.internxt]
            |type = "internxt"
            |sync_root = "~/Internxt"
            |
            |[providers.gernot_krost_internxt_pst]
            |type = "internxt"
            |sync_root = "~/InternxtPst"
            """.trimMargin()
        val raw = SyncConfig.parseRaw(toml)
        val baseDir = Files.createTempDirectory("status-test-")
        try {
            val profiles = discoverProfilesFromRaw(raw, baseDir)
            assertEquals(
                listOf("internxt", "gernot_krost_internxt_pst"),
                profiles.map { it.name },
                "both profiles should be enumerated in declaration order",
            )
            assertTrue(profiles.all { it.type == "internxt" }, "both profiles should resolve as type=internxt")
        } finally {
            Files.deleteIfExists(baseDir)
        }
    }

    // ── shouldProbeRemoteForStatus — side-effect-free status invariant ─────

    @Test
    fun `shouldProbeRemoteForStatus returns true only for Ok health`() {
        // The structural invariant `unidrive status` depends on: a network
        // probe (which for Internxt cascades into authenticateInteractive
        // and prompts for credentials) is only attempted when the offline
        // health check has already said the persisted token is valid.
        assertTrue(shouldProbeRemoteForStatus(CredentialHealth.Ok))
    }

    @Test
    fun `shouldProbeRemoteForStatus returns false for ExpiresIn (stale token)`() {
        // The BACKLOG entry's repro: Internxt JWT past its `exp` claim.
        // InternxtProviderFactory.checkCredentialHealth returns
        // `ExpiresIn(0, "JWT expired — run 'unidrive auth'")` in this case.
        // status MUST treat that as "do not probe" — otherwise
        // provider.authenticateAndLog() drops into authenticateInteractive
        // and prompts for email/password/2FA mid-render.
        assertFalse(shouldProbeRemoteForStatus(CredentialHealth.ExpiresIn(0, "JWT expired — run 'unidrive auth'")))
    }

    @Test
    fun `shouldProbeRemoteForStatus returns false for Missing health`() {
        // No persisted credentials at all → cannot probe; the cached cells
        // are all the status command has to render.
        assertFalse(shouldProbeRemoteForStatus(CredentialHealth.Missing("No credentials file — run 'unidrive auth'")))
    }

    @Test
    fun `shouldProbeRemoteForStatus returns false for Warning health`() {
        // Generic warning from the factory — also a no-probe signal.
        assertFalse(shouldProbeRemoteForStatus(CredentialHealth.Warning("Token is malformed")))
    }

    @Test
    fun `stale glyph label uses the warn-tier rendering with the literal STALE text`() {
        // The BACKLOG worked example explicitly names `⚠︎ STALE` as the
        // visible cell rendering when a profile's token is past expiry.
        // GlyphRenderer.warnLabel("STALE") is the production call;
        // assert both the unicode rendering AND the ASCII fallback so the
        // contract is pinned regardless of stdout encoding.
        val label = GlyphRenderer.warnLabel("STALE")
        // GlyphRenderer.warnLabel produces either `[⚠︎ STALE]` (U+26A0 + VS15)
        // or `[! STALE]` in ASCII mode. Both must contain the literal
        // `STALE` so the user can recognise the cell at a glance.
        assertTrue("STALE" in label, "expected stale glyph to contain STALE, got: $label")
        assertTrue(label.startsWith("[") && label.endsWith("]"), "expected bracketed label, got: $label")
    }

    @Test
    fun `discoverProfilesFromRaw preserves declaration order across mixed provider types`() {
        // Two onedrive profiles + one internxt profile interleaved in file
        // order: the helper must NOT regroup by type or sort alphabetically.
        // (Grouping by type happens later in `showMultiProviderStatus`; the
        // discovery step is purely about completeness + order.)
        val toml =
            """
            |[providers.work_od]
            |type = "onedrive"
            |
            |[providers.personal_inxt]
            |type = "internxt"
            |
            |[providers.archive_od]
            |type = "onedrive"
            """.trimMargin()
        val raw = SyncConfig.parseRaw(toml)
        val baseDir = Files.createTempDirectory("status-test-")
        try {
            val profiles = discoverProfilesFromRaw(raw, baseDir)
            assertEquals(
                listOf("work_od", "personal_inxt", "archive_od"),
                profiles.map { it.name },
            )
        } finally {
            Files.deleteIfExists(baseDir)
        }
    }

    // ── #117: orphan-profile enumeration invariants ──────────────────────────

    @Test
    fun `#117 orphan dir (no config_toml entry) appears in discoverProfilesFromRaw flagged isOrphan`() {
        // The root bug: a profile directory created by `unidrive auth` against a
        // type-resolved name (e.g. `unidrive -p my-personal-drive auth`) writes a
        // token to ~/.config/unidrive/my-personal-drive/ but does NOT write a
        // [providers.my-personal-drive] section to config.toml if the user forgot
        // that step. Pre-fix: discoverProfilesFromRaw only walked raw.providers;
        // the orphan dir was invisible to `status --all`.
        val toml =
            """
            |[general]
            |
            |[providers.onedrive]
            |type = "onedrive"
            |sync_root = "~/OneDrive"
            """.trimMargin()
        val raw = SyncConfig.parseRaw(toml)
        val baseDir = Files.createTempDirectory("status-orphan-test-")
        try {
            // Declared profile dir
            Files.createDirectories(baseDir.resolve("onedrive"))
            // Orphan dir — present on disk, absent from config.toml
            Files.createDirectories(baseDir.resolve("my-personal-drive"))

            val profiles = discoverProfilesFromRaw(raw, baseDir)

            val names = profiles.map { it.name }
            assertTrue("onedrive" in names, "declared profile must appear")
            assertTrue("my-personal-drive" in names, "orphan profile must appear in --all view")

            val orphan = profiles.single { it.name == "my-personal-drive" }
            assertTrue(orphan.isOrphan, "orphan profile must be flagged isOrphan=true")

            val declared = profiles.single { it.name == "onedrive" }
            assertFalse(declared.isOrphan, "declared profile must NOT be flagged isOrphan")
        } finally {
            baseDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `#117 declared profile with its dir present is NOT flagged orphan`() {
        val toml =
            """
            |[providers.internxt]
            |type = "internxt"
            |sync_root = "~/Internxt"
            """.trimMargin()
        val raw = SyncConfig.parseRaw(toml)
        val baseDir = Files.createTempDirectory("status-orphan-test-")
        try {
            Files.createDirectories(baseDir.resolve("internxt"))
            val profiles = discoverProfilesFromRaw(raw, baseDir)
            val profile = profiles.single { it.name == "internxt" }
            assertFalse(profile.isOrphan, "a declared+present profile is not orphan")
        } finally {
            baseDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `#117 declared-but-no-dir profile appears and is NOT flagged orphan`() {
        // Sensible behaviour for a declared profile whose dir does not yet exist
        // (e.g. the user added [providers.onedrive] but has not yet run auth):
        // the profile still appears so the table can show [✘ AUTH] or [– –],
        // and it is NOT flagged as orphan (it has a config.toml section).
        val toml =
            """
            |[providers.onedrive]
            |type = "onedrive"
            """.trimMargin()
        val raw = SyncConfig.parseRaw(toml)
        val baseDir = Files.createTempDirectory("status-orphan-test-")
        try {
            // Intentionally do NOT create baseDir/onedrive
            val profiles = discoverProfilesFromRaw(raw, baseDir)
            val profile = profiles.single { it.name == "onedrive" }
            assertFalse(profile.isOrphan, "declared (no dir yet) profile is not orphan")
        } finally {
            baseDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `#117 hidden dirs under config baseDir are not surfaced as orphan profiles`() {
        // Directories starting with '.' (e.g. .cache, .tmp) must never appear
        // as profile rows — they are OS/tool artifacts, not profiles.
        val raw = SyncConfig.parseRaw("[general]\n")
        val baseDir = Files.createTempDirectory("status-orphan-test-")
        try {
            Files.createDirectories(baseDir.resolve(".hidden-artifact"))
            Files.createDirectories(baseDir.resolve("real-orphan"))
            val profiles = discoverProfilesFromRaw(raw, baseDir)
            val names = profiles.map { it.name }
            assertFalse(".hidden-artifact" in names, "hidden dirs must not appear as profiles")
            assertTrue("real-orphan" in names, "non-hidden orphan dir must appear")
        } finally {
            baseDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `#117 orphan profile row uses ORPHAN status label`() {
        // Rendering parity invariant: both views produce an [ORPHAN] label (not
        // [AUTH], [ERR], or [– –]) for an orphan profile — the user needs to know
        // the profile is undeclared, not merely unauthenticated.
        val label = GlyphRenderer.orphanLabel()
        assertTrue("ORPHAN" in label, "expected orphan label to contain ORPHAN, got: $label")
        assertTrue(label.startsWith("[") && label.endsWith("]"), "expected bracketed label, got: $label")
    }

    @Test
    fun `#117 buildOrphanProfile creates ProfileInfo with isOrphan=true`() {
        val profile = buildOrphanProfile("my-lost-profile")
        assertEquals("my-lost-profile", profile.name)
        assertTrue(profile.isOrphan)
        assertFalse(profile.rawProvider != null, "orphan has no rawProvider")
    }

    // ── PR #196 review fix: configured/type profiles take precedence over orphan-dir ──

    @Test
    fun `PR196 configured type=onedrive profile AND stale orphan dir named onedrive resolves via config not orphan`() {
        // Regression guard: with a [providers.personal] type="onedrive" config AND an
        // orphan dir literally named "onedrive/", `-p onedrive status` must go through
        // the config path — NOT the orphan-dir fallback.  Pre-fix: discoverProfiles()
        // found the orphan dir first and returned it with isOrphan=true, producing
        // "[? ORPHAN]" instead of the configured account's real status.
        val toml =
            """
            |[general]
            |
            |[providers.personal]
            |type = "onedrive"
            |sync_root = "~/OneDrive"
            """.trimMargin()
        val raw = SyncConfig.parseRaw(toml)
        val baseDir = Files.createTempDirectory("pr196-test-")
        try {
            // Stale orphan dir — present on disk, no matching [providers.onedrive] section
            Files.createDirectories(baseDir.resolve("onedrive"))

            // The precedence gate must return true for "onedrive" because "onedrive"
            // is a known provider type — resolveCurrentProfile() takes priority.
            assertTrue(
                resolvesSingleProfileViaConfig("onedrive", raw),
                "known provider type 'onedrive' must route through the config path, not the orphan fallback",
            )
            // Also assert the stale dir IS visible as an orphan in --all view (unchanged #117 behaviour)
            val allProfiles = discoverProfilesFromRaw(raw, baseDir)
            val orphan = allProfiles.find { it.name == "onedrive" }
            assertNotNull(orphan, "orphan dir must still appear in --all view (#117 behaviour preserved)")
            assertTrue(orphan.isOrphan, "orphan dir must be flagged isOrphan=true in --all view")
        } finally {
            baseDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `PR196 genuinely orphan name (no configured profile no type match) routes to orphan fallback`() {
        // The #117 addition must still work for names that have NO config section
        // AND are not a known provider type (e.g. a custom profile dir the user
        // created by hand before adding the config.toml section).
        val toml =
            """
            |[general]
            |
            |[providers.onedrive]
            |type = "onedrive"
            |sync_root = "~/OneDrive"
            """.trimMargin()
        val raw = SyncConfig.parseRaw(toml)
        val baseDir = Files.createTempDirectory("pr196-test-")
        try {
            Files.createDirectories(baseDir.resolve("my-lost-profile"))

            // "my-lost-profile" is neither in raw.providers nor a known type
            assertFalse(
                resolvesSingleProfileViaConfig("my-lost-profile", raw),
                "non-configured non-type name must NOT route through config path",
            )
            // The orphan dir IS visible in --all view (unchanged #117 behaviour)
            val allProfiles = discoverProfilesFromRaw(raw, baseDir)
            val orphan = allProfiles.find { it.name == "my-lost-profile" }
            assertNotNull(orphan, "orphan dir must appear in --all view")
            assertTrue(orphan.isOrphan, "orphan dir must be flagged isOrphan=true")
        } finally {
            baseDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `#117 declared and orphan profiles share identical column structure via AccountRow`() {
        // Rendering parity: both a declared profile row and an orphan profile row
        // are [AccountRow] instances with the same set of fields (same columns).
        // This pins the invariant: a future refactor that adds columns to one path
        // but not the other will fail here.
        val declaredFields = AccountRow::class.java.declaredFields.map { it.name }.toSet()
        // An orphan row is still an AccountRow — same class, same fields.
        val orphanFields = AccountRow::class.java.declaredFields.map { it.name }.toSet()
        assertEquals(declaredFields, orphanFields, "AccountRow column structure must be identical for all rows")
        // Also assert the fields we pin as essential for the table (snapshot)
        assertTrue("profileName" in declaredFields)
        assertTrue("status" in declaredFields)
        assertTrue("statusLabel" in declaredFields)
        assertTrue("sparse" in declaredFields)
        assertTrue("cloudSize" in declaredFields)
        assertTrue("hydratedSize" in declaredFields)
        assertTrue("pendingSize" in declaredFields)
        assertTrue("lastSync" in declaredFields)
    }
}
