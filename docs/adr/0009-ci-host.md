---
id: ADR-0009
title: CI host — commit to GitHub Actions
status: accepted
date: 2026-04-18
---

## Context

[ADR-0008](0008-greenfield-restart.md) parked the git-host / CI-host question as non-blocking. During the greenfield push this was already partially answered by running work: [UD-705](../backlog/CLOSED.md) shipped a `.github/workflows/build.yml` (Linux + Windows matrix, Gradle build/test, jacocoMergedReport, gitleaks), and [UD-708](../backlog/CLOSED.md) layered a `docker-integration` job on top. Both have been green against the local repo (the tree is also not yet pushed to a remote — see UD-701 follow-ups) and `actionlint` validates.

At the same time, `scripts/ci/*.sh` still carries a host-neutral framing in [scripts/ci/README.md](../../scripts/ci/README.md) ("Once UD-701 lands, the chosen CI system translates these into its native format") and several backlog items ([UD-110](../backlog/BACKLOG.md#ud-110), [UD-701](../backlog/BACKLOG.md#ud-701)) are explicitly `status: blocked` waiting on this choice. The `scripts/ci/*.sh` fragments remain useful as **local shortcuts** regardless of host choice — they stay.

Ongoing host-neutrality costs effort (no host-specific features used; every decision re-abstracted) and delays unblocking UD-110 (Dependabot/Renovate — both are GitHub-native). A decision is now cheaper than continued neutrality.

## Decision

**GitHub Actions is the CI host for this monorepo.** GitHub is the git host.

- Pipelines live in `.github/workflows/`. No parallel GitLab CI / Woodpecker / Jenkins config is maintained.
- `scripts/ci/*.sh` remains host-neutral as a **local-development convenience**, not as a portability guarantee. CI workflows invoke them directly where useful.
- GitHub-native capabilities are fair game: Dependabot (UD-110), CodeQL, GITHUB_TOKEN scopes, reusable workflows, `actions/cache`, etc.

## Consequences

### Positive

- Unblocks [UD-110](../backlog/BACKLOG.md#ud-110) (Dependabot) immediately — no second code path needed.
- Unblocks [UD-701](../backlog/BACKLOG.md#ud-701) itself — it can close, since the "realize scripts/ci/" portion already shipped under UD-705/UD-708.
- [scripts/ci/README.md](../../scripts/ci/README.md) drops the "host TBD" caveat and honestly describes what these fragments are for (local reproduction).
- Future workflow work (CodeQL, dependency review, matrix caching, preview deploys) can use native features directly.

### Negative / trade-offs

- **Host lock-in.** Moving off GitHub later requires rewriting workflows plus re-homing issues/PRs. Acceptable: the project is solo + OSS-track; maintenance cost of dual-host support would dwarf the one-time migration if the host ever needs to change.
- **`actionlint` is the only safety net** on workflow syntax (no `.gitlab-ci.yml` cross-check). Pre-existing; nothing changes.

### Neutral

- Cost: free tier covers the foreseeable compute budget for a solo OSS project. Windows runner minutes count 2× but the Windows job stays cheap (one `gradlew build test`).

## Alternatives considered

- **GitLab CI.** `.gitlab-ci.yml` is a natural fit for the existing `scripts/ci/*.sh` shell-fragment style, and GitLab's container registry is free. Rejected: no existing infra, Dependabot-equivalent (Renovate) adds a second moving part, and the developer's prior work is on GitHub — switching costs social context with no clear upside.
- **Woodpecker / self-hosted.** Rejected: single-maintainer project; cost of running the infra exceeds cost of using hosted CI.
- **Keep host-neutral indefinitely, maintain both GitHub + GitLab configs.** Rejected: every decision gets abstracted twice, UD-110 stays blocked forever, and there is no concrete plan to ever use the GitLab path — it would be speculative portability.

## Related

- Backlog: [UD-701](../backlog/BACKLOG.md#ud-701) (this ADR closes it), [UD-110](../backlog/BACKLOG.md#ud-110) (unblocks), [UD-705](../backlog/CLOSED.md) (already-landed GH Actions workflow), [UD-708](../backlog/CLOSED.md) (docker-integration job).
- [ADR-0008](0008-greenfield-restart.md) open question 2 — closed by this ADR.
- Supersedes / superseded by: —
