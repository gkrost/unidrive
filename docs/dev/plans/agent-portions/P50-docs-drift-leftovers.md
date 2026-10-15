# P50 Docs drift: what is left of the status audit

| | |
|---|---|
| Issues | gkrost/unidrive#412 (and the BACKLOG line of #364, which is a close candidate, see the index) |
| Labels | documentation, sev:low, area:sync, lane:quick-win |
| Size | S |
| Priority | rank 50 of 54 (docs group), wave 1 |
| Status | ready |
| Depends on | nothing |
| Not in parallel with | any other edit of `BACKLOG.md` (one BACKLOG change per commit, AGENTS.md) |
| Status check | verified on main a0c6c4d3, 2026-10-14: mostly fixed (PR #446, #677, #708); four leftovers |

## Problem
The 2026-09-29 audit found the docs describing a different system than the code. Fixed since [code]: README names the legacy `SyncEngine` (README.md lines about 94, 128) and the frozen tracking-set engine (about 121); the three spec headers now say Implemented or Implemented-in-part; the sync-tracking README no longer contradicts itself; `dist/unidrive.service` runs `unidrive autostart` (PR #677); `app:config` is gone and the dangling `docs/SPECS.md` reference with it (PR #708). Left:
1. `BACKLOG.md` links closed issues: #288 (line 17, under Critical), #135, #141, #142 (lines 36-38), #147 (line 42), #151 (line 44) are all CLOSED on GitHub; #364 (line 18, "Critical") is a third-party report that is not engine work (#372 says so; the unidrive counterpart #303 is closed). AGENTS.md: "BACKLOG to CLOSED in the same commit".
2. `.claude/skills/unidrive-log-anomalies/SKILL.md` lines 37-38 link `docs/backlog/BACKLOG.md`, which does not exist; the file is the root `BACKLOG.md`.
3. Three test comments cite a missing CHANGELOG: `CloudItemTest.kt` (line about 54), `ProviderMetadataTest.kt` (about 139 and 196).
4. The embed-boundary wording still names the tracking-set engine and #99 as the boundary: `docs/adr/multi-platform.md` line about 30 and `docs/dev/plans/win11-cfapi-mount.md` (about lines 19, 113-116, 150). #343 froze that engine; no issue or code covers an embed. This needs the owner's statement of the post-#343 boundary (decision 1).
Other items of the audit that lived in the Windows repository are not part of this portion.

## Scope
- Change: items 1-3 as described (move the closed entries to `CLOSED.md` following that file's format, delete the #364 line; fix the two SKILL.md links; drop or reword the CHANGELOG sentences). Item 4 only after decision 1.
- Must not change: any code, the meaning of the ADRs' decisions.
- Out of scope: the 667 `UD-###` references in source comments (#702 item 22).

## Acceptance criteria
1. `BACKLOG.md` has no entry whose issue is closed: check with a loop over `gh issue view N --json state` (paste the output in the PR body) or `gh issue list --state closed` against the `[#N]` entries; every moved entry appears in `CLOSED.md`.
2. `git grep -n "docs/backlog/BACKLOG.md"` finds no hit outside history; `git grep -n "See CHANGELOG"` in `core/` finds none.
3. If decision 1 is given: both boundary passages state the owner's wording.
4. No code or test behaviour changed (`git diff --stat` shows only md and comment lines).
Commands: none needed beyond `git diff --stat`; compile check only if test comments change: `cd core && ./gradlew :app:core:compileTestKotlin`.

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
1. What is the embed boundary after #343 (the legacy `SyncEngine` module, or none until the tracking-set graduates)?

## How to verify live
No live check needed.
