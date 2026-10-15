# P01 Windows launcher drops standard input: profile set ignore_rules clears the rules

| | |
|---|---|
| Issues | gkrost/unidrive#859 |
| Labels | bug (data loss of a setting), area:cli, windows |
| Size | S |
| Priority | rank 1 of 54 (correctness group), wave 1 |
| Status | ready |
| Depends on | nothing |
| Not in parallel with | any change of `core/app/cli/build.gradle.kts` deploy tasks or `dist/launcher/*.tmpl` |
| Status check | verified on main a0c6c4d3, 2026-10-14: still open (the template's last line is `& java @javaArgs @passArgs`) |

## Problem
- [measured] (issue text, throwaway config, build 0.0.1+798eb44c) `cmd /c "unidrive.cmd -c <cfg> profile set pa ignore_rules --json < rules.txt"` answers `{"ok":true,...,"value":[],"previous":[]}` (the rules are lost) while `java -jar unidrive-0.0.1.jar -c <cfg> profile set pa ignore_rules --json < rules.txt` stores `["*.tmp","build/"]`. Piping from PowerShell and `pwsh -File` give the same result.
- [code] `dist/launcher/unidrive.ps1.tmpl` (rendered by `renderLauncher` in `core/app/cli/build.gradle.kts` into `%LOCALAPPDATA%\unidrive\unidrive.ps1`, reached through `unidrive.cmd` and `powershell -NoProfile -ExecutionPolicy Bypass -File`) ends with `& java @javaArgs @passArgs`. Inside a PowerShell script, input that arrives from outside is the script's pipeline input (`$input`); the native command is started without it, so java sees an empty standard input.
- [code] Verbs that read standard input: `profile set <p> ignore_rules` (an EMPTY input means "clear", so the command reports success and wipes the rules), `auth login --password-stdin`. The front-end (gkrost/unidrive-windows) calls them through this launcher.
- The sh launcher (`dist/launcher/unidrive.sh.tmpl`) ends in `exec "$java_bin" ...`, which inherits stdin: not affected.
- The CLI tests call the command classes directly, so no test sees the launcher's stdin handling.

## Scope
- Change: the last lines of `dist/launcher/unidrive.ps1.tmpl` (and nothing else of the template).
  Proposed: `if ([Console]::IsInputRedirected) { $input | & java @javaArgs @passArgs } else { & java @javaArgs @passArgs }`, then `exit $LASTEXITCODE`. The `else` branch keeps an interactive console attached (the masked password prompt of `auth login`, `System.console()` checks in `ProfileCommand`).
- Also update the one place that rewrites that line: `LauncherTemplateTest.launcherArgs` replaces the literal `& java @javaArgs @passArgs` to print the arguments instead of starting java; keep both tests working.
- Must not change: argument handling (single-quoted array plus splat), the exit code pass-through, the UTF-8 console setup, `unidrive.cmd`, `unidrive-watch.cmd`.
- Out of scope: what `ignore_rules` does with an empty input (clearing is by design, see the docs of `profile set`); a `--rules-file` option.

## Acceptance criteria
1. A test in `LauncherTemplateTest` (Windows only, `assumeTrue`, real `powershell.exe` like the existing tests) renders the template with the java line replaced by a stub that echoes standard input (for example a one-line `powershell -NoProfile -Command "[Console]::In.ReadToEnd()"`), feeds `*.tmp` and `build/` on stdin, and sees both lines.
2. A second case with no redirected stdin keeps working (stub exits 0, no hang); run the process with `ProcessBuilder.Redirect.INHERIT` for stdin or an empty pipe closed at once, whichever the CI leg allows.
3. The existing two tests of that class pass unchanged apart from the replaced literal.
4. `core/app/cli` `deploy` output (a rendered `unidrive.ps1`) is checked by reading the generated text in a unit test of `renderLauncher` if one exists; otherwise by the new test only.
5. The PR body shows the issue's repro before and after (throwaway config dir, localfs profile, no real account).
Commands: `cd core && ./gradlew :app:cli:test --tests '*LauncherTemplateTest' --tests '*LauncherShTemplateTest'` (the Windows tests run on the `check-windows` leg of CI).

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
None. If `[Console]::IsInputRedirected` proves unreliable under `-File` on Windows PowerShell 5.1, use `$MyInvocation.ExpectingInput` and say so in the PR.

## How to verify live
On the Windows machine after `deploy`: `unidrive.cmd -c <throwaway-config-dir> profile set <localfs-profile> ignore_rules --json < rules.txt` stores the rules; `unidrive.cmd auth login --password-stdin` still works from the Windows client. Do not use a real account.
