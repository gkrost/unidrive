# Adding a write site means auditing every read site for the new invariants

Learned 2026-05-03, after the **UD-901-pending-row family** turned into
three separable invariant violations (UD-901a, UD-901b, UD-901c) that
took multiple file-and-fix-and-deploy cycles to surface and resolve.

## The pattern

UD-901 (closed) added a single innocent-looking change: `LocalScanner.scan`
now writes a "pending-upload" row to `state.db` for every NEW local file it
sees, so the engine could survive interrupted uploads without losing track
of work in progress. Three lines of `db.upsertEntry(SyncEntry(remoteId =
null, isHydrated = true, …))`.

Three separate invariants were quietly broken by that addition:

1. **UD-901a — scope-filter bypass.** `Reconciler.reconcile` has two
   recovery loops (UD-225 download, UD-901 upload) that iterate
   `db.getAllEntries()`. Both ignored the `syncPath` parameter that the
   main reconcile loop honoured. With pending rows now accumulating from
   prior sessions, a `--sync-path /a/b` invocation surfaced **107k
   actions outside the requested scope** because the recovery loops
   re-emitted every orphan row in the entire profile.

2. **UD-901b — orphan parent-folder gap.** UD-901's recovery emitted
   `Upload` for every pending-upload row, but never the prerequisite
   `CreateRemoteFolder` for the parent tree. When the original sync that
   produced a row was killed before its parent folder reached the
   remote, every retry failed identically with `Folder not found`. **The
   row could never resolve itself** — permanent failure mode that only
   `--reset` could escape.

3. **UD-901c — rename PK collision.** When the user moved a folder
   locally, LocalScanner wrote pending rows at the new location. The
   engine's `applyMoveRemote` then tried `db.renamePrefix(old, new)`,
   whose `UPDATE sync_entries SET path = newPrefix||substr(path,…)`
   collided with SQLite's PK uniqueness because the destination
   already had pending rows. The action threw, the remote-side move
   had already landed, the **DB was left half-moved** — and every
   subsequent sync reproduced the same failure exactly.

Each of these had to be diagnosed separately, from a fresh failure
mode, in production. UD-901's own ticket body had nothing wrong with
it — the fix it shipped was correct in isolation. The bugs were in
**the unstated contracts** the recovery side, the move side, and the
rename SQL had been quietly relying on.

## Why this is worth pinning

A new write to a shared table is rarely "just a write." Every read
site in the system carries an unstated assumption about what shape the
table contains. Adding rows of a *new shape* (here: `remoteId = null`
+ `isHydrated = true` is novel — pre-UD-901 every row had either
`remoteId != null` from successful upload or was synthesised in-flight
and never persisted) violates those assumptions one by one until each
read site is audited.

The UD-901 family wasn't visible at design time because the audit
was never done. The author of UD-901 wrote correct code for the
*write* side; nobody owned the read-side audit.

## Defensive checklist when adding a new write site

Before merging a PR that introduces a new persistent row shape (a new
DB column, a new `remoteId == null`-class state, a new "pending"
flag, etc.), ask:

- [ ] **Recovery loops** — does any code path iterate the table to
      find rows of a particular shape? Does the new shape change
      what they should produce? (UD-225, UD-901 in our case.)
- [ ] **Scope/filter consumers** — does any code apply a scope
      filter when reading? Do those filters reach the new shape's
      consumers? (`syncPath` was honoured by main reconcile, ignored
      by recovery loops — UD-901a.)
- [ ] **Action-emission contracts** — does the new shape require
      emitting a *prerequisite* action (CreateRemoteFolder before
      Upload, etc.)? Is the action-priority sort enough, or does the
      code that emits the *follower* action need to emit the leader
      explicitly? (UD-901b.)
- [ ] **Update / rename / move paths** — does any SQL `UPDATE …
      WHERE path LIKE` exist? Could it collide on PK uniqueness with
      rows of the new shape? Should it be DELETE-then-UPDATE inside
      a transaction? (UD-901c.)
- [ ] **Idempotency on retry** — if the new write fails, then the
      caller retries, will the second attempt see and respect the
      partial state from the first? Or does it re-write blindly and
      cause a different downstream collision?
- [ ] **Cross-process visibility** — if multiple unidrive processes
      can touch the same DB (lock files, multi-profile setups), does
      the new shape interact with any other process's read?

A 5-minute audit at the writer's PR-review time saves hours of
production diagnosis later.

## Pattern for fixing existing-but-not-yet-audited cases

When you find one read-site invariant violated by a write you added,
**search for the others before you ship the fix**. The UD-901 family
revealed itself one bug at a time over the course of a multi-hour
session; every fix shipped was followed by the next bug surfacing in
the next sync. Doing a top-down audit *after the first bug* would
have caught all three at once.

Concrete grep queries that surfaced the UD-901-family on the day:

- `git grep -n "db.getAllEntries\|getEntriesByPrefix"` — every read
  site that walks rows. Audit each: does it filter the new shape
  correctly?
- `git grep -n "renamePrefix\|UPDATE sync_entries\|INSERT.*sync_entries"`
  — every site that mutates paths. Could PK uniqueness collide with
  the new shape?
- `git grep -n "isHydrated\|remoteId == null\|remoteId != null"` —
  every place that branches on the row's shape. Update each branch
  to handle the new pending-state correctly.

The discipline is straightforward; the cost of skipping it is high.

## See also

- `docs/dev/lessons/silent-phases-look-like-hangs.md` — UD-240g
  motivating story; same code-area lesson on a different axis (a
  long phase with no log/heartbeat looks identical to a deadlock).
- `docs/dev/lessons/one-truth-sync-discipline.md` — when a code
  change moves, the docs/tickets/lessons must move with it. The
  UD-901 family is what happens when they don't.
