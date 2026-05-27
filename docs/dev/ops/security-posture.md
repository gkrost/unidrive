# GitHub security posture — advisory audit

Read-only advisory audit of the repository's GitHub security configuration, taken
after the repo became public. **No GitHub setting and no code were changed.** This
document is recommendations only; acting on any item is a deliberate, separate decision.

This repo is a public, JVM (Kotlin) Gradle multi-module project: the UniDrive sync
core plus daemon, CLI, and provider modules (OneDrive, Internxt). It talks to cloud
providers over OAuth/HTTP and therefore handles credentials and tokens — integration
tests can run against real provider tokens, gated by `UNIDRIVE_INTEGRATION_TESTS`.
That credential-handling property is what shapes the priorities below.

## Headline: is `main` actually protected?

**Partially — and the gap is the load-bearing finding.** There is one repository
ruleset, `standard protection`, with `enforcement: active`. Its
`conditions.ref_name.include` is `["~DEFAULT_BRANCH"]` (non-empty — it does *not*
fall into the sibling repo's empty-include trap, where an active ruleset protected
zero branches). So the ruleset *does* apply to `main`. The problem is its rule set:
`gh api repos/.../rules/branches/main` returns exactly two rules —
`deletion` and `non_fast_forward`. There is **no `pull_request` rule and no
`required_status_checks` rule**, and classic branch protection returns 404 (none).

Net effect: `main` is protected against deletion and force-push, but **direct
un-reviewed pushes are allowed and CI does not gate merges.** The `build`/`check`
workflow runs on every push and PR and is green on `main` HEAD, but nothing
*requires* it to pass before code lands. For a public credential-handling project,
requiring a PR plus the `check` status check is the single highest-value change.

## Service-by-service

| Service | Current state | Recommendation | Rationale |
|---|---|---|---|
| Secret scanning | Enabled; 0 alerts | Keep on | Repo handles OAuth tokens and has live-token test fixtures; first line of defence against a committed credential. Already clean. |
| Secret scanning — push protection | Enabled | Keep on | Highest-value control here: blocks a token from ever reaching history at push time. Directly mitigates the `UNIDRIVE_TEST_ACCESS_TOKEN` / OAuth-fixture risk. |
| Secret scanning — non-provider patterns | Disabled | Leave disabled | Low signal-to-noise for a two-provider codebase; provider patterns already cover the real risk (OneDrive/Graph, Internxt). Revisit only if a custom credential format appears. |
| Secret scanning — validity checks | Disabled | Optional enable | Marks whether a leaked provider token is still live. Cheap, modestly useful given real tokens are in play; not urgent while there are zero alerts. |
| Dependabot alerts (vulnerability alerts) | Enabled (204) | Keep on | 9 historical Maven advisories, **all `fixed`** (okhttp, okio, org.json x2, log4j-core x3, plexus-utils) — the pipeline demonstrably works. |
| Dependabot security updates | Enabled | Keep on | Auto-opens fix PRs for the Gradle dependency tree; the JVM transitive surface (okhttp/okio/log4j/json) is exactly where the real risk sits. |
| Dependabot version updates (`dependabot.yml`) | Configured: `gradle` at `/core` + `github-actions` at `/` | Keep | Correctly scoped to the single live composite (`core/`, auto-discovering `gradle/libs.versions.toml`) and to action pins. Good. |
| CodeQL / code scanning | **Currently off.** `default-setup` reports `state: not-configured`. Historical default-setup analyses exist (last 2026-05-21) for `actions`, `python`, and `java-kotlin`, but no new analyses run. | **Re-enable, scoped to `java-kotlin`** | This is the biggest substantive gap, and it is the *opposite* of the Rust sibling's verdict: CodeQL's Java/Kotlin pack is mature and high-yield. See the dedicated note below. |
| Branch protection / rulesets on `main` | `standard protection` active, but only `deletion` + `non_fast_forward` | **Add a `pull_request` rule and a `required_status_checks` rule for `check`** | See headline. Closes the un-reviewed-direct-push and CI-bypass gap. |
| Actions — default `GITHUB_TOKEN` permissions | `read` (repo default) | Keep | Already least-privilege at the repo level. `build.yml` also pins `permissions: contents: read` at the workflow level — good belt-and-braces. |
| Actions — allowed actions | `all`; `sha_pinning_required: false` | Consider SHA-pinning the four third-party actions | `build.yml` uses `actions/checkout@v6`, `actions/setup-java@v5` (tag refs). Pinning to commit SHAs removes the mutable-tag supply-chain risk. Dependabot's `github-actions` updater already keeps pins fresh. Modest value; not urgent given read-only token. |
| Private vulnerability reporting | Enabled | Keep on | Gives outside researchers a private channel; appropriate now that the repo is public. |
| `SECURITY.md` | Absent | **Add a short one** | Public repo with no documented disclosure path. PVR is on but undiscoverable without a `SECURITY.md` pointing to it. Low effort, real value. |
| `CODEOWNERS` | Absent | Skip (low value here) | Single-maintainer repo; CODEOWNERS adds review-routing ceremony with no second reviewer to route to. Revisit if contributors grow. |
| Automated security fixes | `enabled`, not paused | Keep | Same machinery as Dependabot security updates; confirmed active. |

## CodeQL — the substantive gap (and why it differs from the Rust sibling)

The Rust sibling concluded CodeQL was low-yield and supply-chain scanning was the
primary control. **That verdict does not transfer.** For Kotlin/JVM, CodeQL's
`java-kotlin` analysis is mature and finds real classes of bug (injection, unsafe
deserialization, path traversal, SSRF in HTTP client code) — directly relevant to a
provider-talking, credential-handling codebase.

Two concrete problems with the current state:

1. **It is off now.** `default-setup` is `not-configured`; no analysis has run since
   2026-05-21. New code lands unscanned.
2. **Even when it ran, java-kotlin was not effectively analyzing the Kotlin.** The
   two historical `java-kotlin` analyses used `build-mode: autobuild` and produced
   **0 rules / 0 results** — the signature of an extraction that found no code,
   because the Gradle build root is `core/`, not the repo root, and autobuild from
   the root extracts nothing. The `actions` analysis ran clean (17 rules, 0 results);
   the `python` analysis failed outright (there is no Python here).

Recommendation: re-enable CodeQL scoped to `java-kotlin` only, and either point the
build at `core/` (default setup with the build configured for the subdirectory) or,
preferably, use **build-mode `none`** — GitHub's buildless Kotlin extraction avoids
the autobuild-from-wrong-root failure entirely and needs no Gradle invocation. Drop
the `python` and `actions` languages (no Python in the tree; `actions` scanning is
optional and was producing zero findings). The success criterion: a `java-kotlin`
analysis with a non-zero `rules_count` covering the `core/` Kotlin modules.

> Note on AGENTS.md "No CI policing." AGENTS.md states `./gradlew check` is the only
> gate and lists *semgrep, gitleaks, codecov, trivy, ktlint, version-drift jobs* as
> out of scope. CodeQL via GitHub's managed default setup is **not** a CI job in the
> repo's workflow surface — it runs on GitHub's scanning infrastructure, adds nothing
> to `build.yml`, and gates nothing unless a ruleset is configured to require it. It
> is therefore outside the spirit of that rule. This audit does **not** recommend
> OWASP dependency-check, Gradle dependency verification, or any third-party CI
> scanner: that supply-chain surface is already covered by Dependabot (alerts +
> security updates + version updates), which is enabled and demonstrably working
> (9 advisories resolved). Adding a CI-side scanner would duplicate Dependabot and
> directly violate the "No CI policing" rule.

## Gaps worth closing (highest value first)

1. **Require PR + `check` status check on `main`.** Add a `pull_request` rule and a
   `required_status_checks` rule (context: `check`) to the `standard protection`
   ruleset. Today both are absent, so un-reviewed direct pushes and CI-bypassing
   merges are possible on a public repo.
2. **Re-enable CodeQL for `java-kotlin`, build-mode `none`, scoped to `core/`.**
   Currently off; when it last ran, autobuild-from-root extracted zero Kotlin. This
   is the one scan that would actually look at the credential/HTTP/provider code.
3. **Add `SECURITY.md`.** Document the private-disclosure path (PVR is on but
   undiscoverable). One short file.
4. **(Optional, lower) SHA-pin the third-party actions** in `build.yml`
   (`actions/checkout`, `actions/setup-java`); Dependabot's `github-actions`
   updater already keeps pins current.
5. **(Optional) Enable secret-scanning validity checks** to flag whether any future
   leaked provider token is still live.

## Services judged low-value here (with rationale)

- **CODEOWNERS** — single-maintainer repo; no second reviewer to route to. Pure
  ceremony at this contributor scale.
- **Secret-scanning non-provider patterns** — noisy for a two-provider codebase; the
  provider patterns already cover the real OAuth-token risk.
- **Third-party CI supply-chain scanners** (OWASP dependency-check, Gradle dependency
  verification, gitleaks, trivy) — duplicate Dependabot, which is enabled and working,
  and are explicitly out of scope per AGENTS.md "No CI policing."
- **`actions` and `python` CodeQL languages** — no Python in the tree; `actions`
  scanning produced zero findings and adds noise. Scope CodeQL to `java-kotlin` only.

## GitHub MCP server — recommended toolsets for this repo's workflow

GitHub's official MCP server groups tools into named toolsets. For this repo:

| Toolset | Value here | Notes |
|---|---|---|
| `code_security` | **High** | CodeQL / code-scanning alert triage. Once CodeQL is re-enabled for `java-kotlin`, this is the toolset that surfaces and triages findings in the agent loop. Directly relevant — the opposite of the Rust sibling, where CodeQL was low-yield. |
| `secret_protection` | **High** | Secret-scanning alert access, plus the newer (GA May 2026) in-editor / pre-commit secret scanning. More relevant here than for the Rust sibling because this repo handles OAuth tokens and ships live-token test fixtures. |
| `dependabot` | **High** | Dependabot alert access and (public preview, May 2026) on-demand dependency vulnerability scanning against the Advisory Database — high-signal for the Gradle/Maven transitive tree (okhttp/okio/log4j/json). |
| `actions` | **Medium** | Inspect/re-run the `build` workflow from the agent loop. Useful but largely redundant with `gh run` from the CLI. |
| `repos`, `pull_requests`, `issues` | Redundant | Fully covered by the `gh` CLI already in use; no added value over `gh api` / `gh pr` / `gh issue`. |
| `security_advisories` | Low | Relevant only if this repo starts publishing its own advisories; not today. |

Bottom line: enable `code_security`, `secret_protection`, and `dependabot` (the three
security toolsets that have no clean `gh`-CLI equivalent for triage-in-the-loop); add
`actions` if workflow re-runs are wanted; skip the repo/PR/issue toolsets as redundant
with `gh`.

## Doc-location note (AGENTS.md deviation)

AGENTS.md bounds the documentation surface to: this file's siblings (`README.md`,
`BACKLOG.md`, `CLOSED.md`, `BOOSTERS.md`), ADRs under `docs/adr/`, audits under
`docs/audits/`, specs under `docs/dev/specs/`, and plans under `docs/dev/plans/`.
**`docs/dev/ops/` is not in that allowed list** — the convention-compliant home for
an audit like this is `docs/audits/`. This file was placed at `docs/dev/ops/` per the
audit's explicit instruction; flagging the deviation here. If aligning with AGENTS.md
is preferred, move this to `docs/audits/security-posture.md`.
