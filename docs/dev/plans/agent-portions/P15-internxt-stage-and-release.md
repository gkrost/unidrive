# P15 Internxt full enumeration: stage pages and release them instead of holding the account

| | |
|---|---|
| Issues | gkrost/unidrive#638 (comparison report adopt item 2) |
| Labels | enhancement, area:internxt, area:sync, kind:efficiency |
| Size | M |
| Priority | rank 15 of 53 (first-sync group), wave 3 |
| Status | ready after PR #880 and the heap probe |
| Depends on | PR #880 merged; P14 merged (its probe is the acceptance test) |
| Not in parallel with | P09, P12 (all edit `cursorFullDelta`); P22 (same provider); same file family (take them one after the other, order in README): P09, P16, P22, P21, P24, P25, P05, P31, P32 |
| Status check | verified on main a0c6c4d3, 2026-10-14: still open as described |

## Problem
- [code] `InternxtProvider.cursorFullDelta` keeps every item in two `LinkedHashMap`s (`folders`, `files`, DTOs `InternxtFolder` / `InternxtFile`) although each page is already persisted through `scanContext.persistPage` with the cursor marker. On a resume it first copies all staged rows back into the maps (`CloudItem.toResumedFolder()` / `toResumedFile()`), after the engine loaded them as a `List<CloudItem>` (`RemoteGather.gather`: `db.loadStagedItems(scanId)`, `resumedItems`).
- [code] After the last page `assembleFullDelta(files.values.toList(), folders.values.toList(), startedAt)` builds a folder map and a second list of `CloudItem` (decrypted names, paths) and returns ONE `DeltaPage`; `RemoteGather.gather` then admits every item into its `changes` map and `RemoteEnumeration.runEnumeration` merges that map into `state.db` in chunks (#873). Up to four whole-account copies are alive at once ([inferred]; the probe of P14 decides).
- [measured] Private bytes of the daemon: 461 MB before and 1,048 MB after the resume at 17:31:40 with 141,000 staged rows (capture run 2026-10-14); working set 1,068 MB at about 190,000 items and still 1,042 MB at 251,000. A status-filtered scan lists about 387,000 live items; headroom under `-Xmx2g` is unmeasured.
- The official client persists each page in 100-row transactions and forgets it. Its refresh later loads every row, which is its own problem.
- #316 is the reconcile-side cousin (mirror engine); this portion is the gather side for the mount daemon and the mirror alike.

## Scope
- Change: `InternxtProvider.cursorFullDelta` and `assembleFullDelta`; `RemoteGather.gather` (resumedItems handling) only as far as needed.
  1. Do not rebuild DTO maps from the staged rows on a resume. Keep only a folder index (`uuid -> parentUuid, plainName`, about 71,000 live folders) that path resolution needs; files are converted to `CloudItem` from the page or the staged row when assembled.
  2. Let `assembleFullDelta` hand the result to the engine in several `DeltaPage`s (`hasMore = true`, the engine already concatenates pages) or in a form that is built page by page, so the provider's own copy is not alive beside the engine's `changes` map.
  3. Drop the page DTOs as soon as `persistPage` returned.
- Must not change: the scan marker format and the resume rules (PR #880's fields included), `complete` semantics, the path-collapse rule (an item whose ancestors cannot be resolved is dropped and the page marked incomplete), the result of a listing (same items, same paths).
- Out of scope: the engine's `changes` map and the merge (a follow-up to be filed once the probe shows the engine copy as the bulk), the incremental delta, the tree walk.

## Acceptance criteria
1. Equivalence: for the fake drive of `InternxtCursorListingTest` (`DriveMock`), the items, paths and `complete` flag of fresh, resumed and fallback runs equal the results on main (the existing tests `both streams page to their last page ...`, `a listing killed part way resumes ...`, `the cursor listing returns what the offset listing returns ...` stay green unchanged; so do the `InternxtCursorFallbackTest` tests of PR #880).
2. Memory: with the probe of P14 at 200,000 items the retained heap after the listing is at most 60 percent of main's, and a resumed run retains no more than 1.1 times a fresh run (main: about 2 times, [inferred]). Numbers for 100k/200k/400k go in the PR body.
3. A scan with 5,000 folders and 400,000 files completes under `-Xmx512m` in the probe (record whether main does).
4. Path-collapse test: a file whose parent folder is missing from the listing is still dropped and `complete=false`.
5. No `Thread.sleep` in new tests; use the fake drive.
Commands: `cd core && ./gradlew :providers:internxt:test --tests '*InternxtCursorListingTest' --tests '*InternxtCursorFallbackTest' --tests '*InternxtScopedDeltaTest' && ./gradlew :app:engine-core:test --tests '*RemoteEnumerationTest'` plus the heap probe.

## Constraints
- Create your own worktree from origin/main (`git fetch origin`, then `git worktree add ../wt-<slug> -b <branch> origin/main`); never work in a shared checkout or in a worktree another session owns.
- Never `git stash`. Merge main into the branch (no rebase, no force-push).
- One branch and one DRAFT pull request for this portion; the owner merges. Check `gh pr list --state open` for a PR on the same files first.
- Claim each issue with a comment ("starting, branch ...") before editing, and report the PR on the issue.
- Commit messages describe the change; engine commits carry no ticket id in the subject, the PR body names the issues. End every commit with `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>` and the PR body with `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.
- Line endings follow `.gitattributes` (LF; only `*.bat`, `*.cmd`, `*.ps1` are CRLF): keep each file's endings. `core/gradlew.bat` always shows an EOL-only change in a Windows checkout: never commit it.
- detekt must pass against the per-module baselines (never regenerate a baseline; there is no ktlint in the build) and add no compiler warnings.
- Run only the touched test classes locally (`cd core && ./gradlew :app:<module>:test --tests '<class>'`); CI runs `./gradlew check` on the PR (read it with `gh pr checks`).
- Public repository: no private paths, account names or addresses in code, tests, commits or PRs. No live account, no deploy, no running `unidrive`/`java` process touched; use fakes, `localfs` or temp dirs.

## Open decisions for the owner
1. Several `DeltaPage`s with `hasMore` for a full listing (changes what other consumers of `delta()` see: `RemoteGather.nextPage`, the tracking-set CLI), or keep one page and only cut the provider-side copies? Proposal: keep one page first; do the multi-page change only if the probe still shows the provider copy as the bulk.

## How to verify live
Owner: with the health line in place, run a first scan of the large account twice, once after a clean start and once with a forced restart at about 140,000 items; compare heap used after GC in the health lines (no doubling at the resume) and that the scan completes with `UNIDRIVE_XMX=1g`.
