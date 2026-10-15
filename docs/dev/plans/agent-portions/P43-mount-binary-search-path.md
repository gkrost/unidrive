# P43 Linux mount: find the co-daemon under /usr/lib as well as under the user's home

| | |
|---|---|
| Issues | gkrost/unidrive#799 |
| Labels | bug, area:mount, area:dist |
| Size | S |
| Priority | rank 43 of 54 (release group), wave 1 |
| Status | ready |
| Depends on | nothing |
| Not in parallel with | any other edit of `MountCommand.kt` |
| Status check | verified on main a0c6c4d3, 2026-10-14: still open |

## Problem
- [code] `MountCommand.defaultBinaryPath()` (`core/app/cli/src/main/kotlin/org/krost/unidrive/cli/MountCommand.kt`) is the only place the Linux co-daemon is looked for: `~/.local/lib/unidrive/unidrive-mount`, with no option and no environment variable. `run()` calls `checkBinaryExists`, which answers exit code 78 (EX_CONFIG, "not installed") and names only that one path.
- Distribution packages (deb, rpm, AUR) install the co-daemon system-wide at `/usr/lib/unidrive/unidrive-mount`; with such a package `unidrive -p <profile> mount <dir>` and `unidrive-mount@.service` (`dist/unidrive-mount@.service` runs `unidrive -p %i mount`) exit 78 and the unit stays inactive. Only the per-user tarball install works.
- `StaleMountDetector` matches the process by basename, so it needs no change.
- The sibling issue #107 (download and verify the co-daemon in `dist/install.sh`) uses the same install path; its search order must read the same list.

## Scope
- Change: `MountCommand.kt` (search order), `docs/env-vars.md` (new variable), `dist/README.md` (the paragraph about the install location).
  Search order, first executable file wins: (1) `UNIDRIVE_MOUNT_BIN` (explicit; if it names a missing file the command refuses and does NOT fall back), (2) `~/.local/lib/unidrive/unidrive-mount`, (3) `/usr/lib/unidrive/unidrive-mount`. The exit-78 message lists every path tried. An optional `--mount-bin` option may be added only if trivial.
- Must not change: exit code 78, the manual `cargo build` hint, how the co-daemon is started, `DaemonAutospawn`.
- Out of scope: the co-daemon's own connect retry (repository unidrive-mount-linux), packaging.

## Acceptance criteria
1. `MountCommandTest` (pattern: the test `binary not found returns EX_CONFIG` near line 192): each step of the order is chosen when it is the only one present; with two present the earlier wins.
2. No binary anywhere: exit 78 and the message names all three locations.
3. `UNIDRIVE_MOUNT_BIN` pointing at a missing file: refused, no fallback, message names that path.
4. A non-executable file at step 2 is skipped in favour of step 3.
5. The `/usr/lib` path is a constructor/seam parameter so the test does not need root (use a temp dir).
Commands: `cd core && ./gradlew :app:cli:test --tests '*MountCommandTest'`.

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
None. The variable name `UNIDRIVE_MOUNT_BIN` is the issue's suggestion.

## How to verify live
On a Linux host with a deb/rpm style install: `unidrive -p <profile> mount <dir>` finds `/usr/lib/unidrive/unidrive-mount`. Not needed for the merge.
