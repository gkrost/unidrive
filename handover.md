# Session handover — 2026-05-02 / 2026-05-03

Long session, focused on a fix-stack for the **UD-901-pending-row family of
invariant violations** that surfaced when the operator tried to do a real
upload of ~68k local files against an Internxt drive that had ~107k legacy
DB rows. Plus a JFR-driven MDC allocation finding (UD-254a) and the live
hang fix (UD-406).

## Repo state at session end

```
dev (2baafeb)                         18 commits ahead of origin/dev
                                       all redacted via filter-branch
dev-pre-redaction-2026-05-03 (3224c48) safety branch — pre-redaction snapshot
fix/UD-254a-mdc-clone-storm (a72d7c7)  4 commits, includes the chunk doc
                                       UNMERGED — see "What didn't land"
experiment/log-troughput (7278606)     1 commit, scratch script (per
                                       earlier user decision: keep local)
```

The freshly-deployed jar at
`C:\Users\gerno\AppData\Local\unidrive\unidrive-0.0.0-greenfield.jar`
(timestamp ~10:50) carries every dev-merged fix below.

## What landed on `dev` this session (in order)

| commit | UD | summary |
|---|---|---|
| 9e84b1a | UD-405 | normalise `--sync-path` at CLI boundary (Windows backslash silent scope drop) |
| fb4735c | UD-901a | UD-225 + UD-901 recovery loops respect `syncPath` |
| be95469 | UD-901b | recovery synthesises `CreateRemoteFolder` chain for orphan upload ancestors |
| 319e4dc | UD-357 | Internxt `FolderUuidCache` masks read-after-write window between `POST /drive/folders` and the next listing |
| 915bdb1 | UD-901c | `renamePrefix` DELETE-then-UPDATE inside `batch{}` to tolerate pending rows at destination |
| 32c186f | UD-406 | close `IpcServer` after one-shot sync so `runBlocking` returns |
| bc5ffa1 | UD-240j | `matchedFolderCreates` dedup in `Reconciler.detectMoves` folder-move loop |
| 2baafeb | UD-240k | folder-move picks best source by parent-prefix overlap; ambiguous-zero-score guard |

Also on dev (pre-existing fixes from earlier in the session, redacted but
otherwise unchanged): UD-240g, UD-240i, UD-774, plus filed-but-unfixed
tickets UD-254a, UD-772, UD-773, UD-240h, the silent-phases lesson,
docs/handover/subagent-chunks-2026-05-02-mdc.md.

## What didn't land — pending merges / decisions

### `fix/UD-254a-mdc-clone-storm` — unmerged

Two commits:
- `26164ae` fix(UD-254a): drop `MDCContext()` from `runBlocking` in
  `SyncCommand.kt:357`. JFR-confirmed the root of 80.63 % allocation
  pressure during upload-only syncs. Behavioural trade documented in
  the commit message: worker log lines lose `[profile]` / `[scan]` MDC
  interpolation.
- `a72d7c7` docs(handover): subagent chunk for UD-254a Slice B + UD-753
  (in `docs/dev/handover/subagent-chunks-2026-05-02-mdc.md`).

To land: rebase onto current `dev` (trivial — touches only
`SyncCommand.kt:357` and a doc file in handover/). The chunk file
inside that branch is the agent-handoff for the wider id-in-message
migration on the apply path.

### `experiment/log-troughput` — keep as local scratch

User's earlier instruction: "we leave experiment/log-troughput as local
branch." Contains `scripts/dev/log-throughput.py` — useful for
post-mortem analysis of `unidrive*.log` files, distinguishes it from
the pre-existing `log-stats.py` (which the user filed as broken on
Windows under cp1252; that's UD-772, also unfixed).

## Tickets filed this session, not yet fixed

Live in `docs/backlog/BACKLOG.md` on dev. None have been moved to
`CLOSED.md` (per project convention, that happens on `backlog.py
close UD-XXX --commit <sha>` after the user verifies the deployed
fix end-to-end on real workload — not just the unit tests).

| UD-### | category | summary |
|---|---|---|
| UD-254a | core | MDC clone storm (fix branch exists; see above) |
| UD-772 | tooling | `log-stats.py` Windows cp1252 crash on `→` literal |
| UD-773 | tooling | `RequestId` close lines lack content-length, blocks bytes/sec metrics |
| UD-240h | core | LocalScanner UD-901 pending-row writes are 67k single-row UPSERTs (wrap in `db.batch{}`) |

## Verification still owed by the operator

The deployed jar fixes a fix-stack that the operator hadn't yet end-to-
end verified on their full workload. Specifically:

1. **`unidrive sync --upload-only`** (no `--sync-path`) against the full
   ~68k local tree should now:
   - Reconcile in single-digit seconds (UD-240g + UD-240i)
   - Not stall indefinitely after `Sync complete:` (UD-406)
   - Not emit `move /Sample → /userhome/.../_Photos/Sample` for the
     legacy stale row (UD-240k picks the close-relative source instead)
   - Tolerate the existing PK-collision orphan rows (UD-901c)
2. **The legacy stale `/Sample`-style top-level row** in the operator's
   DB will produce a standalone `DeleteRemote(/Sample)` that fails
   gracefully with 404 (handled by the existing skip-on-not-found in
   `applyDeleteRemote`).

If a NEW failure mode surfaces: live state.db is at
`C:\Users\gerno\AppData\Roaming\unidrive\inxt_user\state.db`; backup
from this session is `state.db.before-resync-2026-05-03_1026.bak` in
the same dir. failures.jsonl in same dir has 7000+ historical
entries.

## Known unsolved issue — DB persistence mystery (`feedback_db_persistence`)

During this session the operator's `state.db` showed mtime stuck at
**Apr 30 19:39** even though syncs at 10:23 and 10:51 today
demonstrably ran reconcile against a populated DB. Either:

- LocalScanner's UD-901 upserts aren't reaching that file (going to a
  different state.db path? a virtualised cache?)
- Or filter-branch / SQLite's WAL got into a non-standard state at
  process kill
- Or the daemon was using the LOCALAPPDATA-rooted dataDir's state.db
  instead of the APPDATA-rooted one

Neither is fully diagnosed. The operator's data is safe; they have a
forensic backup. Worth investigating in a future session if the
re-sync produces unexpected behaviour again. The candidate-tickets
to file:

- *Bug F* — DB write loss / wrong-path: starting point would be a
  `lsof`/`Process Explorer` snapshot of the daemon while it's running
  to confirm which state.db file descriptor is open.

## Adjacent improvements still on the table

- **detectMoves file-move side** still uses size-only matching. The
  user observed false-positive moves matching JPEGs against legacy
  `/dev/zvg/<HASH>` blobs. UD-240k's parent-prefix scoring should be
  applied to the file-move loop too — call it UD-240l. Ticket not
  filed yet.
- **The Internxt `FolderUuidCache` (UD-357)** never invalidates. A
  folder created via the cache then deleted out-of-band returns 404 on
  next op. Acceptable for in-process scope; if it bites in practice,
  add 404-driven invalidation.

## Privacy posture

The 18 redacted commits on `dev` no longer carry the operator-specific
strings (folder names, file titles, the operator's name in test
fixtures, etc.). Pre-existing private terms in `LICENSE`/`NOTICE`/
`README.md`/`CLOSED.md` are intentional author info and were left
alone. Two pre-existing test files inherited from `origin/dev` still
carry private terms (`SyncConfigStrictTest.kt`, `CliProgressReporterTest.kt`)
— flagged but not redacted; out of branch scope.

If the operator wants to remove the safety net before push:

```bash
git update-ref -d refs/original/refs/heads/dev   # filter-branch's auto-backup
git branch -D dev-pre-redaction-2026-05-03       # session safety branch
```

## Lessons added this session

- `docs/dev/lessons/silent-phases-look-like-hangs.md` (UD-240g
  motivating story — bookend log + reporter heartbeat is a contract,
  not a nice-to-have).

Plus the new lesson committed alongside this handover (see
`docs/dev/lessons/pending-row-recovery-invariants.md` for the
UD-901-family pattern).

## Subagent handoff for the next chunk of work

`docs/dev/handover/subagent-chunks-2026-05-02-mdc.md` (on the
unmerged `fix/UD-254a-mdc-clone-storm` branch) packages **UD-254a
Slice B + UD-753** for delegation. ~3-4 hours of mechanical work,
fully agent-able. Land the parent branch first, then run the
chunk's prompt against it.
