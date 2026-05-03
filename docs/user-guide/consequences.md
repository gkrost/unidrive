# What each verb actually does

> Deliverable for [UD-220](../backlog/CLOSED.md#ud-220).
> Intended audience: a thoughtful operator about to press enter, who
> wants to know exactly what will change, what can be cancelled, and
> what can be undone.

Terminology used throughout:
- **local** — the files under the configured `sync_root`.
- **remote** — the provider (OneDrive, WebDAV, S3, …).
- **state.db** — `%LOCALAPPDATA%\unidrive\<profile>\state.db` (Windows)
  or `~/.local/state/unidrive/<profile>/state.db` (Linux). Holds the
  delta cursor, per-path metadata, and conflict/version indexes.

Each section answers the same four questions: **mutates**,
**cancel/abort**, **rollback**, **remote side-effects**.

---

## relocate

`unidrive relocate --from <src> --to <dst> [--source-path X] [--target-path Y] [--delete-source]`

Migrates a subtree from one provider to another.

**Mutates:**
- **remote (target)**: every file under `--source-path` is uploaded to
  `--target-path`. Missing intermediate folders are created on demand.
- **local**: temp files only. Each source file is streamed through
  `%TEMP%\unidrive-relocate-<random>\` then deleted. No local `sync_root`
  is touched on either side.
- **state.db**: not touched. `relocate` is a direct cloud-to-cloud
  transfer; neither provider's delta cursor moves.
- **remote (source)**: untouched *unless* `--delete-source` is passed,
  in which case see below.

**Atomicity:** none. Each file is a separate download + upload; a
crash, CTRL-C, or network drop half-way leaves however many files
have already been uploaded sitting on the target. The pre-flight
summary box shows the total; the progress line shows running totals.

**Cancel/abort:** CTRL-C stops the loop at the next file boundary.
Files already uploaded to the target stay there. The temp directory
is best-effort cleaned on exit (`Files.deleteIfExists`) but a hard
kill can leave stragglers under `%TEMP%`.

**Rollback:** none built in. To undo: `unidrive -p <target> rm` each
file that was transferred, or delete the target subtree from the
provider's own UI. There is no transaction log — the only record of
what was uploaded is the target side itself.

**`--delete-source`:** a second pass after the migration, prompts for
literal `yes` on stdin, then deletes each migrated file from the
source. Files are deleted individually; folders are deleted
bottom-up and "not empty" errors are swallowed (leftover unmigrated
children stay put). **This does not make the whole operation
atomic** — if the confirmation is given and the delete pass fails
halfway, some source files are gone and some aren't.

**Re-download warning:** relocate reads from the source provider's
API, *not* from any locally hydrated copy. If you have 300 GB locally
hydrated from a OneDrive profile, `relocate --from onedrive --to nas`
will re-download all 300 GB from Microsoft. Workaround: point a
`localfs` profile at the hydrated directory and use that as `--from`
— no cloud round-trip on the source side.

---

## sync

`unidrive -p <profile> sync [--dry-run]`

Runs one three-phase cycle: **refresh** (pull remote delta), **plan**
(compare against local), **apply** (execute actions).

**Mutates:**
- **local**: files added/modified/deleted under `sync_root` to match
  the remote delta. `.unidrive-trash/` receives anything that was
  present locally but missing remotely (safe-delete quarantine).
- **remote**: files added/modified/deleted to reflect local changes
  observed since the last cycle.
- **state.db**: delta cursor advances; per-path metadata rows
  (size/mtime/etag/hash) are updated for every observed path;
  `conflict_log` gains rows when concurrent edits are detected.

**Atomicity:** per-path, not per-cycle. Each apply step either
succeeds and updates its row, or fails and leaves the row unchanged
so the next cycle re-plans it. Nothing is transactional across
multiple paths.

**Cancel/abort:** CTRL-C is checked between phases. Mid-download
or mid-upload operations are abandoned (the cloud call raises
`CancellationException`); the state row is not touched, so the next
cycle reconsiders. A kill -9 during the apply phase leaves the same
situation — state.db is written only after a successful per-path
action, never speculatively.

**Rollback:** `unidrive trash list` + `unidrive trash restore <path>`
reverts a local deletion that was triggered by a remote-side delete.
Remote-side changes that sync pushed up are undone via the provider's
own versioning — OneDrive, Nextcloud, Dropbox all keep server-side
version history. unidrive does not locally stage a pre-sync snapshot.

**`--dry-run`:** emits the planned action list and exits without
mutating anything. state.db is not touched. Useful for verifying
unexpected deletes before committing.

**`--upload-only`** (UD-737): suppresses every action that would
mutate local state. The engine still pulls the remote delta (so the
state.db cursor advances and the next cycle plans against fresh
remote metadata), but **DeleteRemote is also dropped by default** —
upload-only is push-additive: new local files go up, modified local
files overwrite, but a locally-deleted file does NOT delete on
remote. Pass `--propagate-deletes` to opt back in to remote deletion.
The asymmetric default exists because `--upload-only` is most often
used in "back up new files only" scenarios where local deletes are
expected to be intentional and unrelated to the remote.

**`--download-only`:** the inverse — suppresses every action that
would mutate the remote (Upload, DeleteRemote, CreateRemoteFolder,
MoveRemote). **DeleteLocal IS still emitted** when the remote shows
a file as deleted: download-only's contract is "mirror remote state
locally", not "never touch local files". A remote-side delete (via
the provider's web UI, another client, or a TRASHED status flag in
the next delta) propagates to a local delete here. If the local
file matters, pin it (`unidrive pin add <path>`) before running
download-only against a scope where remote deletions are possible —
pinned paths are skipped by DeleteLocal. There is no symmetric
`--no-propagate-deletes` flag yet (UD-737-equivalent for the
download direction is not implemented; if you need additive-only
download semantics, use `--dry-run` first to inspect the plan).

**`--sync-path=<remote-path>`:** restricts both gather and apply to
a remote subtree. Only delta items inside the path are reconciled;
DB entries outside the path are not considered (UD-901a). The
provider-level gather is NOT yet narrowed (UD-362, open) — the
delta still pulls the entire drive's listing and the engine
filters post-delta. Watch wall-clock on large drives.

---

## auth

`unidrive -p <profile> auth`

Initiates the provider's authentication flow (OAuth PKCE / device
code for OneDrive; username/password for WebDAV, SFTP; env vars for
S3; rclone config for rclone).

**Mutates:**
- **local**: writes the provider's token cache under
  `<config-dir>/<profile>/` — e.g. `token.json` for OneDrive, nothing
  on disk for WebDAV (credentials come from the profile config
  directly), keyring entries on Linux where supported.
- **state.db**: not touched.
- **remote**: OAuth flows may create a new refresh-token grant on
  the provider side, observable in the Microsoft account security
  audit log. No user-data change.

**Cancel/abort:** CTRL-C during the browser wait aborts cleanly. No
partial token is written unless the provider callback fires before
the cancel — in which case the token file exists but `auth` can be
re-run safely.

**Rollback:** `unidrive -p <profile> logout` (see below) deletes the
local token. Provider-side the grant persists until the user revokes
it via the provider's security dashboard.

---

## logout

`unidrive -p <profile> logout`

Removes local authentication material for a profile.

**Mutates:**
- **local**: deletes the token cache under `<config-dir>/<profile>/`.
  WebDAV / SFTP / S3 profiles have nothing to delete — the verb is a
  no-op (still returns 0).
- **state.db**: not touched. sync metadata and delta cursors survive
  logout so a subsequent `auth` + `sync` resumes without a full
  re-scan.
- **remote**: nothing. The refresh-token grant is **not** revoked on
  the provider side. To revoke it fully: the user must visit the
  provider's security dashboard.

**Cancel/abort:** N/A (synchronous file delete).

**Rollback:** re-run `auth`.

---

## trash

`unidrive -p <profile> trash list | restore <path> | purge [--older-than DAYS]`

Operates on the safe-delete quarantine under `<sync_root>/.unidrive-trash/`.

**Mutates (list):** nothing.

**Mutates (restore):** moves a file from `.unidrive-trash/` back to
its original path. Next `sync` will re-upload it to the remote (the
remote copy was deleted when the file entered trash).

**Mutates (purge):** permanently deletes trash entries older than the
configured retention window (default 30 days). Irreversible.

**Rollback (purge):** none — purge is a hard delete. If the file was
also present on the remote at some point, the provider's server-side
trash / version history may still have it; unidrive does not check.

---

## versions

`unidrive -p <profile> versions list <path> | restore <path> <version>`

Interacts with the provider's server-side version history when
available (OneDrive, Nextcloud, Dropbox, SharePoint).

**Mutates (list):** nothing.

**Mutates (restore):** issues the provider's "restore this version"
call. Remote is mutated; local is not touched directly — the change
arrives at the next `sync`.

**Rollback:** restore a newer version (they stay in history). If the
version was purged server-side by a provider retention policy, it's
gone.

---

## pin

`unidrive -p <profile> pin <path> | unpin <path>`

Toggles the "keep hydrated" flag on a path. `pin` forces the file to
stay materialised on local disk regardless of cache eviction; `unpin`
allows it to be de-hydrated back to a placeholder.

**Mutates:**
- **local**: the file is downloaded (pin) or removed (unpin) via
  ordinary file I/O. The placeholder/dehydration semantics that the
  retired Windows CF-API tier provided are out of MVP scope per
  [ADR-0011](../adr/0011-shell-win-removal.md); on Linux, unpin
  removes the local copy outright. `state.db` retains the hash + size
  so the next sync recognises the file as legitimately absent rather
  than user-deleted. Files already in the desired state are no-ops.
- **state.db**: `pin_state` column is updated.
- **remote**: nothing.

**Cancel/abort:** CTRL-C during download leaves the file in its
previous state.

**Rollback:** run the inverse verb.

---

## conflicts

`unidrive -p <profile> conflicts list | resolve <path> --keep <local|remote|both>`

Shows paths where sync detected concurrent local + remote edits and
wrote a conflict-copy file.

**Mutates (list):** nothing.

**Mutates (resolve):**
- `--keep local`: deletes the conflict-copy sibling, keeps the
  user-edited version. Next sync will push local up.
- `--keep remote`: replaces the local file with the server copy,
  conflict sibling becomes the "local" version one step back.
  `.unidrive-trash/` gets a backup.
- `--keep both`: renames the conflict sibling to a permanent name
  (`foo (conflict 2026-04-21).txt`) so both survive indefinitely.

**Rollback:** `.unidrive-trash/` contains the displaced version for
the retention window.

---

## Consistency principles

- **state.db is never authoritative for user data** — if it disagrees
  with a file's on-disk state, the next sync re-observes. Wiping
  state.db forces a full re-scan but does not delete anything.
- **Remote-side history is your safety net.** unidrive relies on the
  provider's version history + trash for the deep rollback story
  rather than carrying its own snapshot log. `trash` is the only
  locally-maintained undo, and only for deletes originating in sync
  itself.
- **Only `relocate` and `sync` mutate across the local/remote boundary.**
  Everything else is local-only (`pin`, `trash` list/restore,
  `logout`) or remote-only (`versions` restore, `auth`).
- **Atomicity is per-file, never per-batch.** Plan for interruption.

---

## Cross-reference

- Command flags and usage: `unidrive --help`, `unidrive <verb> --help`.
- Architecture context: [docs/ARCHITECTURE.md](../ARCHITECTURE.md).
- Intent-vs-code audit: [docs/SPECS.md](../SPECS.md).
- WebDAV server robustness: [docs/providers/webdav-robustness.md](../providers/webdav-robustness.md).
