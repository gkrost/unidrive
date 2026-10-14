# P46 S3: a hermetic MinIO suite and opt-in vendor runs

| | |
|---|---|
| Issues | gkrost/unidrive#869 (item 1; item 2, reading back `x-amz-meta-mtime`, is a design question left in the issue) |
| Labels | enhancement, kind:test |
| Size | M |
| Priority | rank 46 of 53 (tests group), wave 4 |
| Status | blocked on owner decision (new test dependency, Docker on CI) |
| Depends on | the owner decisions below |
| Not in parallel with | P27 (S3 mtime precision) |
| Status check | verified on main a0c6c4d3, 2026-10-14: still open; no Testcontainers or MinIO anywhere |

## Problem
- The S3 provider (PR #870) is tested against an in-memory fake (`FakeS3`, `S3ProviderTest`) and AWS's published SigV4 examples (`SigV4SignerTest`). Item 9 of #840 asked for a MinIO Testcontainer suite: delta (full and resumed), simple and multipart upload including resume after a cut-off part, part-copy move above 5 GiB (MinIO allows a sparse test object), batch delete, presigned GET, `If-Match` (MinIO supports it, so the AWS path can run hermetically). Opt-in runs on AWS and Scaleway are gated on `UNIDRIVE_INTEGRATION_TESTS` plus vendor keys, like `LiveGraphIntegrationTest`. On Scaleway to check: the 1,000-part limit, whether `If-Match` on PUT is honoured (it is off today), and that DeleteObjects needs `Content-MD5` (the provider already sends it, `S3Api` about line 339).
- [code] `S3Config.vendor` is a constructor parameter, so MinIO can run with `Vendor("aws", 10_000, true)` to exercise `If-Match`.
- Item 2 (not in this portion): uploads write the file mtime as `x-amz-meta-mtime` (rclone's format) but items report S3's `LastModified` because ListObjectsV2 carries no user metadata and the reconciler compares `modified` exactly; the real mtime needs a HEAD per object (too slow for big buckets) or an engine-side "content mtime" next to the change-detection time. Decide which first.
- Testcontainers is a NEW test dependency; the lane rules require the owner's approval for those, and CI needs Docker on the Linux leg (skip on the Windows leg).

## Scope
- Change: `core/providers/s3/build.gradle.kts`, the version catalog and lockfile (Testcontainers + MinIO image pinned by digest), a new `S3MinioIntegrationTest` (tagged, skipped without Docker) with the scenarios above, an opt-in `S3VendorIntegrationTest` (env-gated), a CI step on the Linux leg.
- Must not change: the fake-based unit tests, provider behaviour (findings from MinIO become separate fixes).
- Out of scope: item 2, other vendors' quirks beyond the three Scaleway checks.

## Acceptance criteria
1. On a machine without Docker the suite is skipped with a clear message and `check` stays green.
2. With Docker (Linux CI leg): every scenario of item 1 passes against MinIO, including resume after a cut-off part and part-copy above 5 GiB.
3. The opt-in vendor class is skipped unless `UNIDRIVE_INTEGRATION_TESTS` and keys are present; it contains the three Scaleway checks as assertions with comments.
4. The dependency additions are the only build changes; the lockfile is regenerated for them only (do not regenerate others).
Commands: `cd core && ./gradlew :providers:s3:test --tests '*S3ProviderTest' --tests '*S3MinioIntegrationTest'`.

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
1. Approve Testcontainers as a test dependency and Docker on the Linux CI leg.
2. Item 2: HEAD per object, or an engine-side content mtime? Not part of this portion.

## How to verify live
Owner (optional): run the vendor class with AWS and Scaleway test keys and attach the three Scaleway answers to the issue.
