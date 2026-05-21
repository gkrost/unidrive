# Testing JSON serialization for paths with `"` / `\` / newline — extract the formatter, don't round-trip through the FS

**Surfaced 2026-05-17 fixing PR #46's Codex P2 review on UD-264's `skipped-ops.jsonl` writer.**

## Problem

PR #46 reviewer flagged: the new `skipped-ops.jsonl` writer in `SyncEngine.logSkippedOp` used a hand-built triple-quoted JSON template:

```kotlin
val line = """{"ts":"${Instant.now()}","action":"$kind","path":"${action.path}","reason":"$reason"}"""
```

For any path containing `"`, `\`, or a newline, this produces invalid JSON. Linux + cloud filesystems happily store such filenames; the 2026-05-17 audit observed `/\ninternxt-cli.desktop` (a real Internxt path with an embedded `\n`) in `failures.jsonl`.

The fix is straightforward — replace the template with `kotlinx.serialization.json.buildJsonObject`. The test isn't.

## Why a straightforward end-to-end test doesn't work on Windows

The natural test shape: seed a `SyncEntry` whose path contains `"`, `\`, `\n`, run `engine.syncOnce(dryRun = true)`, then read `skipped-ops.jsonl` back and assert it parses. This blows up immediately on Windows:

```
java.nio.file.InvalidPathException: Illegal char <"> at index 4: /Doc "with" \back and
newline
    at java.base/sun.nio.fs.WindowsPathParser.normalize(WindowsPathParser.java:204)
    at java.base/sun.nio.fs.WindowsPath.parse(WindowsPath.java:92)
    at java.base/java.nio.file.Path.resolve(Path.java:516)
    at org.krost.unidrive.sync.PlaceholderManagerKt.safeResolveLocal
    at org.krost.unidrive.sync.LocalScanner.scan
    at org.krost.unidrive.sync.SyncEngine.doSyncOnce
```

`LocalScanner` walks the seeded DB entry's path and calls `Path.resolve(rel)`. Windows refuses to construct a `Path` for any string containing `< > : " / \ | ? *` or control characters (0x00-0x1F). The test crashes before any code under test runs.

The intersection of "JSON-special chars" and "Windows-illegal filename chars" is `"`, `\`, and control chars 0x00-0x1F. Every char that *can* break the JSON template *will* fail Windows-path construction. So the bug exists for real paths in production on Linux / cloud, but reproducing it through an end-to-end test on a Windows dev machine (or CI runner) is impossible without forking the FS abstraction.

## What to do

Extract the JSON-formatting logic into a pure function that takes `(action: String, path: String, reason: String, ts: Instant)` and returns the JSON line as a string. Test that function directly with nasty inputs — no FS, no `LocalScanner`, no path construction.

```kotlin
// In SyncEngine.kt — Companion is convenient because tests don't need an instance.
companion object {
    internal fun formatSkippedOpJson(
        action: String,
        path: String,
        reason: String,
        ts: Instant,
    ): String =
        kotlinx.serialization.json.buildJsonObject {
            put("ts", JsonPrimitive(ts.toString()))
            put("action", JsonPrimitive(action))
            put("path", JsonPrimitive(path))
            put("reason", JsonPrimitive(reason))
        }.toString()
}

// The actual writer delegates to it.
private fun logSkippedOp(action: SyncAction, reason: String) {
    val line = formatSkippedOpJson(actionLabel(action), action.path, reason, Instant.now())
    Files.writeString(logPath, line + "\n", CREATE, APPEND)
}
```

Then the test exercises the exact hazard with no FS dependency:

```kotlin
@Test fun `PR46-Codex formatSkippedOpJson is parseable for paths with JSON-special chars`() {
    val nasty = "/Doc \"with\" \\back and\nnewline\t\r"
    val line = SyncEngine.formatSkippedOpJson("del-remote", nasty, "top_level_never_hydrated", Instant.parse("2026-05-17T10:00:00Z"))
    val obj = Json.parseToJsonElement(line) as JsonObject
    assertEquals(nasty, (obj["path"] as JsonPrimitive).content)
}
```

This is the pattern: **when the production code path the test wants to drive crosses a layer the OS rejects, lift the hazardous operation into a unit-testable helper.** Don't try to defeat the OS; route around it.

## Generalisation

The same shape recurs anywhere production code handles values the test environment can't construct:

- Path strings with chars Windows forbids in filenames.
- HTTP headers with bytes that Java's `URI` rejects but a real server might emit.
- Unicode normalisation forms that the JVM normalises on the way in but a cloud service emits raw.
- Filesystem timestamps with nanosecond precision Windows rounds.

In each case: don't try to round-trip through the layer that rejects the value. Extract the format / parse / hash / validate logic and unit-test the pure function with the hazardous inputs the production code path can encounter.

## Cross-refs

- UD-264 (closed by PR #46) — the fix that landed.
- UD-264a (open) — same bug class in `logFailure`; same `formatFailureLogJson`-helper-then-unit-test pattern applies.
- 2026-05-17 audit report §"Corrections" — the observed `/\ninternxt-cli.desktop` path that proves this is not hypothetical.
- `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt` — `Companion.formatSkippedOpJson` is the canonical lifted-helper for this lesson.
- `core/app/sync/src/test/kotlin/org/krost/unidrive/sync/SyncEngineTest.kt` — `UD-264 PR46-Codex formatSkippedOpJson is parseable for paths with JSON-special chars` is the canonical lifted-helper test.
