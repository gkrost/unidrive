# Critical / High fix sequence

Sequenced units-of-work for the genuinely-open Critical- and High-tier bugs, data-risk first. Each unit is a self-contained fix plus a live **test-drive on both test accounts**. The order respects dependencies: stop active data loss, then make the deletion-safe engine trustworthy, then kill the apply-phase 409/ordering storms, then provider-specific correctness, then UX, then carefully unblock the primary profile.

## Already shipped — do NOT re-sequence

Reconciled against `origin/main` + the merged-PR record (the BACKLOGs lag reality):

- FUSE `create` / `mknod` / `fsync` — implemented (JVM `hydration.create` + Rust ops; in CLOSED both repos).
- Mount survives daemon restart (steady-state) — `ReconnectingIpcClient` landed; ops resume after a restart. The residual in-flight-op-during-restart EIO + dev-redeploy guard is filed Low, not here.
- OneDrive mount hang before `mount()` — fixed by backgrounding `recovery-` `open_write` uploads in `HydrationImpl.openForWrite`; OneDrive mounts in ~5s. Mount BACKLOG still lists it only because the fix lives in the JVM repo.
- `setattr` (chmod/chown/utimes + truncate) — implemented and retired to CLOSED.
- Default ignore list: surface (a) LocalScanner/Reconciler and surface (b) the mount upload path (`SyncEngine.uploadFromCache`) are done; only surface (c) remains (see Unit 3).

## Test accounts

The two accounts we can freely create/delete on:

- `internxt_test` — Internxt test account.
- `posteo_onedrive` — OneDrive test account.

The primary Internxt profile is read-mostly: **remote deletions on it are forbidden** and it must never be a destructive test target (see Unit 8).

"Test-drive on both accounts" means run the unit's live scenario on `internxt_test` **and** `posteo_onedrive`, confirm the fix holds and nothing regresses. Provider-specific units (Unit 6) test-drive only on the relevant provider; that is called out per unit.

---

## Unit 1 — Create-collision blind PUT-overwrite (Critical · DATA LOSS)

**Risk / deps:** highest — silently loses data on a normal "upload remotely → edit locally" flow. Independent of everything; the interim provider-level guard can land immediately.

**Scope:** on a 409-create-collision with `remoteId == null`, stop unconditionally calling `replaceFile`. Require an exact size+hash content match to adopt-and-update; otherwise keep-both (conflict copy) or refuse with a typed conflict error. `InternxtProvider.kt` (the create-collision 409 fallback, ~lines 778-787). Cross-provider: OneDrive has the same shape via hardcoded `conflictBehavior="replace"` (`GraphApiService.kt:352` and `:649`), so the no-blind-overwrite invariant must hold on both providers. A `fix/create-collision-keep-both` branch exists (one commit, unmerged) — verify and adopt or redo.

**Test-drive (both accounts):** out-of-band upload a file to the remote (web UI or a second client) → without refreshing the mount, create a file at the same path through the mount with different bytes → assert the pre-existing remote content **survives** (keep-both, or refuse), nothing silently overwritten. Run identically on both providers.

## Unit 2 — Tracking-set cursor-resume forces `exists=true` (Critical · data-risk)

**Risk / deps:** foundational. The tracking-set engine is the dependency for Unit 3, the tracking-set half of Unit 7, and the primary-profile unblock in Unit 8; this bug silently breaks its deletion-safety on incremental (cursor-resumed) passes. Cheap, localized.

**Scope:** on cursor-resume, preserve remote-absence for paths in a pending-delete state (`PendingDeleteLocal` / `PendingDeleteRemote`) instead of synthesizing `exists = true` for every omitted path. `TrackingEngine.kt` (~lines 292-294). Add a named crash-then-resume pending-delete test.

**Test-drive (both accounts):** `ts sync` a file → delete it remotely → force an incremental cursor-resumed pass → assert the local copy is reaped, not masked as still-present. Caveat: Internxt's `/files` listing lags a trash by ≥60 min, so the Internxt arm must cross-check the folder-contents endpoint or tolerate the lag; the OneDrive delta arm is the clean signal.

## Unit 3 — Default-ignore-list surface (c): tracking-set `scanLocal` (High)

**Risk / deps:** finishes the ignore-list trilogy (a and b done). Touches the same file as Unit 2, so batch them.

**Scope:** consult `effectiveExcludePatterns` in the tracking-set `scanLocal` (currently a bare `Files.walk` with no exclude, `TrackingEngine.kt` ~line 312), keep-local semantics (don't upload, don't tombstone).

**Test-drive (both accounts):** `ts sync` a sync_root containing `.directory.lock` + `Thumbs.db` + a real file → assert the junk stays local and only the real file uploads. Both providers.

## Unit 4 — Apply-phase ordering (topo-sort) + XDG locale-aliasing (High · correctness)

**Risk / deps:** both are legacy `SyncEngine` apply-phase failures producing 409 / "parent not found" storms. Correct mkdir-before-move ordering is a prerequisite for clean locale-alias remediation, so sequence them together.

**Scope:** topologically sort the action list by path-depth; treat `MoveRemote` as both producer (destination) and consumer (source) in the dependency graph. Map XDG-user-dir locale aliases (parse `~/.config/user-dirs.dirs` + a static well-known table) to the cloud-canonical folder name so a locale-renamed local folder is not seen as a new folder.

**Test-drive (both accounts):** seed a near-empty cloud → reconcile a 3-level-deep local tree with an internal move, plus a German `Bilder/` against a cloud `Pictures/` → assert apply completes with zero "parent not found" / 409 and no duplicate parallel tree. Both providers.

## Unit 5 — `--fast-bootstrap` adopt-on-match for local folders (High · efficiency)

**Risk / deps:** reuses the adopt-on-name/content-match mechanic settled in Units 1-2.

**Scope:** at fast-bootstrap apply time, adopt-on-name-match for top-level folders (look up the remote by name, register the row, skip the `mkdir`) instead of emitting a `mkdir-remote` that lands as a 409.

**Test-drive (both accounts):** a profile whose top-level local folders already exist on the cloud → `sync --reset --fast-bootstrap` → assert zero 409-Conflicts and N quietly-adopted rows. Both providers.

## Unit 6 — OneDrive correctness cluster (High · OneDrive test-drive only)

**Risk / deps:** Graph-delta / webhook specific — Internxt cannot exercise these, so the test-drive is `posteo_onedrive` only (the Internxt arm is N/A). The sub-items are independent; order by user-visibility:

1. "not in delta, marking deleted" churn loop — a just-uploaded file is declared deleted on the next full-sync pass, then re-uploaded, every cycle. First step: capture one instance with TRACE-level Graph logging and cross-reference the upload request-id timing. `SyncEngine.kt` (the "not in delta, marking deleted" log site).
2. `downloadUrl` refresh on `assertNotHtml` — when the CDN serves an HTML throttle page, re-resolve via `getItemById` instead of failing the download.
3. 410-Gone resync handling; `file.hashes` in the local change detector (`LocalScanner.kt`); `If-Match` precondition on `createUploadSession`; webhook lifecycle events + a validation endpoint.

**Test-drive (posteo_onedrive):** per sub-item — e.g. for (1), upload through the mount, watch the `--watch` loop, assert no "marking deleted" for the live path within N cycles.

## Unit 7 — `status` / `status --all` divergences (High · UX)

**Risk / deps:** the tracking-set-rendering half depends on Units 2-3 (it renders `tracking.db`); the orphan-profile-dir half is standalone.

**Scope:** pick a single source of truth for profile enumeration (FS-scan with `config.toml` as overlay), flag orphan profile dirs explicitly; detect tracking-set profiles (presence of `tracking.db`) and render from it, or at minimum point at `ts status`.

**Test-drive (both accounts):** create an orphan profile dir and a tracking-set-managed profile → assert both `status` views agree and flag correctly. Both providers.

## Unit 8 — Legacy deletion-safeguard traps the primary Internxt profile (High · handle with care)

**Risk / deps:** this is the **primary** Internxt account, where remote deletions are forbidden — it cannot be freely test-driven. The clean resolution is to migrate it onto the now-hardened tracking-set engine (after Units 2-3), or a one-shot operator `--force-delete`, not a test-account drill. The legacy safeguard firing is correct behaviour on a real divergence, not a code bug per se.

**Scope:** mature + validate the tracking-set engine on the test accounts (Units 2-3), then migrate the primary profile with deletions gated. `SyncEngine.kt` (the deletion-safeguard site) is the reference, but the fix is migration, not loosening the guard.

**Test-drive:** validate fully on the test accounts first. No destructive operation on the primary profile without explicit operator go-ahead.

---

## Parallel / non-blocking

- Cut the first `unidrive-mount-linux` release tarball + wire `install.sh` to download and SHA256-verify it. Ship-blocker, no data-risk; gated on the distribution pipeline (`spec/distribution`).
- Reach the 5+5+2 live-integration smoke target (5 OneDrive, 5 Internxt, 2 sync). Test-coverage work, runnable anytime.
