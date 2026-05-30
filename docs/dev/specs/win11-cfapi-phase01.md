# Spec — Windows 11 CfAPI mount: Phase 0 + Phase 1 (implementation)

Companion to the plan `docs/dev/plans/win11-cfapi-mount.md`. This is the implementation-level breakdown of the first two phases (de-risk + read-only mount). Phases 2–4 (writeback, shell UX, packaging) are sketched in the plan and specced later.

**Status:** Proposed. **Prereq decisions (from the plan §6.3):** `unidrive-windows` sibling repo vs in-tree `windows/`; unpackaged `CfRegisterSyncRoot` for Phase 1 (defer MSIX); lifecycle shape = client-is-the-Service.

---

## Shared substrate (built in Phase 0, used by everything)

**Where it lives:** a sibling repo **`unidrive-windows`** (mirrors `unidrive-mount-linux`; satisfies the `AGENTS.md` "platforms are separate apps" rule) — a .NET 8 solution:
- `Unidrive.Ipc` — the AF_UNIX NDJSON client (the spine)
- `Unidrive.CfApi` — `cldapi.dll` bindings via **CsWin32** + the callback↔verb binding (Phase 1+)
- `Unidrive.WinHost` — the Windows Service host / lifecycle
- `*.Tests`

**The IPC client — load-bearing design:**
- **Socket path:** replicate `IpcServer.defaultSocketPath(profile)` exactly — `%TEMP%\unidrive-ipc\unidrive-<profile>.sock`, with the SHA-1-hash + `.meta` sidecar fallback for names > 90 chars.
- **Connection model (important):** the wire is strictly *one-line request → one-line reply, no request IDs* — a connection **cannot multiplex**. Concurrency = **a small pool of req/reply connections (N ≈ 6, under the daemon's `MAX_CLIENTS = 10`) + one dedicated long-lived `subscribe` connection** (which becomes a one-way event stream after its `{ok:true}` reply). CfAPI delivers callbacks on driver thread-pool threads → each grabs a pooled connection.
- **Framing:** newline-delimited UTF-8, requests capped at 64 KiB (`MAX_REQUEST_BYTES`); `System.Text.Json`.
- **Reconnect:** on daemon restart, the pool re-dials and the subscribe reader re-subscribes and treats it as a full `view.invalidated` (drop all cached placeholder metadata).
- **Error tokens → status:** `unknown_path` / `not_found` → object-not-found; everything else → retryable IO (exact `NTSTATUS` / `STATUS_CLOUD_FILE_*` verified against MS Learn during impl).

---

## Phase 0 — De-risk & decide (~1–2 wks)

| # | Task | Detail | Acceptance |
|---|---|---|---|
| **0.1** | **Orphan-recovery tool** (do *first*) | Admin tool (C# console or documented PowerShell). Walk a volume for dirs with a cloud reparse tag (`FSCTL_GET_REPARSE_POINT`, tag `0x9000xxxx`). For a *registered* root → `CfRevertPlaceholder` + `CfUnregisterSyncRoot`. For *true orphans* (no provider) → `fltmc detach cldflt C:` → delete → `fltmc attach cldflt C:` (warn: briefly disables OneDrive/etc. on that volume), or document Safe Mode. Tracked as a GitHub issue; references #237. | A reusable recovery command exists + is documented; the dev loop is safe before any CfAPI experimentation. (The original four `unidrive-cfapi-test*` orphans were already cleaned earlier.) |
| **0.2** | Fix #239 | Make `MountCommandTest` + `SyncEngineTest` cache assertions OS-agnostic (build expected paths via `Path`/`File.separator`, or `assumeTrue(isLinux)`-guard the UDS-path-specific ones). Small Kotlin PR in `core/`. | `./gradlew check` green on Windows (the known #239 failures fixed); unblocks a Windows CI runner. |
| **0.3** | C# skeleton + IPC client | Build `Unidrive.Ipc` (pool + subscribe reader, reconnect). Validate the seam end-to-end: run the existing JVM daemon on Windows (`java -jar unidrive.jar -p <profile> daemon run`), confirm it binds the `%TEMP%` socket, then round-trip `{"verb":"daemon.status"}`. | `dotnet run` prints the daemon's `uptime_ms` / `clients_connected` against a live Windows JVM daemon — proves the IPC contract works on Windows before touching CfAPI. |
| **0.4** | Decisions | Packaging: **unpackaged `CfRegisterSyncRoot` for Phase 1 dev speed**, move to MSIX/sparse-package for Explorer status-UI in Phase 3/4. Lifecycle: **shape (i)** — the client *is* a Windows Service that supervises the JVM daemon. | Recorded in the plan. |

---

## Phase 1 — Read-only mount (~3–4 wks)

| # | Task | CfAPI | → IPC verb | Notes / acceptance |
|---|---|---|---|---|
| **1.1** | Register + connect sync root | `CfRegisterSyncRoot(root, CF_SYNC_REGISTRATION, CF_SYNC_POLICIES{Hydration=PARTIAL, Population=PARTIAL}, …)` → `CfConnectSyncRoot(root, callbackTable, …, &key)` | — | Root = `%USERPROFILE%\unidrive\<profile>`. Keep the connection alive for the session (provider "running"). **Accept:** root appears in Explorer; top-level placeholders render. |
| **1.2** | Seed + expand dirs | `FETCH_PLACEHOLDERS` callback → `CfCreatePlaceholders(parent, infos[], …)` | `hydration.list(prefix)` | Map each entry → `CF_PLACEHOLDER_CREATE_INFO{RelativeFileName, FsMetadata(size, mtime_ms, attrs), FileIdentity=<logical path>, flags(folder→DIRECTORY; cloud-only→not-in-sync)}`. **FileIdentity carries the engine's logical path** so later callbacks resolve it. **Skip names `localNameIssue()` rejects** (don't try to place a `....`). **Accept:** expanding a folder lists its cloud children as placeholders. |
| **1.3** | Hydrate on read | `FETCH_DATA` callback → read `cache_path`, push bytes via `CfExecute(CF_OPERATION_TYPE_TRANSFER_DATA, {TransferKey, Buffer, Offset, Length})` + `CfReportProviderProgress` | `hydration.open_read(handle, path)` → `cache_path`, then `hydration.close_handle` | **Honor never-serve-short:** if `open_read` errors (EIO) or the cache file is incomplete (`size != expected`), complete the transfer with a *failure* status — fail the read, never serve partial bytes. **Accept:** opening a cloud-only file downloads + reads correctly; a corrupt/incomplete cache fails cleanly (no truncated file). |
| **1.4** | Dehydrate / "free up space" | `DEHYDRATE` callback or `CfSetPinState(UNPINNED)` → `CfDehydratePlaceholder` / `CfUpdatePlaceholder(DEHYDRATE)` | `hydration.dehydrate(path)` | If `busy` (handle open) → defer/fail. **Accept:** "free up space" reverts a hydrated file to a placeholder + the engine evicts the cache file. |
| **1.5** | Live refresh | consume the `subscribe` stream → `CfUpdatePlaceholder` / re-`FETCH_PLACEHOLDERS` the affected dirs | `hydration.subscribe` (on mount; also triggers a guarded `sync.enumerate` daemon-side) | On `view.invalidated{paths}` invalidate those dirs; on `{full:true}` re-enumerate visible dirs. **Accept:** a remote rename/delete (after the daemon enumerates) reflects in Explorer without remount. |
| **1.6** | **Teardown (the P0)** | on Service stop/disable/uninstall: `CfRevertPlaceholder` each (if leaving plain files) **then** `CfUnregisterSyncRoot(root)`; on startup, detect + clean stale-registered roots | — | **Never exit registered-but-disconnected.** **Accept:** stop/uninstall leaves **zero** undeletable placeholders; kill-then-restart self-heals stale roots. |

---

## Sequencing, effort, blockers

- **Critical path:** 0.1 (recovery) → 0.3 (IPC client) → 1.1/1.6 (register + teardown, co-built so iteration is safe) → 1.2 → 1.3 → 1.5 → 1.4. Teardown (1.6) is built *with* registration (1.1), not bolted on at the end. 0.2 (#239) is parallel and unblocks Windows CI.
- **Effort:** Phase 0 ≈ 1–2 wks (IPC client + recovery tool + #239 are the bulk); Phase 1 ≈ 3–4 wks (CfAPI binding + the FileIdentity/round-trip plumbing). One dev familiar with CfAPI; the official **CloudMirror** sample shortcuts 1.1–1.4.
- **Decisions that block 0.3+ (need a call):** (1) `unidrive-windows` sibling repo vs in-tree `windows/` — recommend the sibling repo; (2) confirm unpackaged `CfRegisterSyncRoot` for Phase 1; (3) confirm lifecycle shape (i).
