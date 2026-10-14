# P49 dist: test the stale-jar NOTE of install.sh, and limit the mount unit's pre-start unmount to a dead endpoint

| | |
|---|---|
| Issues | gkrost/unidrive#745, gkrost/unidrive#746 |
| Labels | sev:low, area:dist (#745 kind:test, #746 kind:reliability) |
| Size | S (two commits) |
| Priority | rank 49 of 54 (tests group), wave 1 |
| Status | ready |
| Depends on | nothing |
| Not in parallel with | any edit of `dist/install.sh` (including #107, not packaged: blocked on the sibling release) |
| Status check | verified on main a0c6c4d3, 2026-10-14: both open as described |

## Problem
- #745 [code]: `dist/install.sh` captures `STALE_PIDS` (about lines 92-101) and prints `NOTE: still running the previous jar (pid ...)` (about lines 183-186) for JVMs started by hand (#715, ordering fixed in #732). `InstallShTest` (`core/app/cli/src/test/.../InstallShTest.kt`, Linux only) has two tests (fresh inode; stop/start order); none covers the NOTE, its content or ordering. The fake `systemctl` is at about lines 33-40 of the test.
- #746 [code]: `dist/unidrive-mount@.service` runs `ExecStartPre=-/usr/bin/fusermount3 -uz ${UNIDRIVE_MOUNTPOINT}` (line 16) and the same as `ExecStopPost` (line 20) to clear a dead FUSE endpoint. The lazy unmount also detaches a LIVE mount that a user started by hand at the same directory. A dead FUSE mount fails `stat` with ENOTCONN, so a guard such as `ExecStartPre=-/bin/sh -c 'test -e "$UNIDRIVE_MOUNTPOINT" || /usr/bin/fusermount3 -uz "$UNIDRIVE_MOUNTPOINT"'` would clear only the dead case. Also `/usr/bin/fusermount3` is hard-coded (FUSE 2 systems ship only `fusermount`). `dist/README.md` (about lines 65-70) documents the broad behaviour; `SystemdUnitsTest` (about lines 63-67) asserts the exact directive text.
- [unverified] How systemd expands `$` and `${}` inside the `sh -c` word: needs a Linux host.

## Scope
- #745: extend the fake `systemctl start` of `InstallShTest` so it launches a second long-running process whose command line holds `-jar <HOME>/.local/lib/unidrive/unidrive-1.jar`; pre-start a first such process by hand before the install; assert the output names only the first pid; kill both in teardown.
- #746: change both directives to the dead-endpoint guard (decision 1 for the FUSE 2 fallback), update `SystemdUnitsTest` and `dist/README.md`.
- Must not change: the order of install steps, the unit's other directives, the Linux-only gating of `InstallShTest`.
- Out of scope: the co-daemon download (#107).

## Acceptance criteria
1. The new `InstallShTest` case fails if the NOTE loop is changed to include the freshly started process (show the red run in the PR body).
2. `SystemdUnitsTest` asserts the new guard text for both directives; `bash -n` style syntax check of the embedded `sh -c` string passes.
3. `dist/README.md` describes the narrower behaviour.
4. On a Linux host (owner or CI Linux leg), a live mount started by hand at the unit's directory survives `systemctl --user start unidrive-mount@x` (manual check, noted in the PR).
Commands: `cd core && ./gradlew :app:cli:test --tests '*InstallShTest' --tests '*SystemdUnitsTest'`.

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
1. FUSE 2 systems: add a `fusermount` fallback or document that only FUSE 3 is supported? Proposal: document.
2. Should `ExecStopPost` keep the broad unmount (after stop the endpoint IS ours)? Proposal: keep it broad.

## How to verify live
A Linux host: mount by hand at the unit's directory, start the unit, confirm the hand-started mount is untouched; kill the co-daemon with `kill -9` and confirm the next start clears the dead endpoint.
