# Sub-project 1: Default Ignore List — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop syncing desktop/OS/editor junk (`.directory.lock`, `.DS_Store`, `Thumbs.db`, `~$*`, `*.swp`, `*.tmp`, …) to the cloud by adding a consolidated default-exclude set, while keeping user-configured patterns additive.

**Architecture:** Today the internal excludes (`/.unidrive-trash/**`, `/.unidrive-versions/**`) are hardcoded inline at `SyncEngine.kt:118-119`, invisible to `doctor` and to `SyncConfig.effectiveExcludePatterns`. This sub-project consolidates them **and** the new junk list into a single `SyncConfig.DEFAULT_EXCLUDE_PATTERNS` companion: `effectiveExcludePatterns` prepends it (so `doctor` sees the same set), and `SyncEngine` references the constant instead of an inline list (so every construction path — production and tests — applies the defaults, with `.distinct()` deduping when the caller already routed through `effectiveExcludePatterns`). The existing `Reconciler.matchesGlob` matcher is reused unchanged (its `else` branch routes any char incl. `$` through `Regex.escape`, so `~$*` works).

**Tech Stack:** Kotlin, Gradle (`./gradlew :app:sync:test --console=plain`), kotlin.test, JUnit.

**Spec:** `docs/dev/specs/sync-engine-stability-design.md` (v3), Sub-project 1.

**Branch / worktree:** `feat/sync-engine-stability` at `/tmp/ud-sse`.

---

### Task 1: `SyncConfig.DEFAULT_EXCLUDE_PATTERNS` + fold into `effectiveExcludePatterns`

**Files:**
- Modify: `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncConfig.kt` (the `effectiveExcludePatterns` fun at ~line 315 + add a companion object)
- Test: `core/app/sync/src/test/kotlin/org/krost/unidrive/sync/SyncConfigTest.kt`

- [ ] **Step 1: Write the failing test**

Add to `SyncConfigTest`:

```kotlin
@Test
fun `effectiveExcludePatterns includes the default junk + internal excludes`() {
    val cfg = SyncConfig.load(Files.createTempDirectory("ude").resolve("missing.toml"))
    val eff = cfg.effectiveExcludePatterns("any-profile")
    // internal (consolidated from the former SyncEngine hardcode)
    assertTrue("/.unidrive-trash/**" in eff)
    assertTrue("/.unidrive-versions/**" in eff)
    // representative junk
    assertTrue("**/.directory.lock" in eff)
    assertTrue("**/Thumbs.db" in eff)
    assertTrue("**/~\$*" in eff)
    // the constant is the single source of truth
    assertTrue(SyncConfig.DEFAULT_EXCLUDE_PATTERNS.all { it in eff })
}

@Test
fun `effectiveExcludePatterns keeps user global + per-provider patterns additive`() {
    val toml = Files.createTempDirectory("ude").resolve("config.toml")
    Files.writeString(
        toml,
        """
        [general]
        exclude = ["**/secret-*"]
        [providers.p1]
        type = "internxt"
        sync_root = "/tmp/x"
        exclude = ["**/p1-only.bin"]
        """.trimIndent(),
    )
    val eff = SyncConfig.load(toml).effectiveExcludePatterns("p1")
    assertTrue("**/secret-*" in eff)        // user global still present
    assertTrue("**/p1-only.bin" in eff)     // per-provider still present
    assertTrue("**/.directory.lock" in eff) // defaults still present
}
```

(Confirm the exact `SyncConfig.load(...)` entry point + the `[general] exclude` TOML key name by reading `SyncConfig.kt`; the second test's TOML must match the real schema. If the loader signature differs, adjust the test setup to match — do not change production behavior to fit the test.)

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:sync:test --tests '*SyncConfigTest*' -q --console=plain`
Expected: FAIL — `DEFAULT_EXCLUDE_PATTERNS` unresolved / defaults not in `effectiveExcludePatterns`.

- [ ] **Step 3: Implement**

In `SyncConfig.kt`, add a companion object (place it with the other class members) and prepend it in `effectiveExcludePatterns`:

```kotlin
companion object {
    /**
     * Patterns excluded from sync for every profile, before any user
     * (TOML global / per-provider / CLI --exclude) patterns. Consolidates
     * the unidrive-internal excludes (formerly hardcoded in SyncEngine)
     * with desktop/OS/editor junk that must never reach the cloud.
     * Matched by Reconciler.matchesGlob (handles `**/`, `*`, `?`, and
     * escapes other chars incl. `$`).
     */
    val DEFAULT_EXCLUDE_PATTERNS: List<String> = listOf(
        // unidrive-internal
        "/.unidrive-trash/**",
        "/.unidrive-versions/**",
        // desktop / OS / editor junk
        "**/.directory.lock", // KDE Dolphin
        "**/.DS_Store",       // macOS
        "**/._*",             // macOS AppleDouble
        "**/Thumbs.db",       // Windows
        "**/ehthumbs.db",     // Windows
        "**/desktop.ini",     // Windows
        "**/~\$*",            // MS Office lock files
        "**/*.part",          // partial downloads
        "**/*.crdownload",    // Chrome partial download
        "**/*.swp",           // vim swap
        "**/*.swx",           // vim swap
        "**/*.tmp",           // generic temp
        "**/*~",              // generic backup
    )
}

fun effectiveExcludePatterns(providerId: String): List<String> =
    DEFAULT_EXCLUDE_PATTERNS + globalExcludePatterns + (providers[providerId]?.excludePatterns ?: emptyList())
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :app:sync:test --tests '*SyncConfigTest*' -q --console=plain`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncConfig.kt \
        core/app/sync/src/test/kotlin/org/krost/unidrive/sync/SyncConfigTest.kt
git commit -m "feat(sync): SyncConfig.DEFAULT_EXCLUDE_PATTERNS consolidates internal + junk excludes"
```

---

### Task 2: `SyncEngine` references the constant (drop the inline hardcode)

**Files:**
- Modify: `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt:117-119`
- Test: `core/app/sync/src/test/kotlin/org/krost/unidrive/sync/LocalScannerTest.kt` (proves the default set excludes junk through the real scanner that `SyncEngine` feeds)

- [ ] **Step 1: Write the failing test**

Add to `LocalScannerTest` (its `setUp` already provides `syncRoot` + `db`):

```kotlin
@Test
fun `default excludes skip desktop and editor junk but keep real files`() {
    // A scanner configured with the production default set.
    val excluded = LocalScanner(syncRoot, db, SyncConfig.DEFAULT_EXCLUDE_PATTERNS)
    Files.writeString(syncRoot.resolve(".directory.lock"), "x")
    Files.writeString(syncRoot.resolve("Thumbs.db"), "x")
    Files.writeString(syncRoot.resolve("~\$report.docx"), "x")
    Files.writeString(syncRoot.resolve("notes.txt.swp"), "x")
    Files.writeString(syncRoot.resolve("draft.tmp"), "x")
    Files.writeString(syncRoot.resolve("real.txt"), "keep me")

    val changes = excluded.scan()

    assertEquals(ChangeState.NEW, changes["/real.txt"], "real file must sync")
    assertNull(changes["/.directory.lock"], ".directory.lock must be excluded")
    assertNull(changes["/Thumbs.db"], "Thumbs.db must be excluded")
    assertNull(changes["/~\$report.docx"], "Office lock file must be excluded")
    assertNull(changes["/notes.txt.swp"], "vim swap must be excluded")
    assertNull(changes["/draft.tmp"], "temp file must be excluded")
}
```

- [ ] **Step 2: Run to verify it fails / passes**

Run: `./gradlew :app:sync:test --tests '*LocalScannerTest*' -q --console=plain`
Expected: this test PASSES already if Task 1 landed `DEFAULT_EXCLUDE_PATTERNS` (LocalScanner + matchesGlob already honor the patterns) — it is the regression guard that the *set* is correct. If any junk file appears in `changes`, the pattern for it is wrong — fix the pattern in `DEFAULT_EXCLUDE_PATTERNS`, not the test.

- [ ] **Step 3: Implement the SyncEngine consolidation**

In `SyncEngine.kt`, replace the inline hardcode (lines 117-119):

```kotlin
private val effectiveExcludePatterns =
    validateExcludePatterns(
        excludePatterns + listOf("/.unidrive-trash/**", "/.unidrive-versions/**"),
    )
```

with a reference to the single source, deduped (the production caller at `SyncCommand.kt:418` passes `config.effectiveExcludePatterns(...)`, which now already contains the defaults — `.distinct()` prevents doubling; test/daemon callers that pass `emptyList()` still get the defaults):

```kotlin
private val effectiveExcludePatterns =
    validateExcludePatterns(
        (SyncConfig.DEFAULT_EXCLUDE_PATTERNS + excludePatterns).distinct(),
    )
```

Leave `private val scanner = LocalScanner(syncRoot, db, effectiveExcludePatterns)` and the `Reconciler(...)` line below it unchanged — they now receive the consolidated set.

- [ ] **Step 4: Run the full sync module tests (no regression)**

Run: `./gradlew :app:sync:test -q --console=plain > /tmp/sp1-t2.log 2>&1 || grep -E "FAILED|ERROR|Exception" -C3 /tmp/sp1-t2.log | head -40`
Expected: all green. (Existing `SyncEngine` construction sites in tests pass `emptyList()` and now transparently get the defaults — confirm none asserted the *absence* of the defaults.)

- [ ] **Step 5: Commit**

```bash
git add core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt \
        core/app/sync/src/test/kotlin/org/krost/unidrive/sync/LocalScannerTest.kt
git commit -m "feat(sync): SyncEngine uses SyncConfig.DEFAULT_EXCLUDE_PATTERNS (drop inline hardcode)"
```

---

### Task 3: Glob-coverage regression test for the tricky patterns

**Files:**
- Test: `core/app/sync/src/test/kotlin/org/krost/unidrive/sync/ReconcilerTest.kt` (it already tests `Reconciler.matchesGlob`)

Rationale: `~$*` (the `$` escaping), the `**/` root-vs-nested behavior, and the `*.swp` basename match are the patterns most likely to silently break if `matchesGlob` ever changes. Pin them directly against the matcher so a future matcher edit can't regress the ignore list undetected.

- [ ] **Step 1: Write the test**

Add to `ReconcilerTest` (use the same `Reconciler.matchesGlob` access the existing matchesGlob tests use — confirm whether it's `Reconciler.matchesGlob(path, pattern)` via the companion):

```kotlin
@Test
fun `default exclude patterns match their target paths at root and nested`() {
    fun m(path: String, pat: String) = Reconciler.matchesGlob(path, pat)
    // ~$* — the $ must be escaped (else-branch Regex.escape), root + nested
    assertTrue(m("/~\$report.docx", "**/~\$*"))
    assertTrue(m("/Documents/~\$report.docx", "**/~\$*"))
    // **/ matches both root-level and nested
    assertTrue(m("/.directory.lock", "**/.directory.lock"))
    assertTrue(m("/a/b/.directory.lock", "**/.directory.lock"))
    // *.swp matches dotfile-prefixed swap (vim writes .name.swp)
    assertTrue(m("/.notes.txt.swp", "**/*.swp"))
    // negative: a real file must NOT match a junk pattern
    assertFalse(m("/report.docx", "**/~\$*"))
    assertFalse(m("/important.txt", "**/*.tmp"))
}
```

- [ ] **Step 2: Run to verify it passes**

Run: `./gradlew :app:sync:test --tests '*ReconcilerTest*' -q --console=plain`
Expected: PASS. If `~$*` fails, `buildGlobRegex` is not escaping `$` (it should via the `else` branch) — investigate `Reconciler.buildGlobRegex` before changing the pattern.

- [ ] **Step 3: Commit**

```bash
git add core/app/sync/src/test/kotlin/org/krost/unidrive/sync/ReconcilerTest.kt
git commit -m "test(sync): pin default-exclude glob matching ($-escape, **/ root-vs-nested, swap)"
```

---

### Task 4: Full-module verification + finish

- [ ] **Step 1: Run the affected modules**

Run: `./gradlew :app:sync:test :app:cli:test :app:hydration:test -q --console=plain > /tmp/sp1-final.log 2>&1 || grep -E "FAILED|ERROR|Exception" -C3 /tmp/sp1-final.log | head -50`
Expected: all green. (`:app:cli` covers `doctor`, which now reports the consolidated set; `:app:hydration` constructs `SyncEngine` in its tests.)

- [ ] **Step 2: Sanity-check `doctor` sees the defaults**

`doctor` reads `effectiveExcludePatterns` for its local-orphan check; confirm `DoctorCommandTest` still passes and (optional) that a `.directory.lock` in a sync_root is reported as excluded, not as a local orphan.

- [ ] **Step 3: Hand off**

SP1 complete. Per `superpowers:subagent-driven-development`, after the final review use `superpowers:finishing-a-development-branch`. SP2 (delete-detection) and SP3 (reconcile-in-daemon) are separate plans on this same branch/spec.

---

## Self-review

- **Spec coverage:** SP1 requires (a) a `DEFAULT_EXCLUDE_PATTERNS` companion [Task 1], (b) fold into `effectiveExcludePatterns` so `doctor`/`LocalScanner`/`Reconciler` see one set [Task 1 + Task 4 doctor check + Task 2 scanner], (c) remove the `SyncEngine.kt:119` hardcode [Task 2], (d) keep user patterns additive [Task 1 test 2], (e) reuse `matchesGlob` [Task 3 pins it]. All covered.
- **Placeholder scan:** none — every step has concrete code/commands. The two "confirm the exact entry point" notes are verification instructions (read the real signature), not deferred work; the test code is complete.
- **Type consistency:** `DEFAULT_EXCLUDE_PATTERNS: List<String>` used identically in SyncConfig (def), SyncEngine (consumer), LocalScannerTest, ReconcilerTest. `effectiveExcludePatterns(providerId): List<String>` signature unchanged (only the body prepends). `matchesGlob(path, pattern)` matches the existing companion signature read at `Reconciler.kt:1037`.
- **Risk:** the only behavioral risk is an existing test asserting a junk-like filename *should* sync — Task 2 Step 4 runs the full module to catch it.
