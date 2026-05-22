# Hydration SPI (Phase 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land Phase 1 of the sparse-hydration roadmap — a thin verb-based SPI at `core/app/hydration/` that wraps existing engine paths and is consumable over UDS-IPC by the future Rust FUSE co-daemon.

**Architecture:** New Gradle module `app:hydration` depending on `app:sync` and `app:core`. Adds a small inbound-handler shape to the existing `IpcServer` (currently broadcast-only) and registers six hydration verbs as JSON-line RPCs. Verbs delegate to `SyncEngine` via two new narrow public methods (`ensureHydrated`, `uploadFromCache`). State is read from `StateDatabase`; hydration events are a `Flow<HydrationEvent>` with a connection-scoped open-set so a co-daemon crash cleans up cleanly.

**Tech Stack:** Kotlin 2.x, Gradle 9.x, kotlinx-coroutines (Flow + Channel), JUnit 5, SQLite via existing `StateDatabase`. No new third-party dependencies.

**Spec:** `docs/dev/specs/sparse-hydration-roadmap-design.md`.

**Spec-gap noted during planning:** the spec said new verbs would land "matching the existing IpcServer protocol". The existing `IpcServer` is broadcast-only; the wire format (JSON-line) is preserved but the capability shape extends to support inbound request handlers. This is captured in Task 3.

---

## File structure

### Files created

| Path | Purpose |
|---|---|
| `core/app/hydration/build.gradle.kts` | New Gradle module declaration. Depends on `app:core`, `app:sync`. JUnit 5 test config. |
| `core/app/hydration/src/main/kotlin/org/krost/unidrive/hydration/Hydration.kt` | The `Hydration` interface — five verbs + `events` flow. |
| `core/app/hydration/src/main/kotlin/org/krost/unidrive/hydration/HydrationError.kt` | Sealed interface starting with one `Generic(message: String)` variant. |
| `core/app/hydration/src/main/kotlin/org/krost/unidrive/hydration/HydrationEvent.kt` | Sealed class — `Hydrating`, `Hydrated`, `Dehydrated`, `Failed`. |
| `core/app/hydration/src/main/kotlin/org/krost/unidrive/hydration/HydrationImpl.kt` | The implementation. Wraps `SyncEngine` + `StateDatabase`, owns the connection-scoped open-set, emits events. |
| `core/app/hydration/src/main/kotlin/org/krost/unidrive/hydration/HydrationIpcHandler.kt` | Registers six verbs on the extended `IpcServer`. Translates JSON requests ↔ verb calls. |
| `core/app/hydration/src/test/kotlin/org/krost/unidrive/hydration/HydrationImplTest.kt` | Six unit tests (cold/warm read, dehydrate refusal, events, close-handle robustness, IPC-disconnect cleanup). |
| `core/app/hydration/src/test/kotlin/org/krost/unidrive/hydration/HydrationIpcHandlerTest.kt` | One unit test pinning JSON-line round-trip for the six verbs. |
| `core/app/hydration/src/test/kotlin/org/krost/unidrive/hydration/HydrationSmokeTest.kt` | Live-integration smoke (gated by `UNIDRIVE_INTEGRATION_TESTS=true`). Third sync smoke in the 5+5+2 target. |

### Files modified

| Path | Change |
|---|---|
| `core/settings.gradle.kts` | Add `"app:hydration"` to the `include(...)` list. |
| `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcServer.kt` | Add `registerHandler(verb, handler)` + per-client reader coroutine that dispatches inbound JSON-line requests to registered handlers. |
| `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt` | Add two new public suspend functions: `ensureHydrated(path: String): Path` (returns cache path) and `uploadFromCache(path: String, cachePath: Path)`. Both delegate to existing private paths. |
| `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/SyncCommand.kt` (or wherever IpcServer is constructed) | Construct `HydrationImpl`, construct `HydrationIpcHandler`, wire to `IpcServer` after `start()`. |

---

## Tasks

Each task is one logical commit. Steps within a task are 2–5 minutes each.

### Task 1: Create the `app:hydration` Gradle module

**Files:**
- Create: `core/app/hydration/build.gradle.kts`
- Modify: `core/settings.gradle.kts`

- [ ] **Step 1: Read three nearby module configs**

Open and skim `core/app/sync/build.gradle.kts`, `core/app/sync-tracking/build.gradle.kts`, and `core/app/core/build.gradle.kts`. The new module should match their plugin set, Java/Kotlin target, and test-runner config.

- [ ] **Step 2: Write `core/app/hydration/build.gradle.kts`**

```kotlin
plugins {
    id("buildlogic.kotlin-library")  // or whatever the existing modules use; copy from sync's file
}

dependencies {
    implementation(project(":app:core"))
    implementation(project(":app:sync"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}
```

Match the exact plugin id and `libs.*` references to what `core/app/sync/build.gradle.kts` uses. Don't introduce new dependencies.

- [ ] **Step 3: Register the module in `core/settings.gradle.kts`**

Old:
```kotlin
include(
    "app:core", "app:sync", "app:sync-tracking", "app:cli", "app:config",
    "providers:internxt", "providers:onedrive",
)
```

New:
```kotlin
include(
    "app:core", "app:sync", "app:sync-tracking", "app:hydration", "app:cli", "app:config",
    "providers:internxt", "providers:onedrive",
)
```

- [ ] **Step 4: Verify the empty module configures cleanly**

Run from `core/`:
```
./gradlew :app:hydration:check -q
```

Expected: `BUILD SUCCESSFUL` (no tests yet, but Gradle configures + nothing fails).

- [ ] **Step 5: Commit**

```bash
git add core/settings.gradle.kts core/app/hydration/build.gradle.kts
git commit -m "feat(hydration): scaffold app:hydration module"
```

### Task 2: `HydrationError` sealed interface

**Files:**
- Create: `core/app/hydration/src/main/kotlin/org/krost/unidrive/hydration/HydrationError.kt`
- Create: `core/app/hydration/src/test/kotlin/org/krost/unidrive/hydration/HydrationErrorTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package org.krost.unidrive.hydration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HydrationErrorTest {
    @Test
    fun `generic carries the message verbatim`() {
        val e = HydrationError.Generic("disk full")
        assertEquals("disk full", e.message)
    }

    @Test
    fun `sealed interface allows future variants without breaking exhaustiveness`() {
        val e: HydrationError = HydrationError.Generic("x")
        val rendered = when (e) {
            is HydrationError.Generic -> "generic:${e.message}"
            // Note: this 'when' is intentionally non-exhaustive at the language level
            // because HydrationError is a sealed *interface* (open for extension by
            // sub-interfaces in callers). The test pins that Generic is matchable.
        }
        assertTrue(rendered.startsWith("generic:"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```
./gradlew :app:hydration:test --tests HydrationErrorTest -q
```

Expected: FAIL — `HydrationError` doesn't exist yet.

- [ ] **Step 3: Write the minimal implementation**

```kotlin
package org.krost.unidrive.hydration

/**
 * Sealed extension point for hydration failure modes. Phase 1 ships
 * only Generic; Phase 3 adds Transient / Permanent / QuotaExhausted /
 * Busy as the icon-overlay UX surfaces them.
 */
sealed interface HydrationError {
    val message: String

    data class Generic(override val message: String) : HydrationError
}
```

- [ ] **Step 4: Run test to verify it passes**

```
./gradlew :app:hydration:test --tests HydrationErrorTest -q
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/app/hydration/src/main/kotlin/org/krost/unidrive/hydration/HydrationError.kt \
        core/app/hydration/src/test/kotlin/org/krost/unidrive/hydration/HydrationErrorTest.kt
git commit -m "feat(hydration): HydrationError sealed interface extension point"
```

### Task 3: Extend `IpcServer` with inbound handlers

**Files:**
- Modify: `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcServer.kt`
- Modify: `core/app/sync/src/test/kotlin/org/krost/unidrive/sync/IpcServerTest.kt`

**Context to read first:** `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcServer.kt` start lines (around line 60–120) for the accept loop and broadcast channel, and `core/app/sync/src/test/kotlin/org/krost/unidrive/sync/IpcServerTest.kt` for the existing testing style.

- [ ] **Step 1: Write the failing test**

Append to `IpcServerTest.kt`:

```kotlin
@Test
fun `registered handler receives a client request and replies on the same connection`() = runBlocking {
    val sockPath = tempSocket()
    val server = IpcServer(sockPath)
    server.registerHandler("ping") { json -> """{"reply":"pong","echo":$json}""" }

    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    server.start(scope)
    waitForSocket(sockPath)

    val reply = clientRoundTrip(sockPath, """{"verb":"ping","arg":42}""")

    assertEquals("""{"reply":"pong","echo":{"verb":"ping","arg":42}}""", reply.trim())

    scope.cancel()
}
```

Use `tempSocket()` / `waitForSocket()` / `clientRoundTrip()` helpers from the existing test file. If `clientRoundTrip` doesn't exist, add it as a thin wrapper that opens a UDS socket, writes a line + newline, reads one line back.

- [ ] **Step 2: Run test to verify it fails**

```
./gradlew :app:sync:test --tests IpcServerTest.'registered handler receives a client request and replies on the same connection' -q
```

Expected: FAIL — `registerHandler` does not exist.

- [ ] **Step 3: Extend `IpcServer` with handler registry + per-client reader**

Add to `IpcServer.kt` (alongside the existing `clients` / `channel` fields):

```kotlin
private val handlers = java.util.concurrent.ConcurrentHashMap<String, suspend (String) -> String>()

/**
 * Register an inbound-verb handler. The handler receives the raw JSON
 * line (excluding the trailing newline) and returns the JSON reply line
 * (the server appends the newline). Verb dispatch keys on a top-level
 * "verb" field in the request JSON. Throws IllegalStateException on
 * duplicate registration (registration is one-shot per verb).
 */
fun registerHandler(verb: String, handler: suspend (String) -> String) {
    require(handlers.putIfAbsent(verb, handler) == null) {
        "Handler for verb '$verb' is already registered"
    }
}
```

In the accept loop (around line 88–108), after `clients.add(client)` and `flushStateDump(client)`, start a per-client reader coroutine:

```kotlin
scope.launch(Dispatchers.IO) {
    val buf = java.nio.ByteBuffer.allocate(MAX_REQUEST_BYTES)
    val pending = StringBuilder()
    try {
        while (isActive) {
            buf.clear()
            val n = client.read(buf)
            if (n < 0) break  // client closed
            if (n == 0) { kotlinx.coroutines.delay(20); continue }
            buf.flip()
            val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
            pending.append(String(bytes, Charsets.UTF_8))
            // Split on \n; dispatch each complete line.
            var idx = pending.indexOf('\n')
            while (idx >= 0) {
                val line = pending.substring(0, idx)
                pending.delete(0, idx + 1)
                dispatchRequest(client, line)
                idx = pending.indexOf('\n')
            }
        }
    } catch (e: IOException) {
        log.debug("IPC: client reader closed: {}", e.message)
    }
}
```

Add the dispatch helper:

```kotlin
private suspend fun dispatchRequest(client: SocketChannel, line: String) {
    val verb = parseVerb(line) ?: run {
        log.warn("IPC: request without 'verb' field, dropping: {}", line.take(80))
        return
    }
    val handler = handlers[verb] ?: run {
        log.warn("IPC: no handler for verb '{}'", verb)
        return
    }
    val reply = try {
        handler(line)
    } catch (e: Exception) {
        log.error("IPC: handler '$verb' threw", e)
        """{"error":"handler_threw","verb":"$verb","message":${escapeJson(e.message ?: "")}}"""
    }
    runCatching {
        writeNonBlocking(client, java.nio.ByteBuffer.wrap((reply + "\n").toByteArray(Charsets.UTF_8)))
    }
}

private fun parseVerb(line: String): String? {
    // Minimal JSON probe — looks for "verb"\s*:\s*"..." at top level. Avoids
    // pulling a full JSON parser into IpcServer for one field.
    val key = "\"verb\""
    val k = line.indexOf(key)
    if (k < 0) return null
    val colon = line.indexOf(':', k + key.length)
    if (colon < 0) return null
    val q1 = line.indexOf('"', colon)
    if (q1 < 0) return null
    val q2 = line.indexOf('"', q1 + 1)
    if (q2 < 0) return null
    return line.substring(q1 + 1, q2)
}

private fun escapeJson(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

companion object {
    // existing entries here ...
    private const val MAX_REQUEST_BYTES = 64 * 1024
}
```

(The exact location of `MAX_CLIENTS` etc. depends on the existing companion object; just add `MAX_REQUEST_BYTES` next to it.)

- [ ] **Step 4: Run test to verify it passes**

```
./gradlew :app:sync:test --tests IpcServerTest.'registered handler receives a client request and replies on the same connection' -q
```

Expected: PASS.

- [ ] **Step 5: Run the full IpcServer test class to confirm no regression**

```
./gradlew :app:sync:test --tests IpcServerTest -q
```

Expected: PASS (all existing tests + the new one).

- [ ] **Step 6: Commit**

```bash
git add core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcServer.kt \
        core/app/sync/src/test/kotlin/org/krost/unidrive/sync/IpcServerTest.kt
git commit -m "feat(ipc): add inbound-handler registry to IpcServer

IpcServer was broadcast-only. Adds registerHandler(verb, handler) and a
per-client reader coroutine that dispatches inbound JSON-line requests.
Wire format unchanged (JSON-line). Prepares for the hydration verbs."
```

### Task 4: `HydrationEvent` sealed class

**Files:**
- Create: `core/app/hydration/src/main/kotlin/org/krost/unidrive/hydration/HydrationEvent.kt`
- Create: `core/app/hydration/src/test/kotlin/org/krost/unidrive/hydration/HydrationEventTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package org.krost.unidrive.hydration

import kotlin.test.Test
import kotlin.test.assertEquals

class HydrationEventTest {
    @Test
    fun `hydrating carries path only`() {
        val e = HydrationEvent.Hydrating("/foo/bar.txt")
        assertEquals("/foo/bar.txt", e.path)
    }

    @Test
    fun `hydrated carries path and bytes`() {
        val e = HydrationEvent.Hydrated("/foo/bar.txt", 4096)
        assertEquals("/foo/bar.txt", e.path)
        assertEquals(4096L, e.bytes)
    }

    @Test
    fun `dehydrated carries path`() {
        val e = HydrationEvent.Dehydrated("/foo/bar.txt")
        assertEquals("/foo/bar.txt", e.path)
    }

    @Test
    fun `failed carries path and structured error`() {
        val e = HydrationEvent.Failed("/foo/bar.txt", HydrationError.Generic("nope"))
        assertEquals("/foo/bar.txt", e.path)
        assertEquals("nope", e.error.message)
    }

    @Test
    fun `when over sealed class is exhaustive`() {
        val e: HydrationEvent = HydrationEvent.Hydrating("/x")
        // Force exhaustive when (compiler enforces) — this only compiles if
        // every variant is handled.
        val s: String = when (e) {
            is HydrationEvent.Hydrating  -> "ing"
            is HydrationEvent.Hydrated   -> "ed"
            is HydrationEvent.Dehydrated -> "dh"
            is HydrationEvent.Failed     -> "fa"
        }
        assertEquals("ing", s)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```
./gradlew :app:hydration:test --tests HydrationEventTest -q
```

Expected: FAIL — `HydrationEvent` does not exist.

- [ ] **Step 3: Write the implementation**

```kotlin
package org.krost.unidrive.hydration

/**
 * Hydration state-change events emitted as a Flow by the SPI.
 * Phase 3 consumers subscribe via `hydration.subscribe` to drive
 * icon overlays / desktop notifications.
 */
sealed class HydrationEvent {
    abstract val path: String

    data class Hydrating(override val path: String) : HydrationEvent()
    data class Hydrated(override val path: String, val bytes: Long) : HydrationEvent()
    data class Dehydrated(override val path: String) : HydrationEvent()
    data class Failed(override val path: String, val error: HydrationError) : HydrationEvent()
}
```

- [ ] **Step 4: Run test to verify it passes**

```
./gradlew :app:hydration:test --tests HydrationEventTest -q
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/app/hydration/src/main/kotlin/org/krost/unidrive/hydration/HydrationEvent.kt \
        core/app/hydration/src/test/kotlin/org/krost/unidrive/hydration/HydrationEventTest.kt
git commit -m "feat(hydration): HydrationEvent sealed class"
```

### Task 5: `Hydration` interface

**Files:**
- Create: `core/app/hydration/src/main/kotlin/org/krost/unidrive/hydration/Hydration.kt`

- [ ] **Step 1: Write the interface**

No test in this task — the interface is exercised through `HydrationImpl` tests in Task 6+. The contract here is just shape.

```kotlin
package org.krost.unidrive.hydration

import kotlinx.coroutines.flow.Flow
import java.nio.file.Path

/**
 * Thin verb-based SPI between the engine and platform-tier consumers
 * (Phase 2 FUSE co-daemon; future Phase 3 Dolphin extension; eventual
 * Windows / Android tiers). All paths are remote-namespace paths
 * (cloud-side), not local FS paths. The cache-path returned from
 * open_* is a local FS path inside ~/.cache/unidrive/hydration/.
 *
 * Handle IDs are caller-supplied opaque strings. The implementation
 * tracks them per IPC connection (see HydrationImpl) so a co-daemon
 * crash cleanly releases its open-set without explicit close calls.
 */
interface Hydration {
    suspend fun openForRead(connectionId: String, handleId: String, path: String): OpenResult
    suspend fun openForWrite(connectionId: String, handleId: String, path: String, cachePath: Path): OpenResult
    suspend fun closeHandle(connectionId: String, handleId: String)
    suspend fun hydrate(path: String): HydrateResult
    suspend fun dehydrate(path: String): DehydrateResult

    val events: Flow<HydrationEvent>

    /** Called by IpcServer when an IPC connection closes. Clears that connection's open-set. */
    fun onConnectionClosed(connectionId: String)
}

sealed class OpenResult {
    data class Ok(val cachePath: Path) : OpenResult()
    data class Failed(val error: HydrationError) : OpenResult()
}

sealed class HydrateResult {
    data object Ok : HydrateResult()
    data class Failed(val error: HydrationError) : HydrateResult()
}

sealed class DehydrateResult {
    data object Ok : DehydrateResult()
    data object Busy : DehydrateResult()
    data class Failed(val error: HydrationError) : DehydrateResult()
}
```

- [ ] **Step 2: Verify it compiles**

```
./gradlew :app:hydration:compileKotlin -q
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add core/app/hydration/src/main/kotlin/org/krost/unidrive/hydration/Hydration.kt
git commit -m "feat(hydration): Hydration interface (six verbs + events Flow)"
```

### Task 6: `SyncEngine` public hooks (`ensureHydrated`, `uploadFromCache`)

**Files:**
- Modify: `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt`
- Modify: `core/app/sync/src/test/kotlin/org/krost/unidrive/sync/SyncEngineTest.kt`

**Context to read first:** `SyncEngine.kt` around line 1311 (`dispatchStreamingDownload`), line 1368 (`dispatchStreamingUpload`), line 1925 (`applyDownload`), line 1969 (`applyUpload`). One of these is the right delegation target — most likely `dispatchStreamingDownload` for read and the upload application path for write. The engineer chooses based on which entry point is idempotent and synchronous for a single-file operation.

- [ ] **Step 1: Write the failing test**

Append to `SyncEngineTest.kt`:

```kotlin
@Test
fun `ensureHydrated downloads a missing file and returns the local cache path`() = runTest {
    val env = newTestEnv()
    env.fakeProvider.seedRemoteFile("/foo.txt", contentBytes = "hello".toByteArray())
    env.stateDb.insertUnhydratedEntry("/foo.txt", remoteSize = 5)

    val cachePath = env.syncEngine.ensureHydrated("/foo.txt")

    assertTrue(java.nio.file.Files.exists(cachePath))
    assertEquals(5L, java.nio.file.Files.size(cachePath))
    assertEquals(true, env.stateDb.isHydrated("/foo.txt"))
}

@Test
fun `uploadFromCache uploads the cache file and updates state`() = runTest {
    val env = newTestEnv()
    val cacheDir = env.tempDir.resolve("cache").also { java.nio.file.Files.createDirectories(it) }
    val cacheFile = cacheDir.resolve("foo.txt").also { java.nio.file.Files.writeString(it, "hello") }
    env.stateDb.insertHydratedEntry("/foo.txt", localSize = 5)

    env.syncEngine.uploadFromCache("/foo.txt", cacheFile)

    assertEquals("hello", env.fakeProvider.remoteContent("/foo.txt"))
}
```

The exact test-environment fixture (`newTestEnv()`, `FakeProvider`, etc.) must match the existing `SyncEngineTest.kt` style. Use whatever helpers the file already defines.

- [ ] **Step 2: Run tests to verify they fail**

```
./gradlew :app:sync:test --tests SyncEngineTest.'ensureHydrated downloads a missing file and returns the local cache path' --tests SyncEngineTest.'uploadFromCache uploads the cache file and updates state' -q
```

Expected: FAIL — methods do not exist.

- [ ] **Step 3: Add the two public methods to `SyncEngine`**

Find a good location (near the top of the class for visibility, or in a "public API" region if the file has one). Implementation should:

```kotlin
/**
 * Hydrate a single remote path into the local cache. Idempotent — if
 * the path is already hydrated, returns the existing cache path
 * without re-downloading. Throws on unrecoverable errors (404 → permanent
 * failure exception class already used elsewhere; permission errors;
 * etc.).
 */
suspend fun ensureHydrated(path: String): java.nio.file.Path {
    val entry = stateDb.getEntry(path)
        ?: throw IllegalArgumentException("Unknown remote path: $path")
    val cachePath = resolveCachePath(path)
    if (entry.isHydrated && java.nio.file.Files.exists(cachePath)) {
        return cachePath
    }
    // Delegate to existing single-file download path. Use whichever of
    // dispatchStreamingDownload / applyDownload exposes the right signature
    // for a one-shot, synchronous-on-completion download. Wrap a fake
    // SyncAction.DownloadContent if necessary.
    val action = SyncAction.DownloadContent(path = path, /* fill rest from entry */)
    applyDownload(action)  // make this internal if needed; or wrap a new private helper
    return cachePath
}

/**
 * Upload the local cache file at cachePath as the content for remote
 * path. Used by the hydration write-through path when a FUSE RELEASE
 * indicates the cache content changed. Updates state.db mtime/size.
 */
suspend fun uploadFromCache(path: String, cachePath: java.nio.file.Path) {
    require(java.nio.file.Files.exists(cachePath)) { "Cache path missing: $cachePath" }
    val action = SyncAction.Upload(path = path, source = cachePath, /* fill rest */)
    applyUpload(action)
}

private fun resolveCachePath(path: String): java.nio.file.Path {
    // ~/.cache/unidrive/hydration/<profile>/<sanitized-path>
    val xdgCache = System.getenv("XDG_CACHE_HOME")?.let { java.nio.file.Paths.get(it) }
        ?: java.nio.file.Paths.get(System.getProperty("user.home"), ".cache")
    return xdgCache.resolve("unidrive/hydration").resolve(profile.name).resolve(path.trimStart('/'))
}
```

If `applyDownload` / `applyUpload` are private, change them to `internal` and add a comment explaining the cross-module access is for `app:hydration` only. **Do NOT** make them public.

- [ ] **Step 4: Run tests to verify they pass**

```
./gradlew :app:sync:test --tests SyncEngineTest.'ensureHydrated*' --tests SyncEngineTest.'uploadFromCache*' -q
```

Expected: PASS.

- [ ] **Step 5: Run the full sync test class to verify no regression**

```
./gradlew :app:sync:test --tests SyncEngineTest -q
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt \
        core/app/sync/src/test/kotlin/org/krost/unidrive/sync/SyncEngineTest.kt
git commit -m "feat(sync): public ensureHydrated/uploadFromCache hooks

These are the narrow integration points the new app:hydration module
delegates to. applyDownload / applyUpload visibility relaxed to
internal so app:hydration can reach them indirectly via these
suspending wrappers; the wrappers carry the cache-path discipline
(~/.cache/unidrive/hydration/<profile>/...) so callers don't have
to know the layout."
```

### Task 7: `HydrationImpl.openForRead` — cold path

**Files:**
- Create: `core/app/hydration/src/main/kotlin/org/krost/unidrive/hydration/HydrationImpl.kt`
- Create: `core/app/hydration/src/test/kotlin/org/krost/unidrive/hydration/HydrationImplTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package org.krost.unidrive.hydration

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HydrationImplTest {
    @Test
    fun `open_read on unhydrated path triggers download and returns cache path`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertUnhydratedEntry("/foo.txt", remoteSize = 5)
        env.syncEngine.seedRemoteContent("/foo.txt", "hello")

        val result = env.hydration.openForRead("conn1", "h1", "/foo.txt")

        assertTrue(result is OpenResult.Ok)
        val cachePath = (result as OpenResult.Ok).cachePath
        assertEquals("hello", java.nio.file.Files.readString(cachePath))
        assertEquals(true, env.stateDb.isHydrated("/foo.txt"))
    }
}
```

The `HydrationTestEnv` helper (build it in this same file or a `TestEnv.kt` sibling) wires:
- An in-memory or temp-dir `StateDatabase`
- A `FakeSyncEngine` (or a real `SyncEngine` with a `FakeProvider`)
- A real `HydrationImpl` constructed against those

Reuse fixtures from `core/app/sync/src/test/kotlin/org/krost/unidrive/sync/` if they're available cross-module; if not, write a minimal scoped helper here.

- [ ] **Step 2: Run test to verify it fails**

```
./gradlew :app:hydration:test --tests HydrationImplTest.'open_read on unhydrated path*' -q
```

Expected: FAIL — `HydrationImpl` does not exist.

- [ ] **Step 3: Write `HydrationImpl` skeleton + `openForRead`**

```kotlin
package org.krost.unidrive.hydration

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncEngine
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

class HydrationImpl(
    private val syncEngine: SyncEngine,
    private val stateDb: StateDatabase,
) : Hydration {

    private val _events = MutableSharedFlow<HydrationEvent>(extraBufferCapacity = 64)
    override val events: Flow<HydrationEvent> = _events.asSharedFlow()

    // connectionId -> handleId -> path
    private val openSets =
        ConcurrentHashMap<String, MutableMap<String, String>>()

    override suspend fun openForRead(connectionId: String, handleId: String, path: String): OpenResult {
        val entry = stateDb.getEntry(path)
            ?: return OpenResult.Failed(HydrationError.Generic("Unknown path: $path"))

        val cachePath = try {
            _events.emit(HydrationEvent.Hydrating(path))
            val p = syncEngine.ensureHydrated(path)
            _events.emit(HydrationEvent.Hydrated(path, entry.remoteSize))
            p
        } catch (e: Exception) {
            _events.emit(HydrationEvent.Failed(path, HydrationError.Generic(e.message ?: "download failed")))
            return OpenResult.Failed(HydrationError.Generic(e.message ?: "download failed"))
        }

        openSets.computeIfAbsent(connectionId) { mutableMapOf() }[handleId] = path
        return OpenResult.Ok(cachePath)
    }

    // Stubs for verbs not yet implemented — fail fast so missed tests show up
    override suspend fun openForWrite(connectionId: String, handleId: String, path: String, cachePath: Path) =
        TODO("Task 9")
    override suspend fun closeHandle(connectionId: String, handleId: String) = TODO("Task 8")
    override suspend fun hydrate(path: String): HydrateResult = TODO("Task 10")
    override suspend fun dehydrate(path: String): DehydrateResult = TODO("Task 11")
    override fun onConnectionClosed(connectionId: String) { TODO("Task 8") }
}
```

(The `TODO()` stubs will be filled in by later tasks. Once a stub is removed, its task should also remove the `TODO` import if any is no longer needed.)

- [ ] **Step 4: Run test to verify it passes**

```
./gradlew :app:hydration:test --tests HydrationImplTest.'open_read on unhydrated path*' -q
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/app/hydration/src/main/kotlin/org/krost/unidrive/hydration/HydrationImpl.kt \
        core/app/hydration/src/test/kotlin/org/krost/unidrive/hydration/HydrationImplTest.kt
git commit -m "feat(hydration): HydrationImpl.openForRead (cold path)"
```

### Task 8: `closeHandle` + `onConnectionClosed` + connection-scoped open-set

**Files:**
- Modify: `core/app/hydration/src/main/kotlin/org/krost/unidrive/hydration/HydrationImpl.kt`
- Modify: `core/app/hydration/src/test/kotlin/org/krost/unidrive/hydration/HydrationImplTest.kt`

- [ ] **Step 1: Write the failing tests**

Append two tests:

```kotlin
@Test
fun `close_handle on unknown id is a noop`() = runTest {
    val env = HydrationTestEnv()
    // Should not throw, should not affect open-set
    env.hydration.closeHandle("conn-never-opened", "h-never-existed")
    env.hydration.closeHandle("conn1", "h1")
}

@Test
fun `ipc disconnect clears that connection's open set entirely`() = runTest {
    val env = HydrationTestEnv()
    env.stateDb.insertUnhydratedEntry("/a.txt", remoteSize = 1)
    env.stateDb.insertUnhydratedEntry("/b.txt", remoteSize = 1)
    env.syncEngine.seedRemoteContent("/a.txt", "x")
    env.syncEngine.seedRemoteContent("/b.txt", "y")

    env.hydration.openForRead("conn1", "h1", "/a.txt")
    env.hydration.openForRead("conn1", "h2", "/b.txt")

    // Dehydrate /a.txt should fail with Busy while conn1 holds h1
    assertEquals(DehydrateResult.Busy, env.hydration.dehydrate("/a.txt"))

    // Simulate IPC disconnect
    env.hydration.onConnectionClosed("conn1")

    // Now dehydrate /a.txt should succeed
    assertEquals(DehydrateResult.Ok, env.hydration.dehydrate("/a.txt"))
    assertEquals(DehydrateResult.Ok, env.hydration.dehydrate("/b.txt"))
}
```

Note: the `dehydrate` calls in the disconnect test will fail until Task 11 lands. To unblock this task, the test file should be ordered so `closeHandle` + `onConnectionClosed` come now, the disconnect test comes after Task 11. **Move the disconnect test to be appended in Task 11's step 1 instead, and only add `close_handle on unknown id is a noop` in this task.**

Revised: in this task, append ONLY:

```kotlin
@Test
fun `close_handle on unknown id is a noop`() = runTest {
    val env = HydrationTestEnv()
    env.hydration.closeHandle("conn-never-opened", "h-never-existed")
    env.hydration.closeHandle("conn1", "h1")
}

@Test
fun `close_handle removes the handle from its connection's open set`() = runTest {
    val env = HydrationTestEnv()
    env.stateDb.insertUnhydratedEntry("/a.txt", remoteSize = 1)
    env.syncEngine.seedRemoteContent("/a.txt", "x")
    env.hydration.openForRead("conn1", "h1", "/a.txt")
    env.hydration.closeHandle("conn1", "h1")
    // Internal-state check: re-opening with the same handle id should succeed
    val r = env.hydration.openForRead("conn1", "h1", "/a.txt")
    assertTrue(r is OpenResult.Ok)
}
```

- [ ] **Step 2: Run tests to verify they fail**

```
./gradlew :app:hydration:test --tests HydrationImplTest.'close_handle on unknown id*' --tests HydrationImplTest.'close_handle removes*' -q
```

Expected: FAIL — `closeHandle` is `TODO()`.

- [ ] **Step 3: Implement `closeHandle` and `onConnectionClosed`**

Replace the two TODOs in `HydrationImpl`:

```kotlin
override suspend fun closeHandle(connectionId: String, handleId: String) {
    openSets[connectionId]?.remove(handleId)
}

override fun onConnectionClosed(connectionId: String) {
    openSets.remove(connectionId)
}
```

- [ ] **Step 4: Run tests to verify they pass**

```
./gradlew :app:hydration:test --tests HydrationImplTest.'close_handle*' -q
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/app/hydration/src/main/kotlin/org/krost/unidrive/hydration/HydrationImpl.kt \
        core/app/hydration/src/test/kotlin/org/krost/unidrive/hydration/HydrationImplTest.kt
git commit -m "feat(hydration): close_handle + connection-scoped open-set cleanup"
```

### Task 9: `openForWrite`

**Files:**
- Modify: `core/app/hydration/src/main/kotlin/org/krost/unidrive/hydration/HydrationImpl.kt`
- Modify: `core/app/hydration/src/test/kotlin/org/krost/unidrive/hydration/HydrationImplTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `open_for_write triggers upload-from-cache and registers the handle`() = runTest {
    val env = HydrationTestEnv()
    env.stateDb.insertHydratedEntry("/foo.txt", localSize = 5)
    val cacheFile = env.tempDir.resolve("foo.txt").also { java.nio.file.Files.writeString(it, "world") }

    val r = env.hydration.openForWrite("conn1", "h1", "/foo.txt", cacheFile)

    assertTrue(r is OpenResult.Ok)
    assertEquals("world", env.syncEngine.remoteContentSeen("/foo.txt"))
}
```

- [ ] **Step 2: Run test to verify it fails**

```
./gradlew :app:hydration:test --tests HydrationImplTest.'open_for_write*' -q
```

Expected: FAIL — `openForWrite` is `TODO()`.

- [ ] **Step 3: Implement `openForWrite`**

```kotlin
override suspend fun openForWrite(connectionId: String, handleId: String, path: String, cachePath: Path): OpenResult {
    val entry = stateDb.getEntry(path)
        ?: return OpenResult.Failed(HydrationError.Generic("Unknown path: $path"))

    return try {
        _events.emit(HydrationEvent.Hydrating(path))
        syncEngine.uploadFromCache(path, cachePath)
        val bytes = java.nio.file.Files.size(cachePath)
        _events.emit(HydrationEvent.Hydrated(path, bytes))
        openSets.computeIfAbsent(connectionId) { mutableMapOf() }[handleId] = path
        OpenResult.Ok(cachePath)
    } catch (e: Exception) {
        _events.emit(HydrationEvent.Failed(path, HydrationError.Generic(e.message ?: "upload failed")))
        OpenResult.Failed(HydrationError.Generic(e.message ?: "upload failed"))
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```
./gradlew :app:hydration:test --tests HydrationImplTest.'open_for_write*' -q
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/app/hydration/src/main/kotlin/org/krost/unidrive/hydration/HydrationImpl.kt \
        core/app/hydration/src/test/kotlin/org/krost/unidrive/hydration/HydrationImplTest.kt
git commit -m "feat(hydration): openForWrite (FUSE-RELEASE upload trigger)"
```

### Task 10: `hydrate` (explicit pre-fetch)

**Files:**
- Modify: `core/app/hydration/src/main/kotlin/org/krost/unidrive/hydration/HydrationImpl.kt`
- Modify: `core/app/hydration/src/test/kotlin/org/krost/unidrive/hydration/HydrationImplTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `explicit hydrate on already-hydrated path is a noop ok`() = runTest {
    val env = HydrationTestEnv()
    env.stateDb.insertHydratedEntry("/foo.txt", localSize = 5)
    env.syncEngine.seedCacheContent("/foo.txt", "hello")  // already in cache

    val r = env.hydration.hydrate("/foo.txt")

    assertEquals(HydrateResult.Ok, r)
}

@Test
fun `explicit hydrate on unhydrated path downloads and returns ok`() = runTest {
    val env = HydrationTestEnv()
    env.stateDb.insertUnhydratedEntry("/foo.txt", remoteSize = 5)
    env.syncEngine.seedRemoteContent("/foo.txt", "hello")

    val r = env.hydration.hydrate("/foo.txt")

    assertEquals(HydrateResult.Ok, r)
    assertEquals(true, env.stateDb.isHydrated("/foo.txt"))
}
```

- [ ] **Step 2: Run tests to verify they fail**

```
./gradlew :app:hydration:test --tests HydrationImplTest.'explicit hydrate*' -q
```

Expected: FAIL — `hydrate` is `TODO()`.

- [ ] **Step 3: Implement `hydrate`**

```kotlin
override suspend fun hydrate(path: String): HydrateResult {
    return try {
        _events.emit(HydrationEvent.Hydrating(path))
        val cachePath = syncEngine.ensureHydrated(path)
        val bytes = java.nio.file.Files.size(cachePath)
        _events.emit(HydrationEvent.Hydrated(path, bytes))
        HydrateResult.Ok
    } catch (e: Exception) {
        _events.emit(HydrationEvent.Failed(path, HydrationError.Generic(e.message ?: "hydrate failed")))
        HydrateResult.Failed(HydrationError.Generic(e.message ?: "hydrate failed"))
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```
./gradlew :app:hydration:test --tests HydrationImplTest.'explicit hydrate*' -q
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/app/hydration/src/main/kotlin/org/krost/unidrive/hydration/HydrationImpl.kt \
        core/app/hydration/src/test/kotlin/org/krost/unidrive/hydration/HydrationImplTest.kt
git commit -m "feat(hydration): explicit hydrate verb"
```

### Task 11: `dehydrate` (with open-handle refusal)

**Files:**
- Modify: `core/app/hydration/src/main/kotlin/org/krost/unidrive/hydration/HydrationImpl.kt`
- Modify: `core/app/hydration/src/test/kotlin/org/krost/unidrive/hydration/HydrationImplTest.kt`

- [ ] **Step 1: Write the failing tests**

Append all three:

```kotlin
@Test
fun `dehydrate refuses while a handle is open across any connection`() = runTest {
    val env = HydrationTestEnv()
    env.stateDb.insertHydratedEntry("/foo.txt", localSize = 5)
    env.syncEngine.seedCacheContent("/foo.txt", "hello")

    env.hydration.openForRead("conn1", "h1", "/foo.txt")

    assertEquals(DehydrateResult.Busy, env.hydration.dehydrate("/foo.txt"))
}

@Test
fun `dehydrate succeeds once all handles are closed`() = runTest {
    val env = HydrationTestEnv()
    env.stateDb.insertHydratedEntry("/foo.txt", localSize = 5)
    env.syncEngine.seedCacheContent("/foo.txt", "hello")

    env.hydration.openForRead("conn1", "h1", "/foo.txt")
    env.hydration.openForRead("conn2", "h1", "/foo.txt")  // different connection, same handle-id

    assertEquals(DehydrateResult.Busy, env.hydration.dehydrate("/foo.txt"))
    env.hydration.closeHandle("conn1", "h1")
    assertEquals(DehydrateResult.Busy, env.hydration.dehydrate("/foo.txt"))  // still conn2
    env.hydration.closeHandle("conn2", "h1")
    assertEquals(DehydrateResult.Ok, env.hydration.dehydrate("/foo.txt"))
}

@Test
fun `ipc disconnect clears that connection's open set entirely`() = runTest {
    val env = HydrationTestEnv()
    env.stateDb.insertHydratedEntry("/a.txt", localSize = 1)
    env.stateDb.insertHydratedEntry("/b.txt", localSize = 1)
    env.syncEngine.seedCacheContent("/a.txt", "x")
    env.syncEngine.seedCacheContent("/b.txt", "y")

    env.hydration.openForRead("conn1", "h1", "/a.txt")
    env.hydration.openForRead("conn1", "h2", "/b.txt")
    assertEquals(DehydrateResult.Busy, env.hydration.dehydrate("/a.txt"))

    env.hydration.onConnectionClosed("conn1")

    assertEquals(DehydrateResult.Ok, env.hydration.dehydrate("/a.txt"))
    assertEquals(DehydrateResult.Ok, env.hydration.dehydrate("/b.txt"))
}
```

- [ ] **Step 2: Run tests to verify they fail**

```
./gradlew :app:hydration:test --tests HydrationImplTest.'dehydrate*' --tests HydrationImplTest.'ipc disconnect*' -q
```

Expected: FAIL — `dehydrate` is `TODO()`.

- [ ] **Step 3: Implement `dehydrate`**

```kotlin
override suspend fun dehydrate(path: String): DehydrateResult {
    // Check the open-set across ALL connections
    val anyOpen = openSets.values.any { perConn -> perConn.containsValue(path) }
    if (anyOpen) return DehydrateResult.Busy

    return try {
        val cachePath = syncEngine.cachePathFor(path)  // expose this as a public helper on SyncEngine
        if (java.nio.file.Files.exists(cachePath)) {
            java.nio.file.Files.delete(cachePath)
        }
        stateDb.markUnhydrated(path)
        _events.emit(HydrationEvent.Dehydrated(path))
        DehydrateResult.Ok
    } catch (e: Exception) {
        _events.emit(HydrationEvent.Failed(path, HydrationError.Generic(e.message ?: "dehydrate failed")))
        DehydrateResult.Failed(HydrationError.Generic(e.message ?: "dehydrate failed"))
    }
}
```

The call to `syncEngine.cachePathFor(path)` and `stateDb.markUnhydrated(path)` may need helpers added; if those don't exist yet, add small wrappers in this step (their tests already exist by virtue of this task's assertion that dehydrate works).

- [ ] **Step 4: Run tests to verify they pass**

```
./gradlew :app:hydration:test --tests HydrationImplTest.'dehydrate*' --tests HydrationImplTest.'ipc disconnect*' -q
```

Expected: PASS (3 tests pass, including the IPC-disconnect test).

- [ ] **Step 5: Commit**

```bash
git add core/app/hydration/src/main/kotlin/org/krost/unidrive/hydration/HydrationImpl.kt \
        core/app/hydration/src/test/kotlin/org/krost/unidrive/hydration/HydrationImplTest.kt \
        core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt \
        core/app/sync/src/main/kotlin/org/krost/unidrive/sync/StateDatabase.kt
git commit -m "feat(hydration): dehydrate with open-handle refusal + IPC-disconnect cleanup"
```

### Task 12: Pin event emission across all transitions

**Files:**
- Modify: `core/app/hydration/src/test/kotlin/org/krost/unidrive/hydration/HydrationImplTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
@Test
fun `events flow emits Hydrating then Hydrated for successful open_read`() = runTest {
    val env = HydrationTestEnv()
    env.stateDb.insertUnhydratedEntry("/foo.txt", remoteSize = 5)
    env.syncEngine.seedRemoteContent("/foo.txt", "hello")

    val collected = mutableListOf<HydrationEvent>()
    val job = launch { env.hydration.events.collect { collected += it } }

    env.hydration.openForRead("conn1", "h1", "/foo.txt")

    // Yield long enough for the SharedFlow to deliver
    yield(); yield()
    job.cancel()

    assertEquals(2, collected.size)
    assertTrue(collected[0] is HydrationEvent.Hydrating)
    assertTrue(collected[1] is HydrationEvent.Hydrated)
}

@Test
fun `events flow emits Failed when download throws`() = runTest {
    val env = HydrationTestEnv()
    env.stateDb.insertUnhydratedEntry("/foo.txt", remoteSize = 5)
    env.syncEngine.makeNextDownloadThrow(RuntimeException("boom"))

    val collected = mutableListOf<HydrationEvent>()
    val job = launch { env.hydration.events.collect { collected += it } }

    val r = env.hydration.openForRead("conn1", "h1", "/foo.txt")

    yield(); yield()
    job.cancel()

    assertTrue(r is OpenResult.Failed)
    assertTrue(collected.any { it is HydrationEvent.Failed })
}
```

- [ ] **Step 2: Run tests**

```
./gradlew :app:hydration:test --tests HydrationImplTest.'events flow*' -q
```

Expected: PASS without further code changes (the `_events.emit(...)` calls were added back in Task 7 and onward). If a test fails, fix by inserting the missing `emit` calls — but it should already work.

- [ ] **Step 3: Commit (likely test-only)**

```bash
git add core/app/hydration/src/test/kotlin/org/krost/unidrive/hydration/HydrationImplTest.kt
git commit -m "test(hydration): pin Hydrating/Hydrated/Failed event emission"
```

### Task 13: `HydrationIpcHandler` — verb registration + JSON wire format

**Files:**
- Create: `core/app/hydration/src/main/kotlin/org/krost/unidrive/hydration/HydrationIpcHandler.kt`
- Create: `core/app/hydration/src/test/kotlin/org/krost/unidrive/hydration/HydrationIpcHandlerTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package org.krost.unidrive.hydration

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HydrationIpcHandlerTest {
    @Test
    fun `open_read request returns JSON with cache_path`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertUnhydratedEntry("/foo.txt", remoteSize = 5)
        env.syncEngine.seedRemoteContent("/foo.txt", "hello")
        val handler = HydrationIpcHandler(env.hydration)

        val reply = handler.handle("conn1", """{"verb":"hydration.open_read","handle_id":"h1","path":"/foo.txt"}""")

        assertTrue(reply.contains("\"ok\":true"))
        assertTrue(reply.contains("\"cache_path\":"))
    }

    @Test
    fun `dehydrate while open returns busy in JSON`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertHydratedEntry("/foo.txt", localSize = 5)
        env.syncEngine.seedCacheContent("/foo.txt", "hello")
        val handler = HydrationIpcHandler(env.hydration)

        handler.handle("conn1", """{"verb":"hydration.open_read","handle_id":"h1","path":"/foo.txt"}""")
        val reply = handler.handle("conn1", """{"verb":"hydration.dehydrate","path":"/foo.txt"}""")

        assertEquals("""{"ok":false,"error":"busy"}""", reply.trim())
    }

    @Test
    fun `close_handle returns ok in JSON`() = runTest {
        val env = HydrationTestEnv()
        val handler = HydrationIpcHandler(env.hydration)

        val reply = handler.handle("conn1", """{"verb":"hydration.close_handle","handle_id":"h1"}""")

        assertEquals("""{"ok":true}""", reply.trim())
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```
./gradlew :app:hydration:test --tests HydrationIpcHandlerTest -q
```

Expected: FAIL — `HydrationIpcHandler` does not exist.

- [ ] **Step 3: Write `HydrationIpcHandler`**

```kotlin
package org.krost.unidrive.hydration

import java.nio.file.Paths

/**
 * Translates IpcServer JSON-line requests to Hydration verb calls
 * and back. Registers six verbs on the IpcServer; the per-connection
 * id used in the Hydration API is the IpcServer's per-client opaque
 * connection identifier (passed by the caller of handle()).
 *
 * Wire format (all JSON):
 *   open_read   request:  {"verb":"hydration.open_read","handle_id":"...","path":"/foo"}
 *   open_read   reply ok: {"ok":true,"cache_path":"/home/.../foo.txt"}
 *   open_read   reply err:{"ok":false,"error":"<message>"}
 *   open_write  request:  {"verb":"hydration.open_write","handle_id":"...","path":"/foo","cache_path":"/home/.../foo.txt"}
 *   open_write  reply:    same as open_read
 *   close_handle request: {"verb":"hydration.close_handle","handle_id":"..."}
 *   close_handle reply:   {"ok":true}
 *   hydrate     request:  {"verb":"hydration.hydrate","path":"/foo"}
 *   hydrate     reply:    {"ok":true} or {"ok":false,"error":"<message>"}
 *   dehydrate   request:  {"verb":"hydration.dehydrate","path":"/foo"}
 *   dehydrate   reply:    {"ok":true} or {"ok":false,"error":"busy"} or {"ok":false,"error":"<message>"}
 *   subscribe   request:  {"verb":"hydration.subscribe"}
 *   subscribe   reply:    {"ok":true} — and from then on, the connection becomes a one-way
 *                         event stream (server-pushed NDJSON of HydrationEvent serializations)
 *
 * The subscribe verb is registered but its event-push side is wired up
 * by the daemon startup glue (Task 14) — Phase 1 ships the verb call;
 * full stream delivery is exercised by the smoke test in Task 15.
 */
class HydrationIpcHandler(
    private val hydration: Hydration,
) {
    suspend fun handle(connectionId: String, jsonRequest: String): String {
        val verb = pluck(jsonRequest, "verb") ?: return reply(ok = false, error = "missing_verb")
        return when (verb) {
            "hydration.open_read" -> {
                val handleId = pluck(jsonRequest, "handle_id") ?: return reply(ok = false, error = "missing_handle_id")
                val path = pluck(jsonRequest, "path") ?: return reply(ok = false, error = "missing_path")
                when (val r = hydration.openForRead(connectionId, handleId, path)) {
                    is OpenResult.Ok -> """{"ok":true,"cache_path":${json(r.cachePath.toString())}}"""
                    is OpenResult.Failed -> reply(ok = false, error = r.error.message)
                }
            }
            "hydration.open_write" -> {
                val handleId = pluck(jsonRequest, "handle_id") ?: return reply(ok = false, error = "missing_handle_id")
                val path = pluck(jsonRequest, "path") ?: return reply(ok = false, error = "missing_path")
                val cache = pluck(jsonRequest, "cache_path") ?: return reply(ok = false, error = "missing_cache_path")
                when (val r = hydration.openForWrite(connectionId, handleId, path, Paths.get(cache))) {
                    is OpenResult.Ok -> """{"ok":true,"cache_path":${json(r.cachePath.toString())}}"""
                    is OpenResult.Failed -> reply(ok = false, error = r.error.message)
                }
            }
            "hydration.close_handle" -> {
                val handleId = pluck(jsonRequest, "handle_id") ?: return reply(ok = false, error = "missing_handle_id")
                hydration.closeHandle(connectionId, handleId)
                reply(ok = true)
            }
            "hydration.hydrate" -> {
                val path = pluck(jsonRequest, "path") ?: return reply(ok = false, error = "missing_path")
                when (val r = hydration.hydrate(path)) {
                    HydrateResult.Ok -> reply(ok = true)
                    is HydrateResult.Failed -> reply(ok = false, error = r.error.message)
                }
            }
            "hydration.dehydrate" -> {
                val path = pluck(jsonRequest, "path") ?: return reply(ok = false, error = "missing_path")
                when (val r = hydration.dehydrate(path)) {
                    DehydrateResult.Ok -> reply(ok = true)
                    DehydrateResult.Busy -> reply(ok = false, error = "busy")
                    is DehydrateResult.Failed -> reply(ok = false, error = r.error.message)
                }
            }
            "hydration.subscribe" -> {
                // event-stream wiring lands in daemon startup glue (Task 14)
                reply(ok = true)
            }
            else -> reply(ok = false, error = "unknown_verb")
        }
    }

    private fun reply(ok: Boolean, error: String? = null): String =
        if (ok) """{"ok":true}""" else """{"ok":false,"error":${json(error ?: "unknown")}}"""

    private fun json(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    // Minimal JSON pluck — works for flat top-level string fields. Sufficient
    // for our verb messages; we don't accept arbitrary client JSON shapes.
    private fun pluck(line: String, key: String): String? {
        val needle = "\"$key\""
        val k = line.indexOf(needle)
        if (k < 0) return null
        val colon = line.indexOf(':', k + needle.length)
        if (colon < 0) return null
        val q1 = line.indexOf('"', colon)
        if (q1 < 0) return null
        val sb = StringBuilder()
        var i = q1 + 1
        while (i < line.length) {
            val c = line[i]
            if (c == '"') return sb.toString()
            if (c == '\\' && i + 1 < line.length) { sb.append(line[i + 1]); i += 2; continue }
            sb.append(c); i++
        }
        return null
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```
./gradlew :app:hydration:test --tests HydrationIpcHandlerTest -q
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/app/hydration/src/main/kotlin/org/krost/unidrive/hydration/HydrationIpcHandler.kt \
        core/app/hydration/src/test/kotlin/org/krost/unidrive/hydration/HydrationIpcHandlerTest.kt
git commit -m "feat(hydration): HydrationIpcHandler — six verbs over JSON-line"
```

### Task 14: Wire `HydrationImpl` + `HydrationIpcHandler` into the daemon startup

**Files:**
- Modify: `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/SyncCommand.kt` (or wherever the `IpcServer` is constructed and started)
- Modify: `core/app/cli/build.gradle.kts` (add `implementation(project(":app:hydration"))`)

**Context to read first:** Find the call to `IpcServer(socketPath)` followed by `server.start(scope)` in `core/app/cli/`. That's the spot. Likely `SyncCommand.kt`.

- [ ] **Step 1: Read the existing IpcServer construction site**

Grep for `IpcServer(` in `core/app/cli/`. Confirm the construction shape and the scope it's started in.

- [ ] **Step 2: Add the hydration wiring just after `ipcServer.start(scope)`**

```kotlin
// Wire the Phase-1 hydration SPI as IpcServer handlers.
val hydration = HydrationImpl(syncEngine, stateDb)
val hydrationIpc = HydrationIpcHandler(hydration)
for (verb in listOf(
    "hydration.open_read", "hydration.open_write", "hydration.close_handle",
    "hydration.hydrate", "hydration.dehydrate", "hydration.subscribe",
)) {
    ipcServer.registerHandler(verb) { json ->
        // The connection ID comes from the IpcServer's per-client tracking.
        // For now use the verb-call's thread-local or a stub; full wiring of
        // per-connection IDs is a small extension to IpcServer's dispatch
        // that we plug in here (see TODO below).
        hydrationIpc.handle(connectionId = "ipc-shared", jsonRequest = json)
    }
}
// Hydration events fan out to subscribers via the existing emit() channel.
scope.launch {
    hydration.events.collect { event ->
        ipcServer.emit(serialiseHydrationEvent(event))
    }
}
```

- [ ] **Step 3: TODO — per-connection ID plumbing**

The `connectionId = "ipc-shared"` placeholder above is a known shortcut. **Before committing**, plumb the real connection id from `IpcServer.dispatchRequest` through to the handler. Two-step:

3a. Extend `IpcServer.registerHandler` signature to take `suspend (connectionId: String, json: String) -> String`. (Update the signature in `IpcServer.kt`, update the test in Task 3 to match, and update the dispatch call to pass `clients.indexOf(client).toString()` or a per-client uuid.)

3b. Hook `IpcServer` to call `hydration.onConnectionClosed(connectionId)` when a client disconnects. (Add a `registerConnectionCloseListener` API on IpcServer; wire it.)

```kotlin
fun registerConnectionCloseListener(listener: (connectionId: String) -> Unit) {
    closeListeners.add(listener)
}
private val closeListeners = CopyOnWriteArrayList<(String) -> Unit>()
```

And in the broadcast loop's "drop dead clients" path, after closing a dead client, invoke `closeListeners.forEach { it(connId(client)) }`.

In `SyncCommand`:

```kotlin
ipcServer.registerConnectionCloseListener { connId ->
    hydration.onConnectionClosed(connId)
}
```

- [ ] **Step 4: Add `serialiseHydrationEvent`**

In `HydrationIpcHandler.kt` or a small sibling file:

```kotlin
internal fun serialiseHydrationEvent(e: HydrationEvent): String = when (e) {
    is HydrationEvent.Hydrating  -> """{"event":"hydrating","path":${json(e.path)}}"""
    is HydrationEvent.Hydrated   -> """{"event":"hydrated","path":${json(e.path)},"bytes":${e.bytes}}"""
    is HydrationEvent.Dehydrated -> """{"event":"dehydrated","path":${json(e.path)}}"""
    is HydrationEvent.Failed     -> """{"event":"failed","path":${json(e.path)},"error":${json(e.error.message)}}"""
}
private fun json(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
```

- [ ] **Step 5: Run the full CLI tests to confirm no regression**

```
./gradlew :app:cli:test -q
./gradlew :app:hydration:test -q
./gradlew :app:sync:test -q
```

Expected: all PASS.

- [ ] **Step 6: Commit**

```bash
git add core/app/cli/src/main/kotlin/org/krost/unidrive/cli/SyncCommand.kt \
        core/app/cli/build.gradle.kts \
        core/app/sync/src/main/kotlin/org/krost/unidrive/sync/IpcServer.kt \
        core/app/sync/src/test/kotlin/org/krost/unidrive/sync/IpcServerTest.kt \
        core/app/hydration/src/main/kotlin/org/krost/unidrive/hydration/HydrationIpcHandler.kt
git commit -m "feat(hydration): wire HydrationImpl into daemon startup

Per-connection IDs flow from IpcServer through handlers to the open-set.
Hydration events fan out via IpcServer.emit so subscribers see them on
the existing broadcast channel."
```

### Task 15: Live-integration smoke test (third sync smoke)

**Files:**
- Create: `core/app/hydration/src/test/kotlin/org/krost/unidrive/hydration/HydrationSmokeTest.kt`

**Context:** The smoke test fills the third sync smoke in the 5+5+2 target. Gated by `UNIDRIVE_INTEGRATION_TESTS=true` like the existing live tests in `core/providers/onedrive/.../LiveGraphIntegrationTest.kt` — read that for the exact `assumeTrue` pattern.

- [ ] **Step 1: Write the test**

```kotlin
package org.krost.unidrive.hydration

import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class HydrationSmokeTest {
    @Test
    fun `hydrate then read against a real Internxt profile`() = runTest {
        assumeTrue(
            System.getenv("UNIDRIVE_INTEGRATION_TESTS") == "true",
            "Set UNIDRIVE_INTEGRATION_TESTS=true to run this smoke",
        )
        // Construct the real SyncEngine + StateDatabase against the profile
        // the existing live tests use (see InternxtIntegrationTest for the
        // exact setup helpers). Then:
        //
        //   1. Pick a small known cloud file from the test account.
        //   2. Ensure it is unhydrated (call hydration.dehydrate first if needed).
        //   3. Call hydration.openForRead, read the cache file, assert bytes
        //      match the known content.
        //   4. Call hydration.dehydrate; assert the local cache file is gone
        //      and state.db is_hydrated flips back to 0.
        //
        // Refuse to run if the test account isn't configured.
        val env = liveTestEnv()  // build this helper following InternxtIntegrationTest's pattern
        val testPath = "/smoke-test-fixed-file.txt"
        env.ensureRemoteFile(testPath, knownContent = "smoke-test-payload")

        // Make sure we start unhydrated
        env.hydration.dehydrate(testPath)

        val openResult = env.hydration.openForRead("smoke", "h1", testPath)
        assertTrue(openResult is OpenResult.Ok)
        val cachePath = (openResult as OpenResult.Ok).cachePath
        val readBack = java.nio.file.Files.readString(cachePath)
        assertTrue(readBack == "smoke-test-payload")

        env.hydration.closeHandle("smoke", "h1")
        val rehydrate = env.hydration.dehydrate(testPath)
        assertTrue(rehydrate == DehydrateResult.Ok)
    }
}
```

- [ ] **Step 2: Verify the test is opt-in**

Without `UNIDRIVE_INTEGRATION_TESTS=true`:
```
./gradlew :app:hydration:test --tests HydrationSmokeTest -q
```
Expected: the assumption fails fast (`SKIPPED`), test class passes overall.

- [ ] **Step 3: Run the smoke (manually, on a configured live account)**

```
UNIDRIVE_INTEGRATION_TESTS=true UNIDRIVE_TEST_ACCESS_TOKEN=... ./gradlew :app:hydration:test --tests HydrationSmokeTest -q
```

Expected: PASS.

- [ ] **Step 4: Final full check from `core/`**

```
./gradlew check -q
```

Expected: BUILD SUCCESSFUL across all modules.

- [ ] **Step 5: Commit**

```bash
git add core/app/hydration/src/test/kotlin/org/krost/unidrive/hydration/HydrationSmokeTest.kt
git commit -m "test(hydration): live-integration smoke (third sync smoke)"
```

### Task 16: BACKLOG move

**Files:**
- Modify: `BACKLOG.md`
- Modify: `CLOSED.md`

- [ ] **Step 1: Find the BACKLOG entry that Phase 1 closes**

Phase 1 is a step toward the deferred "Virtual filesystem layer (placeholders)" entry, but does NOT close it (that needs Phase 2/3 too). Phase 1 instead closes the *core SPI* portion. **Do NOT remove the deferred entry yet.** Instead, add an entry to CLOSED.md documenting what Phase 1 delivered as a stepping stone.

- [ ] **Step 2: Add to CLOSED.md (top of "Drained from BACKLOG")**

```markdown
- Hydration SPI (Phase 1 of the sparse-hydration roadmap). New `app:hydration` module with a thin verb-based `Hydration` interface (`openForRead`, `openForWrite`, `closeHandle`, `hydrate`, `dehydrate`, `events: Flow<HydrationEvent>`). Wires to existing `SyncEngine` via two new public hooks (`ensureHydrated`, `uploadFromCache`); reads `state.db.is_hydrated` directly. Connection-scoped open-set so a co-daemon crash (Phase 2 consumer) auto-cleans entries on IPC disconnect. `IpcServer` extended with inbound-handler registration to support request/reply alongside its existing broadcast. Six verbs land as JSON-line RPCs. Spec: `docs/dev/specs/sparse-hydration-roadmap-design.md`. Phase 2 (Rust FUSE co-daemon) is the next ship-target; the deferred "Virtual filesystem layer" BACKLOG entry remains open until Phase 2 + Phase 3 ship.
```

- [ ] **Step 3: Commit**

```bash
git add BACKLOG.md CLOSED.md
git commit -m "docs(closed): mark Phase 1 of the sparse-hydration roadmap as landed"
```

---

## Self-review

Run through these checks against the spec before declaring the plan complete:

**1. Spec coverage:**

| Spec section | Plan task(s) |
|---|---|
| Phase 1 — `core/app/hydration/` Gradle module | Task 1 |
| `Hydration` interface (6 verbs + events Flow) | Task 5 |
| `HydrationImpl` (wires SyncEngine + StateDatabase, connection-scoped open-set) | Tasks 7–12 |
| `HydrationEvent` sealed class | Task 4 |
| `HydrationError` sealed interface | Task 2 |
| `HydrationIpcHandler` (six verbs as JSON-line over UDS) | Task 13 |
| `IpcServer` extended with inbound handler registration | Task 3 |
| `SyncEngine` public hooks (`ensureHydrated`, `uploadFromCache`) | Task 6 |
| Connection-scoped open-set + IPC-disconnect cleanup | Tasks 8, 11 |
| Six unit tests + JSON-round-trip + smoke | Tasks 2, 4, 7–13, 15 |
| Live-integration smoke (third sync in 5+5+2) | Task 15 |
| BACKLOG → CLOSED bookkeeping | Task 16 |

**2. Placeholder scan:**
The plan includes one `TODO()` stub-pattern: in Task 7's `HydrationImpl` skeleton, the verbs not yet implemented are `TODO("Task N")`. Each `TODO` is removed by the named later task. This is intentional incremental scaffolding, not a plan-failure placeholder.

Task 14's Step 3 ("Per-connection ID plumbing") refers to a known shortcut introduced in Step 2 and explicitly fixes it before commit. Not a final-state placeholder.

No `TBD` / "implement appropriate error handling" / "add validation" in the plan.

**3. Type consistency:**
Method names cross-referenced — `openForRead`, `openForWrite`, `closeHandle`, `hydrate`, `dehydrate`, `onConnectionClosed`, `ensureHydrated`, `uploadFromCache` — all spell the same way in every task that references them. JSON verbs all use the `hydration.<verb_name>` prefix consistently.

`OpenResult.Ok` / `OpenResult.Failed`, `HydrateResult.Ok` / `Failed`, `DehydrateResult.Ok` / `Busy` / `Failed` consistent between interface (Task 5) and impl (Tasks 7+) and handler (Task 13).

**Gap:** the plan implicitly depends on `StateDatabase` exposing `getEntry(path)`, `isHydrated(path)`, `insertUnhydratedEntry`, `insertHydratedEntry`, `markUnhydrated`. Some of these may not exist on the current `StateDatabase` API. The implementing agent should verify each method exists; if not, the simplest fix is to add narrow query helpers in the same commit as the test that needs them.

**4. Ambiguity check:** the plan deliberately defers a few decisions to the implementing agent's discretion when the existing-codebase shape determines the answer (e.g. "which of `applyDownload` / `dispatchStreamingDownload` is the right delegation target" in Task 6, or "which file constructs `IpcServer`" in Task 14). Each such deferral is bracketed with explicit guidance for the choice. Not ambiguity — judgement.
