# P42 Engine logs: a quiet default, a per-profile durable log, a WARN side file, the stop reason

| | |
|---|---|
| Issues | gkrost/unidrive#664 (first half: levels, files, side file; the run record and the "previous daemon did not stop in order" report are the second half, to be filed after this merges; comparison adopt item 8 is the side file) |
| Labels | enhancement, area:cli, kind:observability, lane:desktop-ui |
| Size | M (first half of an L; the second half is filed after this merges) |
| Priority | rank 42 of 53 (observability group), wave 4 |
| Status | blocked on owner decision (owner starts it when asked) |
| Depends on | P41 merged |
| Not in parallel with | P41, P19 and P14 (`DaemonRuntime.kt` lines), any `logback.xml` edit |
| Status check | verified on main a0c6c4d3, 2026-10-14: still open as described |

## Problem
- [code] `core/app/cli/src/main/resources/logback.xml`: `org.krost.unidrive` is DEBUG into ONE `unidrive.log` (10 MB per file, 5 days, 50 MB total) shared by every profile's daemon and every one-shot CLI call on the machine; the console appender is WARN on stderr; one line per HTTP request and response (about 140 a minute while a mount hydrates); no environment variable for the level; no per-profile file; no WARN side file. The daemon logs `daemon: shutting down` without a reason (`DaemonRuntime` about line 640); the shutdown hook (`DaemonCommand` about line 130) and the `daemon.shutdown` handler (about line 619) log nothing about who or why; a hard kill (TerminateProcess on Windows skips shutdown hooks) leaves nothing.
- [measured] The capture run needed a 500 MB cap override (`logback-capture.xml`, 50 MB x 30 files) because the shipped 50 MB total would have rolled the beginning of a first scan away; `drive-important.log` of the official client (6.2 KB against 521 KB) shows the value of a WARN-and-above side file with its own retention.
- Origin (issue, 2026-10-08): a mount profile was stopped from outside and the cause had to be reconstructed from the Windows PowerShell script-block log.

## Scope
- Change: `logback.xml` (and the launcher/`DaemonAutospawn` environment pass-through): file level INFO by default; request/response lines to DEBUG and, when switched on by `UNIDRIVE_LOG_LEVEL=debug` or a config key, to their own bounded `requests.log`; `UNIDRIVE_LOG_LEVEL=warn|info|debug` for everything else, documented in `docs/env-vars.md`; a per-profile durable log `unidrive-<profile>.log` for the daemon of a profile (a pre-initialisation system property or a sifting appender keyed on the profile MDC; the one-shot CLI keeps `unidrive.log`); a WARN-and-above side file with its own retention (for example 10 MB x 10); stop reason at INFO: `daemon.shutdown` (client identity and scope), SIGTERM/SIGINT/console event via the shutdown hook, fatal enumeration or auth error, normal end.
- Development context (a dirty build or `UNIDRIVE_LOG_LEVEL` set) keeps today's detail.
- Must not change: the log line pattern, the profile/build columns, privacy rules (no names, no queries), the capture override mechanism (`logback-capture.xml` of the capture harness).
- Out of scope (second half): the daemon run record in the profile folder and the next start's "did not stop in order" line; console logs not overwritten by the starter (the Windows host already keeps them).

## Acceptance criteria
1. `LogbackXmlWellFormedTest` (exists in `app/cli`) stays green; a new test starts logback with each level setting and checks which appenders receive a DEBUG request line.
2. Default install: a daemon run writes start, stop reason and WARN lines to `unidrive-<profile>.log`, a few MB per day in the DEBUG-off case (measure in the PR body with the fake daemon of `DaemonRuntimeTest`).
3. `daemon.shutdown`, a simulated SIGTERM and a normal end each produce their own INFO line naming the reason (unit tests with the shutdown path seams of `DaemonRuntimeTest` / `DaemonCommandTest`).
4. The WARN side file contains every WARN of the main log and nothing below; rotation caps hold.
5. `docs/env-vars.md` documents `UNIDRIVE_LOG_LEVEL`; `scripts/dev/log-watch.sh` still parses the new layout.
Commands: `cd core && ./gradlew :app:cli:test --tests '*LogbackXmlWellFormedTest' --tests '*DaemonRuntimeTest' --tests '*DaemonCommandTest'`.

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
1. Start now, or later ("when the owner asks", lane document)?
2. Level policy for the development context; environment variable only, or also a profile key?
3. Keep the shared `unidrive.log` for one-shot calls (proposal) or drop it.

## How to verify live
Owner: run a daemon, stop it with `daemon stop`, with Ctrl+C and with Task Manager; the first two leave a stop-reason line, the third leaves none (that is the second half's job).
