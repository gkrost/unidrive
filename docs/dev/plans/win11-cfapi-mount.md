# Plan — Windows 11 mount via the Cloud Files API (CfAPI)

**Status:** Active — superseded in part by the #290 decision: the **read-only tier (Phases 0–1) is in the MVP**; writeback, shell UX and MSIX packaging (Phases 2–4) are post-MVP (unidrive-windows#5/#6/#7). Implementation lives in `gkrost/unidrive-windows` (Phase 1.3 reached; 1.4/1.5 verification tracked as unidrive-windows#3/#4). Surface gate: unidrive-windows#9 against `docs/dev/specs/mvp-acceptance-criteria.md`.
**Date:** 2026-05-29
**Related ADRs:** `docs/adr/multi-platform.md` (authoritative — the planned Windows desktop tier), `docs/adr/core-app-contract.md` (engine/client split), `docs/adr/linux-only.md` (ADR-0012, Windows out of MVP), `docs/adr/shipping-surface.md` (ADR-0011, `shell-win` removal).
**Related specs:** `docs/dev/specs/unidrive-daemon-design.md`, `docs/dev/specs/mount-view-refresh-design.md`, `docs/dev/specs/sparse-hydration-roadmap-design.md`.
**Related issues:** #237 (teardown — P0), #238 (watch-handle release / teardown verb), #239 (Linux-path test assumptions), #230 (invalid NTFS names — closed), #99 (tracking-set engine = the engine/client module boundary), #142 (auto-spawn daemon). *These are **GitHub issues**, not `BACKLOG.md` entries — the repo tracks work in GitHub issues (see the migration note atop `BACKLOG.md`).*

---

## TL;DR

Yes — **CfAPI is the right mechanism**, and it's already the *planned* Windows desktop tier (`multi-platform.md`: "Full Explorer integration via the Cloud Files API … replaces the first-party OneDrive / Internxt desktop clients"). FUSE is Linux-only, so the Windows path is CfAPI.

The structural insight: **a Windows mount is the exact Windows twin of the existing Linux Rust FUSE co-daemon.** It is a *separate client app* (outside `core/app/`) that binds the OS filesystem API (CfAPI) to unidrive's **existing hydration SPI over the existing AF_UNIX NDJSON IPC**. The daemon/engine side needs **essentially zero changes** — `IpcServer` already emits a Windows socket path (`%TEMP%\unidrive-ipc\unidrive-<profile>.sock`).

**Sharpened decisions (this doc, §6):**
- **Client language → C# / .NET 8 + WinUI 3.** Best CfAPI + Explorer-shell ergonomics; the ADR already anticipates it.
- **Deployment → IPC to the JVM daemon now, in-proc embed later (post-#99).** Fastest to a validated mount, reuses the whole backend; the IPC verb contract is the stable seam, so it isn't a dead end.

**Most important build-first item:** the **teardown trap (#237)** — orphaned CfAPI placeholders are *undeletable* and the bug will recur on every dev iteration if `CfUnregisterSyncRoot` + `CfRevertPlaceholder` aren't wired from day one.

---

## 1. Why CfAPI (and not WinFsp / ProjFS / folder-sync)

| Option | Verdict |
|---|---|
| **CfAPI** (`cldflt` minifilter + `cldapi.dll`) | ✅ **Target.** The native cloud-placeholder API OneDrive/Dropbox use: true sparse placeholders, on-demand hydration, pin / dehydrate ("free up space"), Explorer status overlays + context menu. Matches the ADR's "replace the first-party clients" goal. Heaviest to build; real teardown hazards (§4). |
| WinFsp / Dokany (user-mode FS, FUSE-like) | Fallback only. Closest to the Linux co-daemon mental model, but presents a mounted *volume*, not native cloud placeholders — no native pin/dehydrate/overlay UX, extra driver dependency. |
| ProjFS (Projected File System) | Built for virtualization (VFSForGit); weaker fit for a sync-back/upload model and lacks the cloud-sync UX (status, pin) CfAPI gives. |
| Plain folder-sync (today's legacy engine) | Doesn't solve the problem: a 625 GiB / 171k-file account shows an *empty folder* until everything downloads (see `BACKLOG.md`). The point of a mount is on-demand placeholders. |

---

## 2. Architecture — a Windows twin of the Linux FUSE co-daemon

```
┌──────────────────────────────┐      AF_UNIX, NDJSON         ┌───────────────────────────────┐
│  Windows CfAPI client (NEW)   │  ⇄  %TEMP%\unidrive-ipc\…  ⇄  │  per-profile daemon (REUSE)     │
│  C# / .NET 8 + WinUI 3        │      (already supported)     │  JVM: SyncEngine + Hydration    │
│  • registers CfAPI sync root  │                              │  + state.db + IpcServer + auth  │
│  • cldflt callbacks → IPC     │                              │  (the Linux co-daemon's peer)   │
│  • hydration cache + recovery │                              └───────────────────────────────┘
└──────────────────────────────┘
```

- **Lives outside `core/app/`** — `AGENTS.md` hard rule: "Don't grow daemon to host UI. Platforms are separate apps." The tier is a *client that consumes the engine*.
- **Engine / client split** (`core-app-contract.md`): the **engine** owns the hydration *state model* (which paths are hydrated vs cloud-only) and the deterministic `syncOnce()`; the **client** owns the CfAPI *binding*, the *when* of sync (a Windows Service tick), config persistence (registry/appdata), logging (Event Log/ETW), and all user-facing strings. The ADR explicitly allows "a Windows desktop client may use C# / WinUI / Cloud Files API directly."
- **Two processes** (per `unidrive-daemon-design.md`): the long-lived per-profile **daemon** (JVM) owns `state.db`/auth/`SyncEngine`/`Hydration`/`IpcServer`; the **client** connects as an IPC client. The client does *not* start the daemon or take the profile lock — if the daemon isn't running, the connect refuses and the client surfaces a clear "start the daemon" path.

---

## 3. The CfAPI ↔ hydration-IPC binding

The client translates `cldflt` callbacks into the hydration verbs the daemon already serves (transport: AF_UNIX `SOCK_STREAM`, newline-delimited JSON; success `{"ok":true,…}`, failure `{"ok":false,"error":"<token>"}`). Verbs/handlers: `core/app/hydration/.../HydrationIpcHandler.kt` → `HydrationImpl.kt`; SPI types in `Hydration.kt`.

| CfAPI callback / action | Hydration verb | Notes |
|---|---|---|
| Register + seed root | `hydration.list("")` → `CfCreatePlaceholders` | sync root e.g. `%USERPROFILE%\unidrive\<profile>` |
| `CF_CALLBACK_TYPE_FETCH_PLACEHOLDERS` (dir expand) | `hydration.list(prefix)` | map `{folder,size,mtime_ms,hydrated}` → placeholder metadata + `CF_PLACEHOLDER_*` flags |
| `CF_CALLBACK_TYPE_FETCH_DATA` (read cloud-only) | `hydration.open_read` → serve bytes from returned `cache_path` via `CfExecute(TRANSFER_DATA)` (+ `CfReportProviderProgress`) | **Honor never-serve-short-cache:** on EIO, *fail* the transfer; never ack hydration on a short/failed fetch (engine already throws→EIO if `cachedBytes != remoteSize`). |
| placeholder write / close | `hydration.create` (new) · `open_write_begin` (O_TRUNC) · `open_write` (cache_path) | upload is async + serialized per-path daemon-side; the call returns immediately |
| `CF_CALLBACK_TYPE_DELETE` | `hydration.unlink` / `rmdir` | `rmdir` evicts the cache subtree |
| `CF_CALLBACK_TYPE_RENAME` | `hydration.rename` | no atomic-replace; refuses if dest exists |
| mkdir | `hydration.mkdir` | |
| `CF_CALLBACK_TYPE_DEHYDRATE` / "free up space" | `hydration.dehydrate` | refuses with `busy` if any handle is open |
| pin / unpin (`CfSetPinState`) | `hydration.hydrate` / `dehydrate` | engine pin rules are **stubbed** (`Reconciler.kt:791` "reserved for future CfApi") — wire them |
| `view.invalidated` event → `CfUpdatePlaceholder` / re-list dir | (from `hydration.subscribe`) | the Windows analog of FUSE `notify_inval_entry`; emitted after a remote enumeration changes `state.db` |
| error token → NTSTATUS | — | `unknown_path` / `not_found` → object-not-found; everything else → a *retryable* IO status. (Verify exact `STATUS_CLOUD_FILE_*` codes against MS Learn.) |

**Hydration byte-serving (the `FETCH_DATA` contract):** CfAPI hydration is *push* — on `FETCH_DATA` the kernel hands the provider a `TransferKey`; the client reads bytes from the engine's `cache_path` and writes them into the placeholder via `CfExecute(CF_OPERATION_TYPE_TRANSFER_DATA)` (offset/length-chunked, with `CfReportProviderProgress`). It does **not** map the cache file in directly — it streams cache bytes through the transfer pipe, completing each chunk with a status. The **never-serve-short-cache** invariant binds here: if `open_read` returned but the cache is incomplete (the engine throws → EIO, or `cachedBytes != remoteSize`), the client must complete the transfer with a *failure* `NTSTATUS` so the read fails cleanly — never serve partial bytes (which a copy would silently truncate to a short / 0-byte file).

Plus refresh wiring the client should drive: on mount, issue `hydration.subscribe` (the daemon then schedules one guarded `sync.enumerate` so the view reflects remote renames/deletes); optionally `--poll-interval`. Verbs: `sync.enumerate` / `refresh.run` / `daemon.status` (`EnumerateRpcHandler`, `RefreshRpcHandler`, `DaemonRuntime`).

**Crash-recovery contract:** on (re)mount, the client scans its cache root and replays `hydration.open_write` with `handle_id = "recovery-<n>"` for any cache file newer than the last-synced watermark — the daemon special-cases the `recovery-` prefix to background the upload so the mount can come up. This is how durability survives a crash. The JVM-side handler is confirmed (`HydrationImpl.kt` special-cases the `recovery-` prefix and backgrounds the upload); the Linux consumer of it lives in the sibling `unidrive-mount-linux` repo (not in this checkout), so the **Windows client's scanner is net-new, not a port**. Cache layout to match: `<cacheRoot>/unidrive/hydration/<cacheKey>/<path>`, `cacheKey = profile.name`.

---

## 4. Teardown & lifecycle — **P0 (#237)**

The single hardest hazard. The handoff's four orphaned `unidrive-cfapi-test*` dirs are *undeletable* (Explorer, `Remove-Item -Force`, `fsutil`, even elevated all fail with `ERROR_CLOUD_FILE_PROVIDER_NOT_RUNNING` / `ACCESS_DENIED`) because **the `cldflt` driver vetoes deletes of its placeholders when no provider is connected.** Design rules:

- On **every** disable / uninstall / logout / profile-remove: call `CfUnregisterSyncRoot` for each sync root **and** `CfRevertPlaceholder` (or update to in-sync) **before** removing files.
- **Never leave a registered-but-not-running provider** — that creates the *same* orphan trap as an unregistered one. Tie placeholder liveness to a live provider connection.
- Ship a documented **orphan-recovery command** (admin `fltmc detach cldflt C:` / Safe Mode) and clean up the existing four orphans during bring-up.
- Daemon side (#238): release `ReadDirectoryChangesW` watch handles + add a teardown/`logout` verb so the sync_root isn't left locked-undeletable; document stale-`.lock` reclamation after a hard kill.

---

## 5. Reuse vs build

**Reuse unchanged (the whole backend):** `DaemonRuntime`/`DaemonCommand`, `StateDatabase` (`state.db`), provider auth, `SyncEngine` (hydration download/upload, remote mkdir/delete/rename, delta enumeration, cache management), the `Hydration` SPI (`HydrationImpl`), `IpcServer` + verb handlers, the `view.invalidated`/refresh wiring. The IPC transport already branches to a Windows socket path. **No protocol or daemon change required.**

**Reuse from existing code (don't reinvent):** the two **package-level functions** in `PlaceholderManager.kt` — *top-level functions in `package org.krost.unidrive.sync`, NOT methods on the `PlaceholderManager` class; call them bare, e.g. `localNameIssue(p)`, not `PlaceholderManager.localNameIssue(p)`*: `localNameIssue(remotePath)` (NTFS-name validation; `internal fun`, L81; already on `main` via #230) and `safeResolveLocal(syncRoot, remotePath)` (path-traversal guard; L22, two required args). The engine already *quarantines* unrepresentable names (#230 is on `main`; folder-name coverage via `applyCreatePlaceholder` is pending in PR #245), so the client only surfaces them.

**Build new (the C# CfAPI client):** (1) reconnecting AF_UNIX NDJSON IPC client; (2) the `cldflt` sync-root provider + callback→verb binding; (3) error-token→NTSTATUS mapping; (4) hydration-cache backing store + `recovery-` scanner rooted at `cacheKey=<profile.name>`; (5) `view.invalidated` → placeholder/dir invalidation.

---

## 6. Decisions (the open questions, sharpened)

### 6.1 Client language → **C# / .NET 8 + WinUI 3** ✅

Because the deployment model is IPC (§6.2), the client is fully decoupled from the Kotlin engine — it only (a) speaks NDJSON over AF_UNIX and (b) binds CfAPI. So optimize for CfAPI + shell ergonomics:

- **C# / .NET** — `Windows.Storage.Provider.StorageProviderSyncRootManager` (WinRT) for sync-root registration *with* Explorer integration; `cldapi.dll` P/Invoke via **CsWin32** (source-generated bindings) for `CfCreatePlaceholders`/`CfExecute`/`CfUpdatePlaceholder`/`CfSetPinState`/`CfUnregisterSyncRoot`; the official **CloudMirror / cloud-files .NET samples** are a near-direct template; WinUI 3 for overlays/context-menu/status; `UnixDomainSocketEndPoint` (`System.Net.Sockets`, .NET 5+) for the IPC client; first-class Windows Service + Event Log/ETW. `core-app-contract.md` already anticipates "C# / WinUI / Cloud Files API directly."
- **Rust (`windows-rs`)** — would share the IPC-client + cache + recovery scaffolding with the Linux FUSE co-daemon, but the CfAPI binding itself is all-new regardless of language, and Rust's WinRT `StorageProviderSyncRootManager` + shell-UX path is markedly more painful than C#/WinUI. The shareable surface (IPC framing, cache layout, recovery scan) is modest (~hundreds of LOC) and doesn't outweigh the CfAPI/shell ergonomics penalty.
- **Kotlin / JNI** — rejected: driving COM/WinRT + kernel callbacks from the JVM is the worst-ergonomics option with no precedent.

Tradeoff accepted: a third language (Kotlin engine + Rust Linux mount + C# Windows mount). Expected — platform tiers are separate apps with platform-idiomatic stacks.

### 6.2 Deployment model → **IPC to the JVM daemon now; in-proc embed later** ✅

- **(A) IPC to the JVM per-profile daemon** — for v1–v3. The C# client connects to the running JVM daemon over AF_UNIX NDJSON, exactly the Linux co-daemon architecture. Reuses the **entire** backend with **zero engine changes**. Cost: a JVM must run on Windows (bundle a `jlink`-minimized runtime, ~40–60 MB; run the daemon as a Windows Service) + one IPC hop. This matches unidrive's *existing* model — the daemon is already the JVM jar on every platform; only the mount front-end differs by OS.
- **(B) In-process embed** — the `core-app-contract.md` end-state ("platform apps embed the engine"): no JVM, no IPC. But the engine is Kotlin/JVM, so embedding needs either a GraalVM native-image of the engine exposed as a C-ABI lib (heavy: coroutines + sqlite-jdbc + HTTP-client native-image compat) or a re-implementation — and the engine **isn't extracted to an embeddable lib yet** (the ADR says the tracking-set engine, #99, "is the moment that boundary is drawn").

**Recommendation:** ship **(A)** first — fastest to a validated CfAPI mount, reuses proven code, mirrors Linux. Evolve to **(B)** after #99 extracts the engine boundary. **Not a dead end:** the client's CfAPI binding + cache + recovery + error-mapping are identical under both models; only "where the engine lives" changes, and the IPC verb contract is the stable seam.

### 6.3 Still open (decide before / within Phase 1)

- **Sync-root identity / packaging:** full Explorer integration (registration via `StorageProviderSyncRootManager`, overlays) needs a **package identity** → MSIX or a sparse package. Decide MSIX vs unpackaged + `CfRegisterSyncRoot`-only (lesser UX) early — it shapes packaging and the `%APPDATA%` virtualization concern (the MSIX sandbox virtualizes `%APPDATA%`; socket/config/cache locations must account for it).
- **Daemon-on-Windows lifecycle:** this dictates the *shape* of the client — (i) the client process **is** the Windows Service and supervises the JVM daemon; (ii) a tray/login app launches a separate daemon Service; or (iii) the client is a strict IPC consumer that expects the daemon already running (auto-spawn-on-connect, #142 — Medium priority). **Leaning (i)** (one Windows Service owns the daemon lifecycle + the CfAPI binding). Must be **firmed up in Phase 0, before Phase 1** — it determines the client's process model.
- **v1 shell scope:** placeholders only, or overlays + context menu + "free up space" in v1?

---

## 7. Risks & Windows gotchas

- **Orphaned placeholders (#237)** — P0; designed for in §4. Biggest hazard; build the recovery command *first*.
- **Sparse placeholders only** — never `setLength`/pre-allocate. The engine's own history (UD-222 / UD-712): a `setLength(size)` "sparse" stub fully allocated NUL bytes on NTFS → a 346 GB OneDrive became 346 GB of zeros. Use CfAPI's real sparse on-demand placeholder; the engine model is a 0-byte stub + `isHydrated=false`.
- **AF_UNIX socket in `%TEMP%`, not `%LOCALAPPDATA%`** (sockets fail there) — already handled daemon-side; the client must match.
- **ACL / elevation** — use `CheckTokenMembership`, not `EqualSid` vs `TokenUser` (elevated processes stamp `BUILTIN\Administrators` as owner; `EqualSid` silently fails). Relevant to sync-root SDDL / placeholder ACL checks.
- **Invalid NTFS names (#230)** — all-dots, trailing dot/space, reserved device names, `<>:"/\|?*`, control chars. Engine quarantines them (`downloadQuarantined`); the client surfaces, never retries.
- **NFC** — the client sends UTF-16/NFC; the daemon's `pluckPath()` normalizes (idempotent).
- **Don't resurrect `protocol/` or a named-pipe transport** (ADR-0011/0012) — reuse the UDS NDJSON IPC; prefer code-co-located kotlinx-serialization DTOs if a typed contract is wanted.
- **Test hygiene (#239)** — fix the Linux-path test assumptions (`MountCommandTest`/`SyncEngineTest` assert `/run/user/...` + forward-slash cache paths) before a Windows CI runner can gate; CI grows lazily once the tier has code (`multi-platform.md`).
- **Windows version floor** — CfAPI exists since Win10 1709, but `StorageProviderSyncRootManager` capabilities + the shell-integration surface vary across builds. **Target Windows 11 22H2+** as the supported floor; document graceful degradation on older builds (e.g. drop full Explorer status-UI, keep basic placeholders) rather than chasing 1709 parity.

---

## 8. Phases (each independently demoable)

- **Phase 0 — De-risk & decide.** Pick packaging (§6.3) and the daemon-on-Windows lifecycle. **First deliverable: the orphan-recovery command + clean up the existing four orphans** (so the dev loop is safe). Fix #239. Stand up a C# project skeleton + the AF_UNIX IPC client (round-trips `daemon.status`).
- **Phase 1 — Read-only mount.** Register sync root, seed placeholders (`hydration.list`), hydrate-on-read (`FETCH_DATA`→`open_read`→serve from `cache_path`), dehydrate, `view.invalidated` refresh, and **robust `CfUnregisterSyncRoot` + `CfRevertPlaceholder` teardown**. *Demo: browse + open a large cloud-only account as placeholders.*
- **Phase 2 — Writeback.** create / write / rename / delete / mkdir + the `recovery-` crash scanner (per-path upload serialization is already engine-side). *Demo: edit/save/delete round-trips to cloud on both providers.*
- **Phase 3 — Shell UX.** pin/unpin (wire the engine pin rules), status overlays (`IStorageProviderStatusUISource*`), context menu, progress, "free up space".
- **Phase 4 — Packaging & lifecycle.** MSIX/installer, Windows Service / auto-start + daemon supervision, Event Log/ETW logging, uninstall teardown; bring the Windows CI runner online.

Cross-cutting: the tracking-set engine (#99) draws the engine/client module boundary the ADR references — sequence the eventual embed (§6.2-B) with it.

---

## 9. Strategic gate

Per `multi-platform.md`, this is the *Planned* Windows desktop tier and is gated on a funded effort with a committed maintainer (re-opening criterion: a non-Linux platform that someone owns). It is post-Linux-MVP. The phasing is structured so **Phase 0 + Phase 1 form a contained spike** that de-risks the two scariest parts (teardown, and proving CfAPI hydration against the real engine) before committing to the full tier.
