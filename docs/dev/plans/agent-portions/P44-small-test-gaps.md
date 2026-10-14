# P44 Two missing tests: ts-CLI authentication order, and Internxt key-derivation known answers

| | |
|---|---|
| Issues | gkrost/unidrive#168, gkrost/unidrive#336 |
| Labels | enhancement, sev:low, kind:test (#168 area:tracking-set, lane:ts-epic; #336 area:internxt, lane:deferred) |
| Size | S (two independent commits) |
| Priority | rank 44 of 54 (tests group), wave 1 |
| Status | ready |
| Depends on | nothing |
| Not in parallel with | nothing relevant (test-only; keep the two in separate commits) |
| Status check | verified on main a0c6c4d3, 2026-10-14: both still open as described (#336 partly: zero-byte and multi-shard are covered) |

## Problem
- #168 [code]: the fix 5e8f9bb (`TsSyncCommand` and `TsClaimCommand` call `authenticateAndLog()` before remote operations; now in `core/app/cli/.../tracking/TrackingCli.kt`, about lines 184 and 267, moved by PR #780) has no test; the bug was invisible because the engine is only exercised by live tests that authenticate in setup. Only `TsSyncCommandFlagTest` (picocli flags) and `TsContextProfileResolutionTest` exist; no test runs either command. The tracking-set engine is frozen (#343); this pins a contract that regresses silently.
- #336 [code]: `InternxtCryptoTest` (about lines 92-98 and 168-185) asserts `deriveBucketKey` / `deriveFileKey` (`InternxtCrypto.kt` about lines 133 and 147, a SHA-512 chain) only for length and determinism, with a hex bucket fixture; name encryption has a JS reference vector (about lines 101-123), the content-key chain has none. A regression in the seed, bucket key, file key chain still round-trips inside unidrive and only surfaces as cross-client garbage in live tests. Done since: zero-byte upload (`InternxtEmptyFileTest`, #496), multi-shard and length guard (`InternxtMultiShardDownloadTest`, `InternxtRemoteIncompleteDownloadTest`, #483).

## Scope
- #168: a test next to `TsContextProfileResolutionTest` (`core/app/cli/src/test/kotlin/org/krost/unidrive/tracking/`): set `TsCommand().servicesRef` to an inline `CliServices` (the existing test already has one) whose `createProvider` returns a recording `CloudProvider` stub (`ShareCommandTest` has such a fake; `cli` has no `testFixtures(:app:sync)` dependency, so `FakeCloudProvider` is not available there), run `ts sync` and `ts claim`, assert that `authenticate()` happens before the first `listChildren` / `delta`.
- #336: known-answer vectors for `deriveBucketKey` and `deriveFileKey` for a fixed mnemonic, hex bucket id and index. Source of the vectors: decision 1.
- Must not change: production code (test-only portion).

## Acceptance criteria
1. The new ts test fails if the `authenticateAndLog()` call is removed from either command (show the red run in the PR body by deleting the call locally, then restore it).
2. The known-answer test pins at least two (mnemonic, bucket, index) triples and fails if the derivation changes by one byte.
3. No change in any other test; both tests run in under a second.
Commands: `cd core && ./gradlew :app:cli:test --tests '*TsContextProfileResolutionTest' --tests '*TsAuthenticateOrderTest' && ./gradlew :providers:internxt:test --tests '*InternxtCryptoTest'`.

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
1. Source of the #336 vectors: a run of the reference JavaScript (`inxt-js` `GenerateFileBucketKey` / `GenerateFileKey`; do not install packages from the network on an agent: read the algorithm from a local clone of drive-desktop, `git -C <clone> show origin/feat/2-7-0-release:<path>`, or from the owner), or an independent re-implementation of the documented chain (Python `hashlib`) whose outputs are pasted as constants. Proposal: the independent re-implementation, with the chain description quoted in the test comment.

## How to verify live
No live check needed.
