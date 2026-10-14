# P31 Internxt strict compatibility mode: never create a name Internxt's own clients reject

| | |
|---|---|
| Issues | gkrost/unidrive#577 |
| Labels | enhancement, sev:medium, area:internxt, kind:correctness |
| Size | M |
| Priority | rank 31 of 53 (correctness group), wave 4 |
| Status | ready (key name: decision proposed) |
| Depends on | nothing; step 1 refreshes the rules before any code |
| Not in parallel with | P05, P24 (`InternxtProvider` upload path); same file family (take them one after the other, order in README): P09, P15, P16, P22, P21, P24, P25, P05, P32 |
| Status check | verified on main a0c6c4d3, 2026-10-14: still open; nothing of the mode exists |

## Problem
- Owner request 2026-10-05: a profile option that makes unidrive refuse, on every path, any name the official Internxt clients would not accept, so that everything created is usable in the official apps, and everything hidden is hidden only by Internxt's own rules. Default off.
- Rules table in the issue (read 2026-10-05, not run): the server rejects folder names matching `/[\/]|^\s*$/` (no maximum length, no reserved-name check; file-name rules not checked); the desktop client skips items matching `/[<>:"/\|?*]|^\s|\s$/` (`INVALID_WINDOWS_NAME`); Windows refuses control characters, trailing space or dot, device names (`CON`, `PRN`, `AUX`, `NUL`, `COM1-9`, `LPT1-9`, also with an extension) and more than 255 UTF-16 units per component; Linux more than 255 bytes in UTF-8. Not checked: server file-name rules, the web UI, mobile apps.
- [code] Nothing exists: `name_incompatible`, `strict_names` and `INVALID_WINDOWS_NAME` have no hits; `InternxtProviderFactory.create` reads only `keep_overwritten`. Near it: `LocalNames.localNameIssue` (engine-core, file-system rules only, used by `LocalScanner`, `HydrationImpl.refusedNewName` for mkdir/create/rename, `PlaceholderManager`); `InternxtProvider.sanitizeName` is `raw.trim()` and silently renames names with edge whitespace today.
- Reusable machinery [code]: `NameRefusedException(reason, sibling)` (`ProviderException.kt`) is already honoured by `HydrationImpl.runUploadWithRetries`, `MountEngine.recordBlockedFolder` / `markNameBlocked` and surfaced in `hydration.list` `blocked` and `daemon.status` (PR #809). The OneDrive equivalent is `OneDriveNameRules` (PR #829).
- The official client's rules must be re-read at the measured binary's source: drive-desktop `origin/feat/2-7-0-release` (cursor sync); the git tag `v2.7.0` is an older sync. Read with `git -C <drive-desktop clone> show origin/feat/2-7-0-release:<path>` (do not check anything out), e.g. `src/context/virtual-drive/items/validate-windows-name.ts`.

## Scope
- Step 1: refresh the rules table against `feat/2-7-0-release` and the public `drive-server-wip` source (file names too); put the result in the PR body.
- Step 2: a pure `InternxtNameRules.issue(name): String?` (the union of the server rule, the desktop rule and the portable limits: at most 255 UTF-16 units AND 255 UTF-8 bytes, no device name, no trailing dot or space), applied before any provider call when the profile key is on (name of the key: decision 1) in `createFolder(s)`, `upload`, `move`/rename; refusal throws `NameRefusedException` with the rule named (no silent rename). Existing cloud items with incompatible names: counted and reported once per folder (not renamed, not deleted, not uploaded over), siblings still list.
- Must not change: behaviour with the key off, the OneDrive rules, `localNameIssue`.
- Out of scope: the Explorer-side refusal at the rename callback (client repository), the alias option for names over 255 (unidrive-windows#165).

## Acceptance criteria
1. Table-driven `InternxtNameRulesTest` (new): one case per rule, each boundary (255/256 UTF-16 units and 255/256 UTF-8 bytes with multi-byte characters), a normal name.
2. With the key on, `mkdir "a:b"`, `create "x "`, `rename` to `CON.txt` and a 300-character upload each fail before any provider request (fake provider records zero calls) with `NameRefusedException`; with it off they behave as today (`InternxtNameTest`, `InternxtNameSanitizationTest`, `NameRefusalTest` in `app/hydration` unchanged).
3. An existing incompatible cloud name is counted in `daemon.status` and does not stop its siblings from listing.
4. Key validation in the config schema docs.
Commands: `cd core && ./gradlew :providers:internxt:test --tests '*InternxtNameRulesTest' --tests '*InternxtNameTest' --tests '*InternxtNameSanitizationTest' && ./gradlew :app:hydration:test --tests '*NameRefusalTest'`.

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
1. The profile key name (`internxt_strict_names` is the issue's example).
2. Whether names with edge whitespace stay silently trimmed (today) when the mode is off.

## How to verify live
Owner, isolated account with the mode on: create `a:b` in the mount; the operation is refused with the reason and nothing reaches the cloud.
