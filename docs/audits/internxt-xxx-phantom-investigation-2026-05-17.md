# UD-376: Internxt `_XXX` phantom path investigation (2026-05-17)

Status: **investigation parked** — root cause identified, regression test
added, structural fix deferred. See "Next steps" below.

## TL;DR

The literal `_XXX` is NOT a sentinel, placeholder, or anonymisation marker
in the codebase — `grep -rn '_XXX' core/` returns zero matches in
production sources. It is a **real remote folder name** the user owns
(the user uses `_INBOX`, `_XXX`, `_*` etc. as organisational sort
prefixes).

The bug is **upstream of `resolveFolder`'s error message**:
`InternxtProvider.buildFolderPath` (core/providers/internxt/
.../InternxtProvider.kt:569-580) silently collapses an unresolvable
parent UUID to the empty string, then `toDeltaCloudItem` constructs
`fullPath = "" + "/" + name = "/$name"`. That means **any item whose
parent folder is not in the same delta page is re-emitted at the root**.

For the user's `_XXX` folder (remote_id `117c8471-7d0e-42cd-af74-6046a776f97c`):
- 2026-05-03: full enumeration walked the tree correctly → row written
  at `/_INBOX/_XXX` with isHydrated=false for its 97 file children.
- 2026-05-16: delta cursor returned the `_XXX` folder but **NOT** its
  parent `_INBOX` (because `_INBOX` had not changed in the cursor
  window). `folderMap[parentUuid]` returned null →
  `buildFolderPath` returned `""` → new row written at `/_XXX`.
- The 97 child rows at `/_INBOX/_XXX/*` remain orphaned with
  isHydrated=false.
- The UD-225 download-recovery loop in `Reconciler.kt:183-204`
  synthesises `DownloadContent` actions for every isHydrated=false row.
- `SyncEngine` calls `resolveFolder("/_INBOX/_XXX/KM Popcorn/...")` →
  walks `/`, `/_INBOX`, then can't find a child folder named `_XXX`
  under `_INBOX` (because `_XXX` actually now sits at root) →
  `ProviderException("Folder not found: $segment in $path")` →
  failures.jsonl gets the literal `_XXX` from `$segment`.

## Scope is much broader than `_XXX`

`SELECT COUNT(DISTINCT remote_id), COUNT(remote_id), COUNT(*) FROM
sync_entries` on the user's state.db: **279,615 rows with non-null
remote_id but only 195,513 distinct remote_ids — ~84,000 duplicates.**

Spot-checked a random duplicate (`0001166e-2b05-4d7d-be54-f8b7bec24590`):
- 2026-05-05: `/19notte78/Pictures/Sandra.A.Penina.Sarah.Photo.Pack.2007.2019/MetArt Network/MetArt_2009-01-10_PRESENTING-SANDRA-SANDRA-A-by-ZLATKO_968dc_high/MetArt_Presenting-Sandra_Sandra-A_high_0093.jpg`
- 2026-05-16: `/MetArt_Presenting-Sandra_Sandra-A_high_0093.jpg`

Same UUID, deep path vs. root path, May 5 vs. May 16 timestamps —
identical pattern to `_XXX`. The path-collapse bug is **systemic** to
this user's profile.

## Investigation steps (what was done)

1. **Literal sentinel check** (Grep `_XXX` across `core/`): zero matches.
   Hypothesis 4 from ticket ruled out.
2. **State.db read-only inspection** (`/tmp/state-readonly.db`, copy of
   user's `state.db`):
   - 98 rows match `path LIKE '%_XXX%'` for the phantom-folder family.
   - 97 are stale `/_INBOX/_XXX/...` from 2026-05-03 (no refresh since).
   - 1 is the moved `/_XXX` folder from 2026-05-16.
   - `/_INBOX/_XXX` and `/_XXX` carry **the same remote_id** `117c8471-…`.
3. **failures.jsonl pattern match**: every `_XXX` failure is the literal
   error string from `InternxtProvider.kt:629` —
   `"Folder not found: $segment in $path"` with `segment="_XXX"`.
   Confirms the literal is the `segment` argument, not injected.
4. **`buildFolderPath` trace**: confirmed line 575 returns `""` for
   `folderMap[uuid] == null`, and lines 649/657 in
   `(File|Folder).toDeltaCloudItem` use that empty parentPath unchanged.
5. **Engine path trace**: `Reconciler.kt:183-204` UD-225 recovery loop
   re-emits stale rows; `SyncEngine` routes through `resolveFolder`
   which walks segments and fails on the missing child.

## What was ruled out

- ✗ Literal sentinel in code (Hypothesis 4).
- ✗ `UD-405 --sync-path` normaliser injecting `_XXX` (`grep XXX
  core/app/cli/.../SyncCommand.kt` is empty).
- ✗ `pathSegments` / URI encoding round-trip producing `_XXX` from some
  legal Unicode input (the segment in failures matches the segment in
  state.db verbatim — no encoding involved).
- ✗ Provider doing concat of a placeholder string (no `_XXX` literal
  anywhere in the provider).

## What remains open (the fix)

The structural bug is in **InternxtProvider's delta path resolution**:
`/folders` listing returns only changed folders since the cursor.
Unchanged ancestors are absent. `buildFolderPath` accommodates by
silently returning `""`, which is **always wrong** for non-root items.

Three candidate fixes (rough order of effort):

1. **Signal incomplete + drop the affected items.** In
   `buildFolderPath`, return `null` when an ancestor is not in
   `folderMap`. In `(File|Folder).toDeltaCloudItem`, propagate the
   null up. In `InternxtProvider.delta()`, drop the item from `items`
   AND set `complete = false` (already wired up — engine skips
   `detectMissingAfterFullSync` and refuses to promote the cursor).
   Risk: every delta becomes incomplete forever for sufficiently deep
   trees, blocking cursor advancement. Need an escape valve.

2. **Resolve missing parents on demand.** When `folderMap[uuid] == null`,
   call `api.getFolderMetadata(uuid)` once, populate folderMap, retry.
   Risk: N extra API calls per delta page for any unchanged ancestor;
   could re-trigger 429 storms (see UD-303).

3. **Look up the parent from state.db.** `StateDatabase` already has
   `getEntryByRemoteId(remoteId)`. If `folderMap[uuid] == null` ask the
   DB for the entry; use its `path` as the parent. Zero extra API
   calls. Risk: stale path if the DB itself has phantom rows (which
   here it does — circular).

(3) is the most attractive but needs invalidation when the DB knows it's
been wrong. Out-of-scope for a 90-minute time-box.

## State.db cleanup is also needed

Even after the buildFolderPath fix lands, the user's ~84k duplicate-
remote_id rows need a one-time reconciliation:

- For each remote_id with >1 path: the row whose path matches a real
  remote ancestor chain wins; the others are dropped.
- For `_XXX` specifically: drop the 97 stale `/_INBOX/_XXX/*` rows
  whose ancestor `/_INBOX/_XXX` has no remote_id (because the canonical
  row got moved to `/_XXX`). The UD-225 recovery loop then has nothing
  to re-emit.

This belongs in a `unidrive repair` flow or a one-shot migration in
`StateDatabase.initialize` keyed off a schema version bump. The
migration itself is straightforward; the open question is HOW to detect
which path is canonical without a fresh full enumeration.

## Next steps

1. **New ticket: structural delta-path fix** — pick from the three
   options above. Owner needs Internxt API access to validate option
   (2) latency cost. Likely option (3) with a `unidrive repair`
   complement.
2. **New ticket: state.db duplicate-remote_id reconciliation** —
   one-shot migration triggered by user-visible `unidrive repair` or
   bumped schema version.
3. **Regression test added in this branch** — see
   `InternxtProvider_DeltaPathResolutionTest.kt`. Confirms that an
   `InternxtFolder` with `parentUuid` pointing OUTSIDE `folderMap`
   currently produces a wrong-rooted path (test pinned at the bug for
   now; flip the assertion once one of the three fixes lands).

## Files of record (absolute paths)

- C:\Users\gerno\dev\git\unidrive\.claude\worktrees\agent-a14101438416a144e\core\providers\internxt\src\main\kotlin\org\krost\unidrive\internxt\InternxtProvider.kt:569 — `buildFolderPath` silent-empty fallback.
- C:\Users\gerno\dev\git\unidrive\.claude\worktrees\agent-a14101438416a144e\core\providers\internxt\src\main\kotlin\org\krost\unidrive\internxt\InternxtProvider.kt:629 — error string with `$segment`.
- C:\Users\gerno\dev\git\unidrive\.claude\worktrees\agent-a14101438416a144e\core\app\sync\src\main\kotlin\org\krost\unidrive\sync\Reconciler.kt:183 — UD-225 download recovery loop (multiplier).
- C:\Users\gerno\.unidrive\inxt_gernot_krost_posteo\state.db — reference data (98 rows with `_XXX`; ~84k duplicate remote_ids overall).
- C:\Users\gerno\.unidrive\inxt_gernot_krost_posteo\failures.jsonl — 7,206 rows, hundreds with `Folder not found: _XXX`.
