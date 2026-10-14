# P17 A truncated cloud object: remember the refusal per object instead of asking again on every read

| | |
|---|---|
| Issues | gkrost/unidrive#696 (related, not covered: #556 is a data census, see the index) |
| Labels | area:internxt, kind:reliability |
| Size | M |
| Priority | rank 17 of 54 (first-sync group), wave 2 |
| Status | ready after the close-handle portion |
| Depends on | nothing |
| Not in parallel with | P18, P08, P36 (all edit `HydrationImpl.kt`); same file family (take them one after the other, order in README): P18, P08, P35, P36, P03, P06, P02 |
| Status check | verified on main a0c6c4d3, 2026-10-14: still open |

## Problem
- Hydration of an object known to be truncated (stored size smaller than declared; 19 such objects in the owner's drive, census #556) is refused at the response headers (`RemoteIncompleteDownloadException`, token `remote_incomplete`), but nothing remembers it: every read or pin attempt asks the provider again (two metadata calls and one GET answered with `Content-Length`, no body) and logs a WARN. [measured] (issue, 2026-10-09, engine e0ee9efb): two bursts of reads over a tree produced 1 to 5 refusals per object per burst for four objects, 3 to 11 per object over the day.
- [code] No memory exists anywhere: `InternxtProvider.downloadWithFileUuid` (about line 500) rethrows after a WARN every time; `HydrationImpl.downloadFailureOf` maps the exception to `HydrationError.RemoteIncomplete` without recording; `download_quarantined` is row-based (`RemoteGather` about line 903, `Reconciler` about line 317) and for these objects the rows are `DELETED`, so no existing quarantine site has a row to flag; `daemon.status` has no such field.
- The audit #579 item 4 asks the same ("give remote_incomplete a re-check and surface it in status"). The engine's other quarantine has no expiry either.
- The refusals are cheap, so this is noise and avoidable API traffic, not a load problem; the client's pin worker is bounded, an application's read is not.

## Scope
- Change:
  1. A small per-daemon store keyed by remote id holding `(stored size, declared size, observed at)`, independent of `sync_entries` rows (in memory is enough for the first version; a sidecar file next to `state.db` only if decision 1 says so). Placed beside `MountEngine` / `HydrationImpl`.
  2. `HydrationImpl.openForRead` / `hydrate` answer repeats from the store with the same `remote_incomplete` error until the object's size, hash or modification time changes (the next listing/metadata read) or the TTL passes; after the TTL one cheap header check revalidates.
  3. WARN once per object per daemon run, afterwards DEBUG.
  4. `daemon.status` additive field, for example `remote_incomplete: {"count": N}` (count only, no names), fixture first; the per-object list is a later step.
- Must not change: the refusal itself, the guards that stop a truncated object entering the cache, quarantine semantics.
- Out of scope: repairing objects (#556 is owner data work), the row-based quarantine TTL.

## Acceptance criteria
1. `InternxtRemoteIncompleteDownloadTest`-style provider test unchanged; new `HydrationImplTest` case (`HydrationTestEnv.makeNextDownloadThrow`, pattern in `HydrationOpenReadNotFoundTest`): two hydrations of a mock object that throws `RemoteIncompleteDownloadException` make ONE provider request; the second answer is `remote_incomplete`.
2. A size, hash or mtime change of the object (via the enumeration) clears the entry; the next read asks the provider again.
3. After the TTL one revalidation request is made; if the object is complete now the read succeeds and the entry is gone.
4. One WARN per object per run (assert with a log appender or a counting seam).
5. The status field is absent when the store is empty; `IpcContractCorpusTest` green with the fixture.
Commands: `cd core && ./gradlew :app:hydration:test --tests '*HydrationImplTest' --tests '*HydrationOpenReadNotFoundTest' && ./gradlew :providers:internxt:test --tests '*InternxtRemoteIncompleteDownloadTest' && ./gradlew :app:cli:test --tests '*IpcContractCorpusTest' --tests '*DaemonStatusHealthTest'`.

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
1. Persist across restarts, or memory only? Proposal: memory only first (the cost of a refusal after a restart is three cheap calls).
2. TTL value (proposal one hour).

## How to verify live
Owner: read a known truncated file twice from the mount; the second refusal does not appear as a provider request in the engine log and the WARN appears once.
