# Manual Test Checklist (per-release smoke)

> Salvage of the pre-greenfield `docs/TEST-CHECKLIST.md`, trimmed to
> the cases [`scripts/smoke.sh`](../../scripts/smoke.sh) does NOT
> already cover. If smoke.sh covers it, the automated path is
> authoritative — this doc starts where it ends.

**Scope:** v0.0.x MVP (`localfs + s3 + sftp` gated; others preview).
Pre-release sweep before tagging.

**Time budget:** ~30 minutes if everything passes. The non-localfs
items need real credentials; `assumeTrue`-skip cleanly if you have
none.

## What smoke.sh already covers

`scripts/smoke.sh` exits 0 when:

1. `:app:cli:shadowJar` + `:app:mcp:shadowJar` build cleanly.
2. `cli profile create` lays down a `localfs` profile.
3. `cli -p <profile> sync` completes against a seeded `$SYNC_ROOT`.
4. MCP `initialize` + `tools/list` + `tools/call ls` returns a
   well-formed `id=3` response.
5. `scripts/backlog-sync.kts` lints cleanly.

Run it first:

```
./scripts/smoke.sh
```

The checklist below picks up where smoke.sh stops.

---

## 1. Provider configuration

- [ ] `unidrive profile create <name> --provider <p>` for each in
      scope (`localfs`, `s3`, `sftp`). Verify `config.toml` rewrite
      preserves comments and other profiles.
- [ ] `unidrive profile list` enumerates all configured profiles.
- [ ] `unidrive profile remove <name>` cleanly drops the section,
      leaves siblings intact, deletes per-profile state DB.
- [ ] `unidrive identity` (MCP-only tool) returns the active
      `(profile, provider)` tuple.

## 2. Initial sync (full cold start)

- [ ] Seed a fresh `$SYNC_ROOT` with ≥10 files + nested dirs.
- [ ] First `unidrive sync` round-trips: local → remote → DB row
      per item, `isHydrated=true` for everything actually uploaded.
- [ ] Re-running `unidrive sync` immediately is a no-op (zero
      uploads, zero downloads, "Scan ended" banner shows
      `reason=MANUAL` or appropriate).

## 3. Delta sync (warm)

- [ ] Touch one file's mtime/size locally → next `sync` uploads it
      and nothing else.
- [ ] Add a remote-side file (web UI, `aws s3 cp`, etc.) → next
      `sync` downloads it and nothing else.
- [ ] Rename a local file → engine collapses to `MoveRemote` (not
      `Delete + Upload`); verify via the sync log.
- [ ] Delete a local file → remote deletion happens, then DB row is
      cleared.

## 4. Watch mode (`--watch`)

- [ ] `unidrive sync --watch` enters the poll loop without burning
      100% CPU. `top` should show idle.
- [ ] Touch a file → adaptive interval collapses to `min`; the next
      cycle picks up the change.
- [ ] Leave idle for several minutes → `poll_state` events show
      `IDLE`, interval at max.
- [ ] `ctrl-C` shuts down cleanly: no orphan socket file in
      `/run/user/$UID/`, no orphan lock.

## 5. Conflict resolution

- [ ] Edit the same file both sides between syncs → with default
      `keep_both`, expect remote copy saved as
      `file.conflict-remote-<TS>.ext`, local copy unchanged.
- [ ] `unidrive conflicts` lists the conflict.
- [ ] Toggle `conflict_policy = "last_writer_wins"` and repeat →
      most-recent-mtime wins, the other side is overwritten.

## 6. Pause / resume (interrupt)

- [ ] Start a sync of a large file set; `kill -TERM` mid-transfer.
- [ ] Restart `unidrive sync`. Engine resumes from the delta cursor
      in `sync_state`; no duplicate uploads, no half-uploaded files
      left over.
- [ ] Pending-actions count from the resumed pass matches what was
      outstanding pre-interrupt (modulo any new local changes).

## 7. Error recovery

- [ ] Yank the network mid-sync (`iptables` drop or unplug). Engine
      surfaces `sync_error` IPC event, daemon stays up.
- [ ] Restore network. Next poll cycle completes successfully
      (`reason=RESCAN_AFTER_ERROR` until the loop settles).
- [ ] Disk-full simulation: fill the `$SYNC_ROOT` partition. Engine
      should surface a clear error message, not panic, not corrupt
      the DB.

## 8. Deletion safeguard

- [ ] Delete >50% of files locally. Next sync **must abort** with
      the `max_delete_percentage` guard. ✅ covered by
      `integration-test.sh` Section 3 but worth a manual verify when
      the threshold is overridden.
- [ ] `unidrive sync --force-delete` bypasses the guard.

## 9. Trash & versioning

- [ ] Delete a synced file. `unidrive trash list` shows it.
- [ ] `unidrive trash restore <path>` brings it back; next sync
      re-uploads (or no-op if the remote still has it).
- [ ] `unidrive versions list <path>` enumerates server-side
      versions (provider-dependent; OneDrive has them, localfs
      does not).

## 10. Logout & token rotation

- [ ] `unidrive logout` for the active OAuth provider clears the
      token file (`~/.config/unidrive/tokens/<provider>.json`) at
      POSIX `0600` before deletion.
- [ ] Next sync fails with a clear "not authenticated" message,
      not a stack trace.
- [ ] Re-login via `unidrive auth begin / auth complete` (or MCP
      equivalents) restores access.

## 11. Profile switching (MCP)

- [ ] Run two MCP servers concurrently with `--profile A` and
      `--profile B`. Each binds to a different UDS socket and a
      different state DB.
- [ ] Tool calls in process A don't bleed into process B's state.
- [ ] Stop process A; process B continues unaffected.

## 12. OneDrive-specific (preview, needs tenant)

These need a real Microsoft tenant + an OAuth token. `assumeTrue`-
skip if absent.

- [ ] `unidrive sync` against OneDrive completes a full first pass.
- [ ] Personal Vault is skipped with the documented INFO log on
      first encounter (UD-315).
- [ ] Share-link round-trip: `unidrive share <path> --expires <ts>`
      returns a URL that resolves anonymously until expiry.
- [ ] Webhook subscription path (out-of-tree): with a public tunnel
      + `webhook = true` in config, observe one `createSubscription`
      at sync start and **no further Graph subscription traffic
      until ~24h before the 3-day expiry** (UD-303 scheduler).
      See [`webhooks-nat-setup.md`](webhooks-nat-setup.md).

## 13. IPC consumer roundtrip

- [ ] With a sync in progress, `unidrive log --watch` shows the
      live frame sequence: `sync_started → scan_progress* →
      reconcile_progress* → action_count → action_progress* →
      sync_complete`.
- [ ] Connect a second consumer mid-sync. It receives the
      state-dump replay first, then joins the live stream.
      See [`ipc-protocol.md`](ipc-protocol.md) for the wire format.

## 14. ProcessLock & build cleanliness (pre-tag gate)

- [ ] Start a daemon, then a second daemon for the same profile.
      Second refuses to start citing the lock; no racey double-write.

- [ ] `( cd core && ./gradlew build )` is green: tests + ktlint +
      jacoco all pass.
- [ ] `scripts/dev/ktlint-sync.sh` reports no drift in baselines.
- [ ] `scripts/check-version-drift.sh` reports no drift between
      `gradle.properties`, the CLI `--version` output, and the
      CHANGELOG `[Unreleased]` heading.

---

## Items deferred to `unidrive-closed`

The pre-greenfield checklist included:

- `BenchmarkCommand` / `provider benchmark` CLI runs.
- Backup-wizard end-to-end.
- Vault setup / unlock round-trip.

These now live in the closed-source repo. If you have a
`unidrive-closed` build, run its own per-release checklist
alongside this one. Otherwise skip — they are not gating for the
public MVP.

## See also

- [`scripts/smoke.sh`](../../scripts/smoke.sh) — the automated
  starting point; this checklist begins where it ends.
- [SPECS.md §7 Test strategy](../SPECS.md#7-test-strategy--current-reality) — automated coverage status.
- [`ipc-protocol.md`](ipc-protocol.md) — for the IPC consumer tests.
- [`webhooks-nat-setup.md`](webhooks-nat-setup.md) — for the
  OneDrive-tenant webhook tests.
