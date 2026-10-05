# Lane: engine quick wins (gkrost/unidrive) — for a Sonnet-class agent

Prepared 2026-10-05 against `main` `0d239bf`. Small, low-risk, no design decision; verified against the code. Same rules as
`docs/lanes/lane-engine-bugs.md`: claim on the issue, own worktree off `origin/main`, one draft PR per item (Q4 and Q5 may share one), owner
merges, `cd core && ./gradlew check` green, hands off the #560/#578 areas, no live stack, public repo.

## Q1 — #394 `ls --live` lists trashed and removed Internxt children
- **Evidence:**
  - `InternxtProvider.kt:186-190` returns all children; the converters mark tombstones `deleted = true` (`:2657-2661`, `:2700`, `:2727`).
  - `LsCommand.kt:76-77` prints everything and never looks at `deleted`.
  - The gather already filters them (`SyncEngine.kt:2925`).
- **Fix:** `LsCommand`: `children.filterNot { it.deleted }`. Provider-neutral; do not change `InternxtProvider`, which `CloudRelocator` and
  `VerifyCommand` also call.
- **Test:** a fake provider returns one live and one `deleted` item; only the live one is printed. **Size:** S.

## Q2 — #202 (CLI half) The mount diagnostic says "daemon not running" while the socket exists
- **Evidence:** `MountCommand.kt:78-81` prints "the daemon ... is not running" on any non-zero co-daemon exit.
- **Fix:** if the socket file exists, say "the daemon socket exists but refused the connection (still starting, or a stale socket); retry,
  or check `unidrive -p X daemon status`". Extract the message builder into an internal function.
- **Out of scope:** the Rust co-daemon's retry (unidrive-mount-linux).
- **Test:** unit-test the message with the socket present and absent. **Size:** S.

## Q3 — #395 residuals: NFC-normalise `sync_path` roots; show the scope in `status --all`
- **Evidence:**
  - `SyncScope.normalizePath` (`core/app/engine-core/.../SyncScope.kt:14-21`) only fixes slashes, so an NFD-typed root never matches the
    NFC keys (#171);
  - `printScopeLine` is called only from the single-profile view (`StatusCommand.kt:357`), not from `--all` (`:156`, per the issue).
- **Fix:** apply `PathNormalizer.nfc` in `normalizePath` (same module); add a scope column or suffix to `--all`.
- **Test:** in `SyncScopeTest`, an NFD `/Ä` normalises to NFC; a status test shows that `--all` prints the scope.
- **Claim on #560 first** (engine-core is the split's module). On the issue, tick the two boxes that are already done (the `--full-tree`
  refusal and the silent 0-row narrowing). **Size:** S/M.

## Q4 — #412 residual docs drift
- **Fix:**
  - `OneDriveProvider.kt:196` cites the non-existent `docs/SPECS.md §3.1`: point it at the real spec, or drop it;
  - `scripts/dev/pre-commit/scope-check.sh:111,130` and `.claude/skills/unidrive-log-anomalies/SKILL.md:37-38` point at
    `docs/backlog/BACKLOG.md`: the backlog is at the repo root (`BACKLOG.md`);
  - `BACKLOG.md` still lists closed issues #136, #148, #149, #157, #163, #166 (and #152 once closed): move them to `CLOSED.md`;
  - `AGENTS.md` module list lacks `engine-core`.
- **Test:** none; `check` stays green. **Size:** S. **Order:** after the bug lane's B5 if it is running (`OneDriveProvider.kt`).

## Q5 — #326 Secret scanning: dead CI reference and dead allowlist paths
- **Evidence:**
  - `scripts/hooks/pre-push:4-6` names a CI scan `scripts/ci/gitleaks.sh` that does not exist (no `scripts/ci/`, no workflow mentions
    gitleaks);
  - `.gitleaks.toml:31,34` allowlist two paths that are gone (`core/docker/docker-compose.integration.yml`, `core/providers/sftp/...`).
- **Fix:** correct the hook comment and prune the dead allowlist entries. An advisory gitleaks CI job is optional and the owner's call:
  propose it in the PR, do not add it.
- **Test:** `gitleaks detect --config .gitleaks.toml` if available. **Size:** S.

## Q6 — #305 `dist/install.sh` overwrites the jar in place and never checks Java
- **Evidence:** `dist/install.sh:73` copies over the installed jar in place; there is no Java check and no running-unit check (only
  `daemon-reload`, `:111-112`).
- **Fix:**
  - `rm -f` before `cp` (new inode, as `scripts/dev/redeploy-local.sh` does);
  - require Java 21 or later;
  - if `systemctl --user is-active unidrive.service`, stop the unit first or refuse with a hint. The owner reviews which.
- **Test:** `shellcheck`; a dry run with a temp `HOME`: install twice and the inode changes. **Size:** S.

## Q7 — #494 part 1: a connection beyond `MAX_CLIENTS` is closed silently
- **Evidence:** `IpcServer.kt:259-262` logs and closes the socket without a reply.
- **Fix:** before closing, write one line `{"ok":false,"error":"max_clients"}` (blocking, in `runCatching`).
- **Test:** open `MAX_CLIENTS + 1` connections; the last reads the error line before EOF. **Size:** S.
- **Claim first:** it adds an error token to the IPC contract. Add it to the corpus or `docs/dev/ipc-protocol.md` and ask on the issue
  before starting. Raising the cap is out of scope.

## Deliberately not here
- The small #579 audit items (the wrong "pending UD-307" comment at `InternxtProvider.kt:566`, the dead `putEncryptedShard(ByteArray)`, the
  reply-write failure at `IpcServer.kt:451`) sit in the #578 upload path or need behaviour decisions.
