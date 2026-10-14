# P13 daemon.status.enumeration: expected total and ETA for a scan

| | |
|---|---|
| Issues | none filed yet: file one before starting ("enumeration: expected total, basis and ETA"); related gkrost/unidrive-windows (flyout bar shows storage use, not scan progress) |
| Labels | enhancement, kind:observability (design proposal) |
| Size | M |
| Priority | rank 13 of 53 (first-sync group), wave 3 |
| Status | ready after the error-code portion |
| Depends on | P10 merged; P12 should land first if the total is to be given per stream |
| Not in parallel with | P12, P11 (same tracker/status files); same file family (take them one after the other, order in README): P10, P11, P12 |
| Status check | verified on main a0c6c4d3, 2026-10-14: `eta_s`/`eta_kind` exist but only with a baseline; no total is exposed |

## Problem
- [measured] The Windows flyout and Explorer panel show "93,000 items found" / "96,000 items in 3 min" for a first scan, no total, percent or ETA (owner's screenshots, 17:24). The blue bar beside the text is the account's storage use (754.9 GB of 3.3 TB), which a user can read as progress; the client fix is in gkrost/unidrive-windows.
- [code] `EnumerationTracker.baseline(first, expected)` receives `Expected(items, folders, durationMs)` from `RemoteEnumeration.previousFullEnumeration()` (sync_state keys `FULL_ENUMERATION_ITEMS_KEY`, `..._FOLDERS_KEY`, `..._MS_KEY`, written by `recordFullEnumeration` after a COMPLETE full enumeration). With it `snapshot()` computes `eta_s` and `eta_kind` (`estimate`, or `lower_bound` for a folder walk). The total itself (`Expected.items`) is not in `EnumerationStatus` / `EnumerationStatusJson`, so a client cannot show "X of Y" or a percentage.
- [code] A profile that never completed a scan has no `Expected`: no ETA. A failed first scan records nothing, so attempt 2 knows only its resumed count. `profile remove`/reset drops the baseline.
- [measured] Why bytes are not a basis: the 189,000 staged files of the stopped run (copy of `state.db`) add up to 688.3 GB, 91 percent of the account's `used_bytes` (754.9 GB), while only 60 percent of the 315,744 files were listed. The cursor order is `updatedAt`, not size, so listed-bytes over used-bytes overstates progress.
- [inferred] Live folders are about 71,000 and files 316,000; a first scan of this account needs about 21-25 minutes (comparison report). No count endpoint of the Internxt gateway is documented in this repo ([unverified] whether `files/count` style endpoints exist).

## Scope
- Change (all additive):
  1. Expose the baseline: `expected_items`, `expected_folders` and `expected_basis` (`previous_scan`) in `enumeration` when `Expected` exists (`EnumerationStatus`, `EnumerationStatusJson`, spec section 4.3).
  2. Persist a lower bound for a scan that did not finish: when an attempt fails, keep `items` (and the per-stream counts if P12 landed) as `expected_basis: partial_scan`, `expected_is_lower_bound: true` for the next attempts; cleared by the first complete scan.
  3. A provider hook for a count source: `ScanProgress` may carry `expectedItems` (nullable). Providers that cannot tell leave it null. Internxt implements it only if the live probe below finds a count endpoint.
- Must not change: `eta_s`/`eta_kind` semantics, `rate_per_s`, the baseline keys written by `recordFullEnumeration`, "unknown stays unknown" (no zero, no guessed total).
- Out of scope: any bytes-based estimate (measured above), client wording, the bar.

## Acceptance criteria
1. Fixture lines with `expected_items` / `expected_basis` are added to the IPC corpus first; `IpcContractCorpusTest` green.
2. `EnumerationTrackerTest`: with `baseline(first = true, expected = Expected(50_000, 1_000, null))` the snapshot reports `expected_items` 50,000 and `previous_scan`; without it the fields are absent.
3. A failed first attempt that reached 141,000 items makes the next attempt report `expected_basis: partial_scan` with 141,000 as a lower bound; a complete scan replaces it by `previous_scan`; a second profile is unaffected (per-profile `state.db`).
4. `eta_s` for `partial_scan` is absent (a lower bound gives no honest ETA).
5. A provider that sets `ScanProgress.expectedItems` is reported with basis `provider_count`; a fake provider test in `EnumerationStatusTest` (`app/sync`, its `ReportingProvider`).
6. `RemoteEnumerationTest` (`app/engine-core`): the keys survive a daemon restart.
Commands: `cd core && ./gradlew :app:engine-core:test --tests '*EnumerationTrackerTest' --tests '*RemoteEnumerationTest' && ./gradlew :app:sync:test --tests '*EnumerationStatusTest' && ./gradlew :app:cli:test --tests '*EnumerationStatusJsonTest' --tests '*IpcContractCorpusTest'`.

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
1. Is a lower-bound total (`partial_scan`) worth a field, or is "unknown" the honest answer for a first scan? Proposal: show it, flagged as a lower bound.
2. Should the client be allowed to show a percentage when the basis is `previous_scan` only? (The engine only reports numbers; the client words them.)
3. Is a count-endpoint probe acceptable (one read-only GET by the owner)?

## How to verify live
Owner: after one complete scan of the Internxt profile, restart the daemon, trigger `refresh.run` and read `daemon.status`: `expected_items` is about the scan's item count and `eta_s` falls. For the probe: one read-only GET on a count style endpoint with the owner's own token; report the status and body shape on the issue.
