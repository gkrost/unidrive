# P51 Packaged runtime image: a class-data-sharing archive, measured first

| | |
|---|---|
| Issues | gkrost/unidrive#688 (the AppCDS and `--add-options` candidates of the closed-out audit #628 belong here) |
| Labels | none yet (build/dist) |
| Size | S for the code, M for the measurement |
| Priority | rank 51 of 54 (release group), wave 3 |
| Status | ready (measure first) |
| Depends on | nothing |
| Not in parallel with | P52, P53 (all edit `core/app/cli/build.gradle.kts`); same file family (take them one after the other, order in README): P52, P53 |
| Status check | verified on main a0c6c4d3, 2026-10-14: still open |

## Problem
- [code] `:app:cli:runtimeImage` (`core/app/cli/build.gradle.kts`, the `jlink` call at about lines 290-299) builds the packaged runtime with `--add-modules`, `--strip-debug`, `--no-man-pages`, `--no-header-files`, `--compress zip-9` and `--output`, and no `--generate-cds-archive` or `--add-options`. A jlinked image without the plugin has no default CDS archive (`lib/server/classes.jsa`), so every JVM start of the packaged engine (CLI one-shots, the daemon, `unidrive-win run`) loads and verifies the JDK classes from scratch. The Linux JVM spike (#624) benchmarked the full Zulu 27 JDK, which ships its archive, not the trimmed image, and flagged this.
- The Windows client documents about one second per JVM start for `daemon run --help` (`EngineHost.cs`). Nobody has measured the packaged image with and without the archive.
- Guards: `RuntimeModulesTest` and the jdeps check inside the task (jlink fails the build when it cannot create the archive; check it works with `--strip-debug` and the image's module set).
- Audit #628 candidates to try in the same change if the first number is good: an application CDS/AOT archive built against the exact image and `jlink --add-options`.

## Scope
- Change: step 1, a measurement harness (script under `scripts/dev/`, not part of the build): `daemon.status`-ready time and a one-shot `status` wall clock, image as built now versus `jlink ... --generate-cds-archive`, 10+ runs each, on Windows and Linux. Step 2, only if the gain is real: add the flag to the jlink arguments and print the size delta next to the existing `[runtime-image] ... MB` line.
- Must not change: module set, `--strip-debug`, the fat-jar launchers.
- Out of scope: the JDK 27 AOT cache unless the first number justifies it.

## Acceptance criteria
1. The measurement script prints median and spread for both images; its output for one machine is in the PR body with JDK build, OS and CPU.
2. If adopted: the task output shows the archive size; `RuntimeModulesTest` passes; the image still starts (`unidrive --help` and `daemon run --help` exit 0 in a smoke step).
3. If not adopted: the PR contains only the harness and the numbers, and says so; close #688 with the result.
Commands: `cd core && ./gradlew :app:cli:runtimeImage :app:cli:test --tests '*RuntimeModulesTest'` (building the image is the point of this portion; it is the one task allowed beyond test classes).

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
1. The threshold: how many milliseconds saved per start justify roughly 10-15 MB on disk? Proposal: at least 100 ms median.
2. Add the JDK 27 AOT cache in the same change if it is easy.

## How to verify live
Owner on the Windows machine: run the harness against the installed image before and after.
