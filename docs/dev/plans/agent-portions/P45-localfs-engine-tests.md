# P45 Engine tests on the localfs provider: mirror drift, exact round trip, seeded Unicode fuzz, hazard names

| | |
|---|---|
| Issues | gkrost/unidrive#589 |
| Labels | enhancement, sev:low, area:sync, kind:test |
| Size | M (items 1 and 3 are S-M, items 2 and 4 are M; one commit per item) |
| Priority | rank 45 of 54 (tests group), wave 2 |
| Status | ready |
| Depends on | nothing |
| Not in parallel with | P03 (item 1 includes an NFC twin rename) |
| Status check | verified on main a0c6c4d3, 2026-10-14: none of the five items exists as a localfs test |

## Problem
- Test ideas from an older end-to-end harness, re-hosted on the `localfs` provider (no cloud account, no browser): "remote" changes are plain file operations on the localfs `root_path`. [code] Today sync tests use `FakeCloudProvider` (`SyncEngineTest`); only `LsCommandTest`, `MigrateConvertTest`, `RelocateCommandTest` and `LocalFsProviderTest` touch localfs. `VerifyCommandTest` (about lines 33-60: `Main` plus a localfs profile in-process) is the pattern and has no mirror-drift scenario.
- Items: (1) mirror drift: local edit, new file, delete and folder rename plus remote edit, new file, rename and move, then sync and assert both sides; add a folder rename, a cross-folder move and renaming one of an NFD/NFC twin pair (#491); engine counterpart of the client's rename/move writeback (unidrive-windows#87). (2) ground-truth round trip on Linux/localfs: generate the golden tree (`scripts/golden/unicode-tree/layout.json`), sync up, delete the local tree and `state.db`, sync down, `get` a sample, verify EXACT sizes and hashes (the old harness accepted larger files, so a truncated download #536 must fail); swapping the provider profile makes it a parity run. (3) seeded code-point fuzzing into `PathNormalizer`, `localNameIssue` and `StagingNames` (255-byte limit), recording the seed and the JDK's Unicode version. (4) hazards the golden suite leaves out: reserved device names, trailing dot or space, paths of 260 characters or more, written into the localfs remote from Linux and asserted as quarantined as documented (NTFS counts UTF-16 code units: 300 emoji are 600 units). (5) a cross-device conflict rig: manual, keep on the manual list (unidrive-windows#95).
- Existing example-based tests: `LocalNamesTest`, `StagingNamesTest`, `PathNormalizerTest`; no JVM test reads `layout.json`.

## Scope
- Change: new test classes beside `core/app/cli/src/test/.../SyncCommandTest.kt` using the `VerifyCommandTest` setup (item 1, item 2, item 4) and next to the three name tests (item 3); item 2 reads the repo-relative golden tree (skip with a message when absent); the heavy ones tagged so `check` time stays bounded.
- Must not change: production code; any failing scenario that reveals a bug becomes a separate issue with the red test attached (do not fix here).
- Out of scope: item 5, the Windows-only `run-localfs.ps1`.

## Acceptance criteria
1. Item 1: a class of at least six drift scenarios, each asserting both sides and `state.db`; a second sync plans nothing.
2. Item 2: the round trip passes with exact sizes and hashes; a deliberately truncated download in a fake variant fails the test (red-first proof in the PR body).
3. Item 3: a fixed seed list plus a rotating seed from the environment; the seed and Unicode version are printed on failure.
4. Item 4: each hazard is quarantined with the documented reason and does not stop its siblings.
5. Tests complete in under 60 s together on the Linux leg; Windows-incompatible hazards use `assumeTrue`.
Commands: `cd core && ./gradlew :app:cli:test --tests '*LocalfsMirrorDriftTest' --tests '*LocalfsGoldenRoundTripTest' && ./gradlew :app:engine-core:test --tests '*LocalNamesFuzzTest'` (names of the new classes are yours to choose; keep them in these modules).

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
1. How `check` reads the repo-relative golden tree and its runtime budget (proposal: skip when the folder is not found, run on CI).

## How to verify live
No live check needed.
