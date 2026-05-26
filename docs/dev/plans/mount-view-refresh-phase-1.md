# Mount-view refresh — Phase 1 implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Make `unidrive refresh` work on a mounted profile by adding a one-way remote→`state.db` enumeration path that never scans `sync_root`, never plans a local→remote delete, and never reaches the deletion guards.

**Architecture:** New `SyncEngine.enumerateRemoteIntoState(reset)` reuses the existing remote-gather + `state.db`-upsert helpers but skips the local scan, the reconcile/apply loop, and the guards. Remote-observed deletions flip `state.db` rows (+ evict the hydration cache) **only on a complete enumeration**. Surfaced as a new IPC verb `sync.enumerate`; `RefreshRpcHandler` routes to it when a mount client is connected. Spec: `docs/dev/specs/mount-view-refresh-design.md`.

**Tech Stack:** Kotlin, Gradle (`core` composite build), kotlin.test + kotlinx-coroutines-test, SQLite (`StateDatabase`), JSON-line UDS IPC.

**Before you start — read these to confirm exact signatures (the plan reuses them):**
- `core/app/sync/.../SyncEngine.kt`: `gatherRemoteChanges` (~`:1353`), `updateRemoteEntries`/`db.batch` (~`:860`), `entryFromCloudItem` (~`:2667`), `detectMissingAfterFullSync` (~`:2097`), `promotePendingCursor` (~`:2070`), `resolveCachePath` (~`:345`), the `pending_cursor`/complete flag, and `doSyncOnce` (~`:504`) for how these compose.
- `core/app/sync/.../StateDatabase.kt`: `resetAll` (~`:72`), `markDeleted` (~`:602`), `getHydratedEntryCount` (~`:457`), `listDirectChildren` (~`:539`), `batch{}`.
- `core/app/cli/.../RefreshRpcHandler.kt` (the in-flight `AtomicReference<RefreshJob>` pattern, `:65`), `core/app/cli/.../DaemonRuntime.kt` (`:119-160`: engine/handler wiring, `server.clientCount`).
- The existing sync test harness + fake provider (find via `grep -rln "FakeProvider\|FakeCloudProvider\|: CloudProvider" core/app/sync/src/test`); model new tests on it.

**Module/test commands:** `cd core && ./gradlew :app:sync:test --tests "<FQN>" --console=plain -q` ; CLI handler tests: `:app:cli:test`.

**Self-contained changes per task; commit after each.**

---

## File structure

- Create: `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/EnumerateResult.kt` — result value type.
- Modify: `core/app/sync/src/main/kotlin/org/krost/unidrive/sync/SyncEngine.kt` — add `enumerateRemoteIntoState`.
- Create: `core/app/sync/src/test/kotlin/org/krost/unidrive/sync/EnumerateRemoteIntoStateTest.kt` — engine tests.
- Create: `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/EnumerateRpcHandler.kt` — `sync.enumerate` verb.
- Modify: `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/RefreshRpcHandler.kt` — route mount→enumerate.
- Modify: `core/app/cli/src/main/kotlin/org/krost/unidrive/cli/DaemonRuntime.kt` — register `sync.enumerate`; pass a `mountClientConnected: () -> Boolean` probe.
- Create: `core/app/cli/src/test/kotlin/org/krost/unidrive/cli/RefreshRoutingTest.kt` — routing test.

---

## Task 1: `enumerateRemoteIntoState` happy path (remote→state.db, no scan, no guards)

**Files:** Create `EnumerateResult.kt`, `EnumerateRemoteIntoStateTest.kt`; Modify `SyncEngine.kt`.

- [ ] **Step 1: Write the failing test.** In `EnumerateRemoteIntoStateTest.kt`, build a `SyncEngine` with the existing test fake provider, an **empty `sync_root`** (temp dir), and a fresh `state.db`. Seed the fake provider's remote with two files `/a.txt`, `/dir/b.txt`. Assert that after `engine.enumerateRemoteIntoState(reset = false)`:

```kotlin
@Test
fun `enumerate upserts remote entries into state_db without scanning sync_root or planning deletes`() = runTest {
    val provider = FakeProvider().apply { putRemote("/a.txt", "AAA"); putRemote("/dir/b.txt", "BBB") }
    val engine = newEngine(provider, syncRoot = emptyTempDir(), db = freshStateDb())
    val result = engine.enumerateRemoteIntoState(reset = false)
    assertTrue(result.ok, "enumerate must succeed against an empty sync_root (no guard)")
    assertEquals(setOf("/a.txt", "/dir"), db.listDirectChildren("/").map { it.path }.toSet())
    assertNotNull(db.getEntry("/dir/b.txt"))
    assertEquals(0, provider.deletedPaths.size, "enumerate must never call provider.delete")
}
```

(Match `newEngine`/`FakeProvider`/`freshStateDb`/`emptyTempDir` to the existing harness you read in pre-reqs; reuse its fake, don't invent a new one.)

- [ ] **Step 2: Run it — expect FAIL** (`enumerateRemoteIntoState` unresolved). `cd core && ./gradlew :app:sync:test --tests "*.EnumerateRemoteIntoStateTest" -q`.

- [ ] **Step 3: Add `EnumerateResult`.**

```kotlin
package org.krost.unidrive.sync
data class EnumerateResult(
    val ok: Boolean,
    val upserted: Int = 0,
    val reaped: Int = 0,
    val complete: Boolean = false,
    val error: String? = null,
)
```

- [ ] **Step 4: Implement `enumerateRemoteIntoState` (skeleton — confirm helper signatures against the cited anchors).** Add to `SyncEngine`:

```kotlin
/**
 * One-way remote→state.db refresh for view consumers (the FUSE mount). Reuses the remote
 * gather + state.db upsert, but NEVER scans sync_root, NEVER plans/executes a local→remote
 * delete, and NEVER evaluates the empty-sync_root / max_delete_* guards. Remote-observed
 * deletions flip state.db rows only on a COMPLETE enumeration (see Task 2). See
 * docs/dev/specs/mount-view-refresh-design.md.
 */
suspend fun enumerateRemoteIntoState(reset: Boolean): EnumerateResult {
    if (reset) db.resetAll()
    val gather = try {
        gatherRemoteChanges(/* same args doSyncOnce passes for the remote-only path */)
    } catch (e: ProviderException) {
        return EnumerateResult(ok = false, error = e.message)
    }
    var upserted = 0
    db.batch {
        for (item in gather.remoteItems) {            // adds + mods
            updateRemoteEntries(/* entryFromCloudItem(item) per existing mapping */)
            upserted++
        }
    }
    promotePendingCursor()                            // incremental next time
    return EnumerateResult(ok = true, upserted = upserted, complete = gather.complete)
    // Task 2 inserts the complete-enumeration-only deletion reaping before the return.
}
```

Key: route the remote-item upsert through the SAME `db.batch { updateRemoteEntries(...) }` + `entryFromCloudItem` the legacy path uses (constraint B), so `hydration.list` sees well-formed rows. Do NOT call `scanner.scan()`, the reconciler, the apply loop, or any guard.

- [ ] **Step 5: Run the test — expect PASS.** Same command. Confirm `provider.deletedPaths` is empty and rows appear.

- [ ] **Step 6: Commit.** `git add … && git commit -m "feat(sync): enumerateRemoteIntoState — one-way remote→state.db refresh (no sync_root scan, no guards)"`

---

## Task 2: complete-enumeration-only remote-deletion reaping (the safety invariant)

**Files:** Modify `SyncEngine.kt`, `EnumerateRemoteIntoStateTest.kt`. This is constraint C / spec §3.1 — the riskiest invariant. **Two orthogonal named tests.**

- [ ] **Step 1: Write failing test A — incomplete enumeration must NOT reap.**

```kotlin
@Test
fun `incomplete enumeration does not mark omitted rows deleted`() = runTest {
    val provider = FakeProvider().apply { putRemote("/keep.txt", "K") }
    val engine = newEngine(provider, emptyTempDir(), freshStateDb())
    engine.enumerateRemoteIntoState(reset = false)              // /keep.txt now tracked
    provider.markNextDeltaIncomplete()                          // partial page omits /keep.txt
    val r = engine.enumerateRemoteIntoState(reset = false)
    assertFalse(r.complete)
    assertNotNull(db.getEntry("/keep.txt"), "omitted path on an INCOMPLETE delta must NOT be reaped")
    assertEquals(0, r.reaped)
}
```

- [ ] **Step 2: Write failing test B — complete enumeration that genuinely drops a path reaps it.**

```kotlin
@Test
fun `complete enumeration reaps a remotely-deleted path`() = runTest {
    val provider = FakeProvider().apply { putRemote("/gone.txt", "G"); putRemote("/stay.txt", "S") }
    val engine = newEngine(provider, emptyTempDir(), freshStateDb())
    engine.enumerateRemoteIntoState(reset = false)
    provider.removeRemote("/gone.txt")                         // genuinely deleted on the remote
    val r = engine.enumerateRemoteIntoState(reset = true)      // complete re-enumeration
    assertTrue(r.complete)
    assertNull(db.getEntry("/gone.txt")?.takeIf { !it.isDeleted }, "complete enum must reap /gone.txt")
    assertNotNull(db.getEntry("/stay.txt"))
    assertEquals(0, provider.deletedPaths.size, "reaping is a state.db flip, NOT a provider.delete")
}
```

- [ ] **Step 3: Run both — expect FAIL** (no reaping yet; test B fails because `/gone.txt` lingers).

- [ ] **Step 4: Implement.** Before the `return` in `enumerateRemoteIntoState`, add (reusing `detectMissingAfterFullSync` `:2097`, routed to a state-only flip — constraint C):

```kotlin
    var reaped = 0
    if (gather.complete) {                                  // ONLY on a complete enumeration
        val missing = detectMissingAfterFullSync(/* present remote ids/paths from gather */)
        db.batch {
            for (path in missing) {
                db.markDeleted(path)                        // state.db flip, NOT provider.delete
                reaped++
                // Task 3 adds cache eviction here.
            }
        }
    }
    promotePendingCursor()
    return EnumerateResult(ok = true, upserted = upserted, reaped = reaped, complete = gather.complete)
```

- [ ] **Step 5: Run both — expect PASS.** Confirm test A keeps `/keep.txt`, test B reaps `/gone.txt`, and `provider.deletedPaths` stays empty in both.

- [ ] **Step 6: Commit.** `git commit -m "feat(sync): enumerate reaps remote deletions into state.db only on a complete enumeration (safety invariant + 2 named tests)"`

---

## Task 3: evict the hydration cache on remote-delete

**Files:** Modify `SyncEngine.kt`, `EnumerateRemoteIntoStateTest.kt`.

- [ ] **Step 1: Failing test.**

```kotlin
@Test
fun `reaping a remotely-deleted hydrated path evicts its cache file`() = runTest {
    val provider = FakeProvider().apply { putRemote("/big.bin", "X".repeat(10)) }
    val engine = newEngine(provider, emptyTempDir(), freshStateDb())
    engine.enumerateRemoteIntoState(reset = false)
    val cache = engine.resolveCachePath("/big.bin").also { Files.createDirectories(it.parent); Files.writeString(it, "X".repeat(10)) }
    provider.removeRemote("/big.bin")
    engine.enumerateRemoteIntoState(reset = true)
    assertFalse(Files.exists(cache), "cache file must be evicted when the remote path is reaped")
}
```

- [ ] **Step 2: Run — expect FAIL.**

- [ ] **Step 3: Implement.** In the Task-2 reaping loop, after `db.markDeleted(path)`:

```kotlin
                runCatching { Files.deleteIfExists(resolveCachePath(path)) } // idempotent; partial cleanup must not abort
```

- [ ] **Step 4: Run — expect PASS.**

- [ ] **Step 5: Commit.** `git commit -m "feat(sync): evict hydration cache on enumerate-reaped remote deletion"`

---

## Task 4: cursor promotion + reset semantics

**Files:** `EnumerateRemoteIntoStateTest.kt` (behavior is already implemented in Tasks 1–2; this pins it).

- [ ] **Step 1: Failing test — second enumeration resumes incrementally; reset re-enumerates.**

```kotlin
@Test
fun `enumerate resumes from the persisted cursor unless reset`() = runTest {
    val provider = FakeProvider().apply { putRemote("/a.txt", "A") }
    val engine = newEngine(provider, emptyTempDir(), freshStateDb())
    engine.enumerateRemoteIntoState(reset = false)
    val firstCursor = provider.deltaCursorsSeen.last()
    engine.enumerateRemoteIntoState(reset = false)
    assertEquals(firstCursor, provider.deltaCursorsSeen.dropLast(1).last(),
        "second enumerate must resume from the cursor the first one ended on")
    engine.enumerateRemoteIntoState(reset = true)
    assertNull(provider.deltaCursorsSeen.last(), "reset must enumerate from a null cursor")
}
```

(Adapt the cursor-assertion to the fake's recorded-cursor hook — same shape the legacy sync tests use.)

- [ ] **Step 2: Run — expect PASS or FAIL.** If `promotePendingCursor` already runs (Task 1) it may pass; if `reset` doesn't null the cursor, fix `resetAll()` ordering. Iterate to green.

- [ ] **Step 3: Commit (if any change).** `git commit -m "test(sync): pin enumerate cursor-resume + reset semantics"`

---

## Task 5: `sync.enumerate` IPC verb

**Files:** Create `EnumerateRpcHandler.kt`; Modify `DaemonRuntime.kt`; create handler test.

- [ ] **Step 1: Failing test.** Model on `RefreshRpcHandlerTest` (find it). Assert `handle()` on a fake engine returns a terminal `{"event":"enumerate.done","ok":true,...}` and that a concurrent call returns `busy`.

- [ ] **Step 2: Run — expect FAIL.**

- [ ] **Step 3: Implement `EnumerateRpcHandler`** mirroring `RefreshRpcHandler` (same `AtomicReference` in-flight guard, same `scope.launch`/event-emit shape; use `compareAndSet(null, job)` BEFORE launch per the BACKLOG-64 defensive note). It calls `engine.enumerateRemoteIntoState(reset)`, emits `enumerate.done` with `upserted`/`reaped`/`complete`, maps `ProviderException`→`provider_error`, `CancellationException`→`shutdown`. JSON-encode via the existing encoder (not string concat).

- [ ] **Step 4: Register the verb** in `DaemonRuntime` next to `refresh.run` (`:153`): `server.registerHandler("sync.enumerate", EnumerateRpcHandler(engine, scope))`.

- [ ] **Step 5: Run — expect PASS.**

- [ ] **Step 6: Commit.** `git commit -m "feat(daemon): sync.enumerate IPC verb (one-way remote→state.db refresh)"`

---

## Task 6: `RefreshRpcHandler` routes mount profiles to enumerate

**Files:** Modify `RefreshRpcHandler.kt`, `DaemonRuntime.kt`; create `RefreshRoutingTest.kt`.

- [ ] **Step 1: Failing test.** Build a `RefreshRpcHandler` with a `mountClientConnected` probe and a fake engine recording which method ran. Assert: probe→true ⇒ `enumerateRemoteIntoState` called, `syncOnce` NOT called; probe→false ⇒ `syncOnce` called (legacy unchanged).

```kotlin
@Test
fun `refresh routes to enumerate when a mount client is connected`() = runTest {
    val engine = RecordingEngine()
    val handler = RefreshRpcHandler(engine, scope, mountClientConnected = { true })
    handler.handle(refreshRunRequest(reset = false)).awaitTerminal()
    assertTrue(engine.enumerateCalled); assertFalse(engine.syncOnceCalled)
}
@Test
fun `refresh uses legacy reconcile when no mount client is connected`() = runTest {
    val engine = RecordingEngine()
    val handler = RefreshRpcHandler(engine, scope, mountClientConnected = { false })
    handler.handle(refreshRunRequest(reset = false)).awaitTerminal()
    assertTrue(engine.syncOnceCalled); assertFalse(engine.enumerateCalled)
}
```

- [ ] **Step 2: Run — expect FAIL.**

- [ ] **Step 3: Implement.** Add `mountClientConnected: () -> Boolean` ctor param to `RefreshRpcHandler`; in `handle`, branch: `if (mountClientConnected()) engine.enumerateRemoteIntoState(reset) else engine.syncOnce(skipTransfers = true, skipRemoteGather = false, …)`. In `DaemonRuntime`, pass `mountClientConnected = { server.clientCount > 0 }` (confirm the connected-client accessor at `DaemonRuntime.kt:160` / `IpcServer`). Keep the emitted event shape identical so `unidrive refresh`'s client rendering is unchanged.

- [ ] **Step 4: Run — expect PASS.** Run the full `:app:cli:test` + `:app:sync:test` suites for regressions.

- [ ] **Step 5: Commit.** `git commit -m "feat(daemon): route refresh to enumerate-only for mounted profiles (no sync_root reconcile)"`

---

## Live test-drive (Phase-1 acceptance — needs the running daemons/mounts)

With the two test-account mounts up (`internxt_test`, `posteo_onedrive`):

- [ ] Deploy (`scripts/dev/redeploy-local.sh`), restart the daemons (pick up new code), remount.
- [ ] `unidrive -p internxt_test refresh` → **succeeds** (no empty-sync_root guard), `sync_root` untouched, `state.db` reflects the remote; `ls /tmp/ud-internxt_test` shows current remote content.
- [ ] `unidrive -p posteo_onedrive refresh` → **succeeds** (no 77%-deletion safeguard), view reflects the remote.
- [ ] Delete a file on the provider web UI → `unidrive refresh` → the path disappears from the mount (and its cache file is evicted); a file added on the web UI appears.
- [ ] Confirm **no** provider `delete()` was issued during any refresh (daemon log).

## Subsequent phases (separate plans)

- **Phase 2:** `--poll-interval` on `daemon run` (jitter, in-flight-guarded, `Retry-After` backoff) + the §3.2 corroboration guard (defer reaping >`max(50, 20%)` until a 2nd enumeration) + optional `sync_direction="passive"`. Requires the `unidrive-daemon-design.md` G3 spec amendment (BACKLOG-58).
- **Phase 3:** FUSE dir/attr cache invalidation in `unidrive-mount-linux` via a `view.invalidated` event on the `hydration.subscribe` stream (file as a sibling-repo BACKLOG entry when Phase 1 lands).
