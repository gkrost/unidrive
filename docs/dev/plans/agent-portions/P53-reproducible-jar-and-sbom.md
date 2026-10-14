# P53 Release engineering: a reproducible shadowJar and an SBOM

| | |
|---|---|
| Issues | gkrost/unidrive#339 |
| Labels | enhancement, sev:low, area:dist, kind:reliability, lane:infra |
| Size | S for the flags, M with the build-time fix and the SBOM |
| Priority | rank 53 of 53 (release group), wave 4 |
| Status | ready for the flags, blocked on owner decision for the SBOM |
| Depends on | the owner decisions below |
| Not in parallel with | P51, P52 (`core/app/cli/build.gradle.kts`), release workflow changes; same file family (take them one after the other, order in README): P51, P52 |
| Status check | verified on main a0c6c4d3, 2026-10-14: still open, with one more blocker than the issue names |

## Problem
- [code] `tasks.shadowJar` (`core/app/cli/build.gradle.kts` about lines 212-221) has `mergeServiceFiles()` and `duplicatesStrategy` only: no `isPreserveFileTimestamps = false`, no `isReproducibleFileOrder = true`, and no CycloneDX/SPDX plugin anywhere. The release v0.0.1 (2026-10-10) shipped the jar, `.asc`, `.sha256` and `.sha256.asc` only; the generated `THIRD-PARTY-NOTICES` (`core/gradle/notices.gradle.kts`) is not an SBOM.
- [code] The build is not reproducible by itself: `generateBuildInfo` writes `BUILD_INSTANT = Instant.now()` (`build.gradle.kts` about lines 162 and 194), used at `Main.kt` (about line 928) and asserted by `BuildInfoTest`. Two builds of one commit differ in that class even with the flags set.
- Impact: the signed artifact cannot be byte-reproduced independently and downstream scanners get no dependency manifest. Cheap to fix before checksums are widely used; painful after.

## Scope
- Change: step 1 (no decision needed): the two reproducible-archive flags on `shadowJar`. Step 2 (decision 1): the build instant from the commit time or `SOURCE_DATE_EPOCH` instead of `Instant.now()` (a dirty build keeps the wall clock and says so). Step 3 (decision 2): a CycloneDX Gradle plugin (a new dependency: owner approval) producing an SBOM uploaded beside the release artifacts in `release.yml`; a build-twice-and-compare step in the workflow.
- Must not change: the contents of the jar other than ordering/timestamps/the instant, the signing step, artifact names.
- Out of scope: the jlink image (not shipped yet), packaging for deb/rpm.

## Acceptance criteria
1. Two `shadowJar` builds of one clean commit produce byte-identical jars (compare SHA-256; paste both hashes in the PR body).
2. `BuildInfoTest` is updated to the new instant rule and passes; a dirty build still reports its build time.
3. If step 3 is approved: the SBOM file is produced by a Gradle task and listed in `release.yml`; it names every runtime dependency of the fat jar with versions.
4. The release workflow runs the build-twice comparison and fails on a difference.
Commands: `cd core && ./gradlew :app:cli:test --tests '*BuildInfoTest' && ./gradlew :app:cli:shadowJar` (twice, on a clean tree, for the comparison).

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
1. `BUILD_INSTANT` source: the commit timestamp, or `SOURCE_DATE_EPOCH`?
2. Approve a new SBOM Gradle plugin (lane rules require the owner's approval for new dependencies).

## How to verify live
The owner can compare a CI-built jar with a locally built one of the same commit (hash equality).
