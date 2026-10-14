# P47 Tests: constructor seams instead of writing final fields by reflection (OneDrive, engine-core, hydration)

| | |
|---|---|
| Issues | gkrost/unidrive#507 (first part: every non-Internxt site; the 88 Internxt sites follow after the Internxt PRs have merged) |
| Labels | enhancement, sev:low, kind:test, lane:infra |
| Size | M (this part); L as a whole |
| Priority | rank 47 of 54 (tests group), wave 2 |
| Status | ready |
| Depends on | nothing for this part; PR #880 and the other Internxt portions merged for the second part |
| Not in parallel with | any portion that edits `OneDriveProvider` / `GraphApiService` tests; for the Internxt part every Internxt portion |
| Status check | verified on main a0c6c4d3, 2026-10-14: grown since the issue (128 sites in 46 test files, was 90 in 34) |

## Problem
- JEP 500 (JDK 26+): the first reflective write to a final field prints a three-line warning per test JVM, and a later JDK will refuse it. Tests write private final fields to inject a mock HTTP client or fake credentials.
- [code] Count today: 128 `getDeclaredField` sites in 46 test files: `providers/internxt` 88, `providers/onedrive` 36, `engine-core` 3 (`StateDatabaseTest`), `hydration` 1 (`HydrationOpenSetConcurrencyTest`). By field: `httpClient` 44, `credentials` 20, `authService` 20, `api` 20, `graphApi` 12, `rest` 12.
- [code] The seam already exists in places: `InternxtApiService(... httpClient: HttpClient = defaultHttpClient(...))` (PR #505), `GraphApiService(config, throttleBudget, engine: HttpClientEngine?, tokenProvider)`. Yet `InternxtApiServiceTest` still reflects into `httpClient` (about lines 531, 602, 1044). `InternxtProvider(config)` and `OneDriveProvider(config)` have no `api` / `authService` / `graphApi` seam.
- The stopgap is `--enable-final-field-mutation=ALL-UNNAMED` for test JVMs on JDK 26+ (`core/build.gradle.kts` about line 305, with a comment promising the follow-up). The `--illegal-final-field-mutation=deny` leg that would prove the cleanup (audit #628) does not exist.

## Scope
- This part: give `OneDriveProvider` an optional `graphApi: GraphApiService` (and credentials/token seam) constructor parameter with the production default; convert the 36 OneDrive sites to it and to `GraphApiService(engine = MockEngine)`; replace the 3 `StateDatabaseTest` and the 1 hydration site by a visible constructor/test hook; keep each conversion class by class; add a Gradle test leg or a documented property that runs one module with `--illegal-final-field-mutation=deny` (first for the converted modules).
- Second part (later, after the Internxt portions merged): `InternxtProvider(api, authService)`, `InternxtAuthService(credentials)`, drop the remaining `httpClient` reflection.
- Must not change: production behaviour (defaults identical), test assertions (only how objects are built).
- Out of scope: the Internxt sites in this part.

## Acceptance criteria
1. `grep -rn getDeclaredField core/providers/onedrive core/app` finds no hit in test sources after the change (Internxt untouched).
2. The converted modules' tests pass on the runtime-JDK leg with `--illegal-final-field-mutation=deny` (add the leg or a Gradle property and run it once; paste the result).
3. No production file changes other than adding defaulted constructor parameters.
4. Detekt baselines unchanged.
Commands: `cd core && ./gradlew :providers:onedrive:test :app:engine-core:test --tests '*StateDatabaseTest' :app:hydration:test --tests '*HydrationOpenSetConcurrencyTest'` (the OneDrive module's whole test task is the point; it is the only full-module run this portion asks for, and CI runs the rest).

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
1. A permanent `deny` leg in CI, or a one-off proof? Proposal: a Gradle property used by one CI job.

## How to verify live
No live check needed.
