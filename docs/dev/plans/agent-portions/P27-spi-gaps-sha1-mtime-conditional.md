# P27 SPI gaps for the next providers: Sha1 hash, mtime precision, conditional-write capability

| | |
|---|---|
| Issues | gkrost/unidrive#846 (items 1, 3, 4; item 2, ranged download, is P26) |
| Labels | enhancement |
| Size | M |
| Priority | rank 27 of 54 (efficiency group), wave 3 |
| Status | ready (shape: decision proposed) |
| Depends on | nothing; decide before a WebDAV/SFTP provider is built |
| Not in parallel with | P26 (`CloudProvider.kt`), P02, PR #877 (`FakeCloudProvider` fixture); same file family (take them one after the other, order in README): P26 |
| Status check | verified on main a0c6c4d3, 2026-10-14: partly fixed (Dropbox hash added in PR #868) |

## Problem
- [code] `HashAlgorithm` has `QuickXor`, `Md5Hex`, `Sha256Hex` and `DropboxContentHash` (added by PR #868); `Sha1Hex` (Nextcloud `oc:checksums`) is missing.
- [code] No mtime precision declaration: SFTP and FTP report whole seconds and FTP LIST may give only minutes in a server timezone; the reconciler compares `modified` exactly, so a provider that rounds churns (cf. #396).
- [code] No conditional-write capability: `Capability` lists Delta, DeltaShared, Webhook, Share, ListShares, RevokeShare, VerifyItem, QuotaExact, FastBootstrap. `ifMatchETag` is silently ignored by providers without it; emulations already exist undeclared (Dropbox `currentRev`, S3 `requireUnchanged`). Real: OneDrive, Dropbox `rev`, S3 with a flag, WebDAV `If-Match`; emulated and racy: SFTP/FTP stat-compare, Drive `version` pre-check.
- The issue says to agree these before the first new provider lands; Dropbox and S3 landed already, so the point is moot for them but still holds for WebDAV, SFTP and Google Drive (#841, #842, #845, not packaged).
- Exhaustive `when` over `HashAlgorithm` exists in `engine-core/.../HashVerifier.kt` (3 sites) and `cli/LegacyInventory.kt` (about line 546). About seven private `CloudProvider` stubs in hydration tests break on a signature change: prefer default methods.

## Scope
- Change: `HashAlgorithm.Sha1Hex` (plus verifier and inventory branches); `CloudProvider.mtimePrecisionMs(): Long` default 1 (declared by providers that round; `Reconciler` compares at that granularity); a conditional-write declaration (`Capability.ConditionalWrite` with two levels, or a `conditionalWrite(): ConditionalWrite` enum `NONE | EMULATED | REAL`; decision 1) declared by OneDrive, Dropbox and S3, and the engine logs once when it sends `ifMatchETag` to a provider that declares none.
- Must not change: existing providers' behaviour at the default values, the `ifMatchETag` parameter, the wire format.
- Out of scope: ranged download (P26), any new provider.

## Acceptance criteria
1. `HashVerifierTest`: a `Sha1Hex` value verifies a matching file and detects a mismatch; unknown algorithm still means "skip, not passed".
2. `ReconcilerTest` with `FakeCloudProvider` declaring `mtimePrecisionMs = 1000`: a remote mtime rounded to the second does not plan an upload/download for an unchanged file; at precision 1 the old behaviour holds.
3. `CapabilityContractTest` / `CloudProviderDefaultsTest`: defaults, and the declared level of OneDrive, Dropbox, S3, Internxt, localfs.
4. No change in any existing provider test (all default values keep behaviour).
5. `LegacyInventory` compiles with the new algorithm (exhaustive `when`).
Commands: `cd core && ./gradlew :app:core:test --tests '*CapabilityContractTest' --tests '*CloudProviderDefaultsTest' && ./gradlew :app:engine-core:test --tests '*HashVerifierTest' && ./gradlew :app:sync:test --tests '*ReconcilerTest' && ./gradlew :app:cli:test --tests '*LegacyInventory*'`.

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
1. Shape of the conditional-write declaration (a capability or an enum with three levels). Proposal: the enum.
2. mtime precision as milliseconds on the provider, or per-operation? Proposal: one number on the provider.

## How to verify live
No live check needed.
