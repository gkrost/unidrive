# P52 A compiler-warning gate for the Kotlin modules (audit #628 finding 4)

| | |
|---|---|
| Issues | gkrost/unidrive#628 (findings 1-3 are delivered: OOM policy PR #639, one flag list, diagnostics PR #667; the AppCDS bullet is P51) |
| Labels | documentation, area:cli, area:dist, kind:observability, lane:research |
| Size | S-M |
| Priority | rank 52 of 53 (release group), wave 4 |
| Status | blocked on owner decision |
| Depends on | the owner decision below |
| Not in parallel with | P51, P53 (build scripts), every portion in flight (a new gate touches all modules' warnings); same file family (take them one after the other, order in README): P51, P53 |
| Status check | verified on main a0c6c4d3, 2026-10-14: finding 4 still open |

## Problem
- [code] No Kotlin warning gate: no `allWarningsAsErrors`, `-Werror` or `-Xlint` in any core build file (`core/build.gradle.kts` has `-Xlint:all` as report-only for the few Java sources, about lines 230-233; the repo has no `.java` file). detekt runs as a ratchet (PR #712) but is not a compiler gate. The audit #702 notes a zero-warnings bar held by convention, without a mechanism that enforces it; the Windows client builds with TreatWarningsAsErrors.
- The audit's other open flag items wait on data (`gc.log` of the daemons for wave-2 GC choices).
- [unverified] How many warnings the modules emit today; every module must be counted first.

## Scope
- Change: step 1, count the warnings per module from a clean compile (`./gradlew compileKotlin compileTestKotlin --rerun-tasks` is allowed here, once, as measurement; paste the table). Step 2, per module with zero warnings: `allWarningsAsErrors.set(true)` in the shared Kotlin configuration; modules with warnings: either fix them (separate commits) or record a per-module count that the build checks does not grow (a small ratchet script) until they reach zero.
- Must not change: runtime behaviour, detekt baselines, dependency versions.
- Out of scope: Java lint, new analysers.

## Acceptance criteria
1. The PR body has the per-module warning count before the change.
2. `compileKotlin` and `compileTestKotlin` of every module with the gate on pass; introducing a deliberate warning in one module makes its build fail (red-first proof, reverted).
3. For ratcheted modules, adding one warning fails `check`; removing one lowers the recorded count by a documented command.
4. `AGENTS.md` states the rule in one line (the doc surface is bounded: edit, do not add files).
Commands: `cd core && ./gradlew :app:core:compileKotlin :app:core:compileTestKotlin` per module as needed; CI runs the rest.

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
1. Enforce the gate in the engine at all (the client does)? Proposal: yes for new code via the ratchet, hard gate where the count is zero.
2. Fix existing warnings in this portion or only record them?

## How to verify live
No live check needed.
