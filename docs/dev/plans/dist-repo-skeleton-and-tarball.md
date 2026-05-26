# Plan P4a: unidrive-dist Repo Skeleton + Tarball Channel + fetch-artefacts

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Cross-repo note:** This plan creates a **new** GitHub repo, `unidrive-dist`. The plan file lives in `unidrive/docs/dev/plans/` (the sanctioned home for distribution-spec plans). The implementer reads this plan from `unidrive/` but commits the implementation to the freshly-created `unidrive-dist/` repo.

**Goal:** Create the `unidrive-dist` repo with its AGENTS.md / BACKLOG.md / CLOSED.md / LICENSE / NOTICE / README.md / RELEASES.md scaffolding, the tarball packaging channel, the `fetch-artefacts.sh` script that pulls JAR + Rust tarballs from sibling GH Releases, and the `test/local-build.sh` dev-loop driver. Deb / Rpm / AUR channels and the release-orchestration scripts land in Plan P4b.

**Architecture:** A pure-packaging repo with no JVM or Rust source code. The first channel — tarball — bundles the JVM JAR + per-arch Rust binary + LICENSE + NOTICE + `install.sh` + `unidrive.service` into a per-arch `.tar.gz` with a signed `SHA256SUMS`. The `fetch-artefacts.sh` script uses the GitHub API to find the latest sibling-repo release at or before a given SemVer, downloads each artefact, and verifies SHA256 + GPG signature.

**Tech Stack:** Bash, `gh` CLI (for GH API queries), `gpg`, `tar`, `sha256sum`, `curl`, `jq`. No JVM, no Rust, no Docker (Docker arrives in P4b for the smoke matrix).

**Spec reference:** `unidrive/docs/dev/specs/unidrive-distribution-design.md` §2.2 (justification for a third repo), §4.1 (tarball channel), §4.6 (cross-channel concerns), §4.7 (RELEASES.md changelog strategy), §6 (repo layout), §6.1 (AGENTS.md version exception), §10 AC #3 (tarball bundle in GH Release).

**Scope explicitly NOT in this plan:**

- No deb, rpm, or AUR recipes (Plan P4b).
- No `release/release.sh` orchestration (Plan P4b).
- No Docker smoke tests for the channel packages (Plan P4b).
- No first-release content in `RELEASES.md` (Plan P5).
- No `apt.unidrive.krost.org` / `dnf.unidrive.krost.org` content (Plan P5).

**Pre-flight assumptions:**

- Plan P3 has been executed: `pkg-server` and `pkg-signer` exist on krost-infra; the GPG public key is at `unidrive.krost.org/install/unidrive-releases.gpg`; the maintainer has the GPG fingerprint.
- Plans P1 and P2 have been executed and merged. (Their workflows do not need to have fired yet — Plan P5 cuts the first real tags. P4a uses local builds for testing.)
- The maintainer has admin rights on the gkrost GitHub organization (to create the new repo).
- `gh` CLI is installed and authenticated locally (`gh auth status` returns logged in).
- Sibling-repo checkouts exist at `/home/gernot/dev/git/unidrive` and `/home/gernot/dev/git/unidrive-mount-linux`.

---

## File Structure

All paths are in the new `unidrive-dist` repo.

| Path | Purpose | Plan |
|------|---------|------|
| `README.md` | What this repo is, for humans | P4a |
| `AGENTS.md` | Rulebook for agents (version-exception clause, sibling-checkout assumption) | P4a |
| `BACKLOG.md` | Work queue (seeded with the design-constraint deferrals from spec §9) | P4a |
| `CLOSED.md` | Empty header; populated as work lands | P4a |
| `RELEASES.md` | Changelog source of truth (empty until P5) | P4a |
| `LICENSE` | Apache-2.0 (copied from sibling repos) | P4a |
| `NOTICE` | Per Apache-2.0 conventions | P4a |
| `.gitignore` | Standard | P4a |
| `release/fetch-artefacts.sh` | Pull + verify sibling-repo artefacts | P4a |
| `packaging/tarball/build-tarball.sh` | Stage + tar the per-arch bundle | P4a |
| `packaging/tarball/install.sh` | End-user installer (migrated from `unidrive/dist/install.sh`) | P4a |
| `packaging/tarball/uninstall.sh` | End-user uninstaller | P4a |
| `packaging/tarball/unidrive.service` | systemd --user unit | P4a |
| `test/local-build.sh` | Dev loop driver | P4a |
| `docs/release-process.md` | Skeleton (filled in by P5) | P4a |
| `docs/compatibility.md` | Supported distro / kernel / libfuse / glibc matrix | P4a |
| `docs/adr/0001-version-coupling.md` | ADR capturing the §3.3/§3.4 conventions | P4a |
| `packaging/deb/`, `packaging/rpm/`, `packaging/aur/` | Channel recipes | **P4b** |
| `release/{sign-packages,publish-*,release}.sh` | Orchestration scripts | **P4b** |
| `test/{deb,rpm,aur,tarball}-install-in-docker.sh` | Smoke matrix | **P4b** |
| `test/smoke-all.sh` | The gate | **P4b** |
| `.github/workflows/{release,test-packaging}.yml` | CI | **P4b** |
| `repo-server/` (config destined for krost-infra) | Defer to P5 (already provisioned in P3) | — |

---

## Task 0: Create the GitHub repo `[MAINTAINER]`

**Files:** none yet

- [ ] **Step 1: Create the repo on GitHub**

Run:

```bash
gh repo create gkrost/unidrive-dist \
    --public \
    --description "UniDrive distribution & release engineering — apt, dnf, AUR, tarball packaging."
```

Expected: repo created. URL printed.

If `gh repo create` errors with "already exists" — the repo was set up earlier. Proceed.

- [ ] **Step 2: Clone the empty repo locally**

Run:

```bash
mkdir -p /home/gernot/dev/git
cd /home/gernot/dev/git
git clone git@github.com:gkrost/unidrive-dist.git
cd unidrive-dist
```

Expected: empty clone. The default branch may not exist yet (GitHub waits for the first push).

- [ ] **Step 3: Verify the sibling checkouts are where this plan expects them**

Run:

```bash
ls /home/gernot/dev/git/unidrive/core/build.gradle.kts \
   /home/gernot/dev/git/unidrive-mount-linux/Cargo.toml
```

Expected: both files exist.

- [ ] **Step 4: Defer the `DIST_DEPLOY_KEY` and `SIGNER_DEPLOY_KEY` GH secrets to the end of this task**

Plan P3 Task 8 Step 6 and Task 9 Step 3 deferred these GH-secret pastes until the unidrive-dist repo existed. The repo now exists. **If you still have the two private SSH keys** in a temporary file from P3, paste them now into:

- https://github.com/gkrost/unidrive-dist/settings/secrets/actions → `DIST_DEPLOY_KEY`
- https://github.com/gkrost/unidrive-dist/settings/secrets/actions → `SIGNER_DEPLOY_KEY`

If you no longer have them, regenerate per P3 Task 8 Steps 4–7 (`pkg-publisher`) and Task 9 Steps 1–4 (`signer`). The public keys on the VPS still match — only the private parts need to be re-pasted to GH if you re-keyed.

**Also add `GPG_PRIVATE_KEY` and `GPG_KEY_FINGERPRINT`** — the same values pasted to unidrive and unidrive-mount-linux in P3 Task 7. These are needed by the release CI in Plan P4b.

**Also add `AUR_BOT_KEY`** if the AUR bot account has been provisioned per spec §4.4. If not, this secret is created in Plan P4b (which adds the AUR channel and the bot-account setup); for now defer.

---

## Task 1: Scaffolding — LICENSE, NOTICE, .gitignore

**Files:**
- Create: `LICENSE`
- Create: `NOTICE`
- Create: `.gitignore`

- [ ] **Step 1: Copy LICENSE from a sibling repo**

The two sibling repos already carry Apache-2.0 LICENSE files. Use either:

```bash
cd /home/gernot/dev/git/unidrive-dist
cp /home/gernot/dev/git/unidrive/LICENSE ./LICENSE
ls -la LICENSE
head -5 LICENSE
```

Expected: file exists, starts with `Apache License`.

- [ ] **Step 2: Copy NOTICE**

```bash
cp /home/gernot/dev/git/unidrive/NOTICE ./NOTICE
cat NOTICE
```

Expected: short attribution.

- [ ] **Step 3: Write `.gitignore`**

Create `.gitignore`:

```
# Staging directories produced by build scripts.
staging/
build/
dist-output/

# Downloaded sibling artefacts (fetched by release/fetch-artefacts.sh).
artefacts/

# Local secrets that mustn't reach the repo.
*.gpg.secret
*.private
secrets/

# Editor scratch.
.idea/
.vscode/
*.swp
*~
```

- [ ] **Step 4: First commit (just the scaffolding files)**

```bash
git add LICENSE NOTICE .gitignore
git commit -m "chore: initial scaffolding — LICENSE, NOTICE, .gitignore"
```

---

## Task 2: README.md

**Files:**
- Create: `README.md`

- [ ] **Step 1: Write the README**

Create `README.md`:

```markdown
# `unidrive-dist`

Distribution & release engineering for [UniDrive](https://unidrive.krost.org).

This repo packages and publishes the JVM CLI (built by
[`unidrive`](https://github.com/gkrost/unidrive)) and the Rust FUSE
co-daemon (built by
[`unidrive-mount-linux`](https://github.com/gkrost/unidrive-mount-linux))
as `.deb`, `.rpm`, AUR `PKGBUILD`, and end-user tarball, signs them
with the `unidrive-releases` GPG key, and publishes them via:

- `apt.unidrive.krost.org` — Debian / Ubuntu apt repository.
- `dnf.unidrive.krost.org` — Fedora / RHEL dnf repository.
- [AUR `unidrive`](https://aur.archlinux.org/packages/unidrive) — Arch Linux.
- GitHub Releases on this repo — end-user tarball.

This repo contains **no JVM or Rust source code**. It only packages.

## Repo layout

```
unidrive-dist/
├── packaging/             # recipes per channel
│   ├── deb/, rpm/, aur/, tarball/
├── release/               # orchestration scripts
│   ├── fetch-artefacts.sh, release.sh, publish-*.sh, sign-packages.sh
├── repo-server/           # config destined for krost-infra (see notes below)
├── docs/                  # release-process, compatibility, ADRs
├── test/                  # Docker smoke matrix (one container per channel)
├── RELEASES.md            # single source of truth for changelog
├── BACKLOG.md, CLOSED.md  # work queue
└── AGENTS.md              # rulebook for agents working in this repo
```

## Sibling-checkout convention

Several scripts (`test/local-build.sh`, the dev-loop entry point) expect
the two sibling repos to be checked out as **siblings of this repo**:

```
your-dev-dir/
├── unidrive/                 ← JVM repo
├── unidrive-mount-linux/     ← Rust co-daemon repo
└── unidrive-dist/            ← THIS REPO
```

A different layout is supported by passing explicit paths to
`local-build.sh`, but the docs assume the sibling layout.

## How to cut a release

See [`docs/release-process.md`](docs/release-process.md).

The user-visible version (e.g. `0.0.1`) is coordinated across all three
repos. Sibling repos tag only when their own source changes; this repo's
tag is authoritative for what a release contains. See the spec §3.3 and
§3.4 (path below) for the full convention.

## Where the spec lives

The distribution design spec lives in the `unidrive` repo at
[`docs/dev/specs/unidrive-distribution-design.md`](https://github.com/gkrost/unidrive/blob/main/docs/dev/specs/unidrive-distribution-design.md).

That spec governs this repo and is referenced extensively from
`AGENTS.md` and `docs/release-process.md`.

## License

Apache-2.0. See [LICENSE](LICENSE), [NOTICE](NOTICE).
```

- [ ] **Step 2: Commit**

```bash
git add README.md
git commit -m "docs: README explaining what unidrive-dist is and isn't"
```

---

## Task 3: AGENTS.md (with the version-exception clause)

**Files:**
- Create: `AGENTS.md`

- [ ] **Step 1: Write the AGENTS.md**

Create `AGENTS.md`. The full text follows; the spec §6.1 mandates the explicit version-exception clause.

```markdown
# Agent instructions

This file is the rulebook for everyone touching the repo — human
contributors and LLM agents alike. End-users land on `README.md`; the
moment you want to *change* something, you read this file.

`unidrive-dist` is the distribution / release-engineering tier for
UniDrive. It packages artefacts produced by the sibling repos
([`unidrive`](https://github.com/gkrost/unidrive),
[`unidrive-mount-linux`](https://github.com/gkrost/unidrive-mount-linux))
and publishes them as `.deb` / `.rpm` / AUR / tarball.

The design spec governing this repo lives in the **sibling `unidrive`**
repo at `docs/dev/specs/unidrive-distribution-design.md`. Reference it
by section (e.g. "per spec §3.4") in commits and BACKLOG entries.

## Hard rules

- **No JVM or Rust source code.** This repo packages; it does not build
  the things being packaged. New code here belongs in one of the sibling
  repos.
- **Pre-built artefacts only.** `release/fetch-artefacts.sh` downloads
  the JAR and Rust tarballs from the sibling repos' GitHub Releases.
  We never re-build them locally or in CI.
- **The signing key never reaches this repo's CI runner.** Signing
  happens via SSH to the `pkg-signer` container on the krost-infra VPS
  (per spec §5). The key is on the VPS only.
- **`test/smoke-all.sh` is the gate.** No coverage tool, no lint
  baseline. Per spec §8.1.
- **No IDs or dates in commit messages, BACKLOG entries, or filenames.**
  Same rule as the sibling repos.

## Versions — the explicit exception

The sibling repos forbid version numbers in commits / filenames /
document body. **This repo is the explicit exception** to that rule,
because its job is releases and releases carry versions. The exception
is **bounded**:

- Versions are permitted in release artefact filenames, GH Release pages,
  and the `debian/changelog` and `Version:` fields that distro tooling
  requires.
- Versions are permitted in `RELEASES.md` and in `docs/release-process.md`
  when documenting past releases or example workflows.
- Versions are permitted in tag names (e.g. `v0.0.1`, `v0.0.1-pkg2`).
- Source-code commit messages and `BACKLOG.md` entries still don't
  mention versions. Describe work, not releases.

## How to work

1. Read the top of `BACKLOG.md`. Pick the first item that isn't blocked.
2. Read three nearby files before writing. The existing patterns are
   the style guide.
3. **Pre-execution sanity check.** Spec-changing work belongs in the
   `unidrive` repo, not here. If a BACKLOG item points at something
   that requires editing the spec, file it in the sibling repo's
   BACKLOG and stop.
4. Make the change. Run `test/smoke-all.sh`. Iterate.
5. Move the item from `BACKLOG.md` to `CLOSED.md` in the same commit.
6. New discovered work → `BACKLOG.md`.

## Sibling-checkout assumption

`test/local-build.sh` and other dev-loop scripts assume the sibling
repos live at `../unidrive` and `../unidrive-mount-linux`. The scripts
accept explicit paths, but the docs and CI assume the sibling layout.

## What lives where

- `packaging/<channel>/` — one subdirectory per channel (deb, rpm, aur,
  tarball), each self-contained.
- `release/` — orchestration scripts. `release/release.sh` is the
  top-level orchestrator; the others are leaves it calls.
- `repo-server/` — config that *describes* the krost-infra apt/dnf
  server. The server itself runs in krost-infra (provisioned by
  `unidrive/docs/dev/plans/krost-infra-pkg-server-and-signer.md`).
- `docs/adr/` — architectural decisions. The first one captures the
  version-coupling rule.
- `docs/release-process.md` — operator runbook.
- `docs/compatibility.md` — supported-distro matrix.
- `test/` — Docker smoke matrix.

## Commit etiquette

- Conventional Commits.
- One BACKLOG item per commit when possible.
- **Splitting allowed**: a single release-cutting commit may touch
  `RELEASES.md`, tag, and any per-release tweaks — that's fine.

## What not to do

- **Don't put source code here.** Even small helper utilities. The
  `release/` scripts are pure bash + standard CLI tools.
- **Don't add a JDK or Rust toolchain to CI.** The artefact-fetching
  CI is shell + `gh` + `gpg` + packaging tools only.
- **Don't sign anything in CI.** All signing goes through `pkg-signer`.
- **Don't bypass the spec.** A new channel, a new threat-model
  assumption, a different signing model — those start with a spec
  amendment, not a packaging change.
```

- [ ] **Step 2: Commit**

```bash
git add AGENTS.md
git commit -m "docs: AGENTS.md — rulebook + version-exception clause"
```

---

## Task 4: BACKLOG.md, CLOSED.md, RELEASES.md scaffolding

**Files:**
- Create: `BACKLOG.md`
- Create: `CLOSED.md`
- Create: `RELEASES.md`

- [ ] **Step 1: Seed `BACKLOG.md` from the spec §9 deferrals**

Create `BACKLOG.md`:

```markdown
# Backlog

> One-line items. Move to `CLOSED.md` in the same commit that lands
> the work.

## Now

- (nothing — scaffolding lands first; see `docs/dev/plans/` in the
  sibling unidrive repo for the sequence)

## Soon

- (empty)

## Design constraints (not tickets)

These are spec-level constraints that bind future work; they don't
appear on the work queue. Per the sibling repos' "Design constraints"
convention.

- **Snap / Flatpak channels.** Deferred per spec §4.5 and §9.
  Re-evaluate on (a) funded effort, (b) specific customer, or (c)
  community contributor proposing to own the channel.
- **Reproducible builds.** Rust tarballs aren't byte-reproducible.
  Achievable but expensive; defer.
- **Multi-arch beyond amd64/arm64.** No riscv64, no armv7l.
- **`--version` build-info enrichment.** Per spec §3.5.
- **In-app auto-update.** Each channel uses its native one.
- **Telemetry-less download counters.** GH Releases counts +
  apt/dnf access-log aggregates only.
- **Hardware-backed signing key (HSM / YubiKey).** §5 puts the
  key on a krost-infra container volume; HW-backed is a follow-up.
- **Man page** (`/usr/share/man/man1/unidrive.1.gz`). CLI `--help`
  is canonical for MVP.
- **Staging vs production repos.** Single-environment for MVP.
- **`--enable-native-access` JDK shelf life.** Pinned to JDK 21
  for MVP. When a target distro defaults to JDK 23+, the wrapper
  script must detect `java --version` and select the correct flag
  form. Per spec §4.6.
- **AUR push-collision recovery.** If a concurrent push races,
  `release/publish-aur.sh` must `git pull --rebase && re-generate`
  before retrying. Document in `docs/release-process.md`.
```

- [ ] **Step 2: Create empty `CLOSED.md`**

```markdown
# Closed

> Done work, newest first. Move items here from `BACKLOG.md` in the
> same commit that lands the work.
```

- [ ] **Step 3: Create empty `RELEASES.md`**

Per spec §4.7, this is the single source of truth for the deb/rpm
`%changelog` content. Empty until P5 cuts the first release.

```markdown
# Releases

> Single source of truth for changelog content across all channels.
> See spec §4.7 for the contract. Format below.
>
> ```
> ## 0.0.1 — first MVP
>
> ### unidrive (JVM)
> - <bullet sourced from unidrive/CLOSED.md>
>
> ### unidrive-mount-linux (FUSE co-daemon)
> - <bullet sourced from unidrive-mount-linux/CLOSED.md>
>
> ### Packaging
> - <bullet sourced from THIS repo's CLOSED.md>
> ```
>
> Maintainer identity for distro-format conversion:
> `UniDrive Releases <releases@unidrive.krost.org>`.

<!-- Releases below this line. Newest first. -->
```

- [ ] **Step 4: Commit**

```bash
git add BACKLOG.md CLOSED.md RELEASES.md
git commit -m "docs: BACKLOG / CLOSED / RELEASES scaffolding"
```

---

## Task 5: First docs/ files — release-process skeleton, compatibility, ADR

**Files:**
- Create: `docs/release-process.md`
- Create: `docs/compatibility.md`
- Create: `docs/adr/0001-version-coupling.md`

- [ ] **Step 1: `docs/release-process.md` skeleton**

Create `docs/release-process.md` with placeholders that P5 will fill:

```markdown
# Release process

Operator runbook for cutting a UniDrive release.

## Pre-flight

(filled by Plan P5 — the first-release cutover plan)

## Cutting a release

(filled by Plan P5)

## Rollback playbook

Per spec §3.6. (filled by P5 after the first real rollback is
documented; for MVP, the playbook in the spec is canonical.)

## Key rotation

Per spec §3.7 (GPG) and §7.5 (SSH deploy keys). (Cross-references
only; no operator steps yet.)
```

- [ ] **Step 2: `docs/compatibility.md`**

Create `docs/compatibility.md`:

```markdown
# Supported platforms

Per spec §4.2, §4.3, §4.4, §4.5.

## Channel × distro matrix

| Channel | Distro | Status |
|---|---|---|
| APT | Ubuntu 24.04 LTS | Supported |
| APT | Ubuntu 26.04 | Supported once GA |
| APT | Debian 12 (bookworm) | Supported |
| APT | Debian 13 (trixie) | Supported |
| DNF | Fedora 40+ | Supported |
| DNF | RHEL 10 | Supported (kernel 6.12 ≥ 6.9 floor) |
| DNF | RHEL 9 | **Unsupported** (kernel 5.14 < 6.9 floor; rpm installs but unidrive-mount exits 78) |
| DNF | AlmaLinux / Rocky 10+ | Supported |
| AUR | Arch Linux (any) | Supported |
| AUR | Manjaro, EndeavourOS, etc. | Best-effort (downstream of Arch) |
| Tarball | Any glibc Linux with kernel ≥ 6.9 + libfuse ≥ 3.16 | Supported |
| Snap / Flatpak | — | **Not in MVP**; see spec §4.5 |

## Runtime requirements

- Linux kernel ≥ 6.9 (hard floor; enforced at runtime by `unidrive-mount`).
- libfuse ≥ 3.16 (hard floor).
- Java 21 runtime (`java-runtime-headless (>= 21)` for deb;
  `java-21-openjdk-headless` for rpm; `java-runtime>=21` for AUR).
- systemd (user mode) for the service unit.
- Architecture: `x86_64` or `aarch64`.

## Out-of-scope architectures

`riscv64`, `armv7l`, `i686`. The JVM JAR is arch-independent and would
run; the Rust binary isn't shipped for them.
```

- [ ] **Step 3: `docs/adr/0001-version-coupling.md`**

Create `docs/adr/` and the first ADR:

```bash
mkdir -p docs/adr
```

`docs/adr/0001-version-coupling.md`:

```markdown
# ADR 0001: Version coupling across the three repos

## Status

Accepted.

## Context

UniDrive ships from three repos: `unidrive` (JVM CLI), `unidrive-mount-linux`
(Rust FUSE co-daemon), and `unidrive-dist` (this repo, packaging). The
question is how their versions relate.

## Decision

A single user-visible coordinated SemVer (e.g. `0.0.1`) across all three
repos. **Sibling repos tag only when their own source changes for a
given release.** `unidrive-dist`'s tag is authoritative — it defines
what a release contains.

`fetch-artefacts.sh` (in `release/`) queries each sibling's GH Releases
for the **latest release at or before** the dist tag's SemVer. So if
unidrive sits at `v0.0.1` and unidrive-mount-linux sits at `v0.0.1` and
unidrive-dist tags `v0.0.2` (a packaging-only fix), the fetcher pulls
the existing `0.0.1` JAR + Rust tarball and packages them as `0.0.2`.

Packaging-only respins use the `v0.0.1-pkg2`, `v0.0.1-pkg3` tag suffix
on this repo. The user-visible version stays `0.0.1`; distro-native
revision strings (`Release: 2`, `pkgrel=2`, deb `-2`) carry the respin
counter.

## Consequences

**Pro:** the convention preserves the "one user-visible version
everywhere" property without forcing empty-payload tags on sibling
repos when their source hasn't changed.

**Con:** the `fetch-artefacts.sh` parse rule for `vX.Y.Z[-pkgN]` is
correctness-critical and is spelled out in spec §3.4.

## Alternatives considered

- **Forced lockstep:** all three repos tag the same version every
  release, even when source hasn't changed. Rejected per spec
  review-round-2 §2 — re-issuing a tag on byte-identical content
  under a different name violates the "one tag = one content"
  assumption.
- **Fully independent versions:** each repo has its own SemVer, with
  a compatibility matrix published. Rejected as user-hostile.

## References

- Spec §3.2, §3.3, §3.4, §3.5.
- Spec review round 2, item #2.
```

- [ ] **Step 4: Commit**

```bash
git add docs/release-process.md docs/compatibility.md docs/adr/0001-version-coupling.md
git commit -m "docs: release-process skeleton, compatibility matrix, ADR 0001"
```

---

## Task 6: First push to GitHub

**Files:** none changed

- [ ] **Step 1: Set the default branch + push**

Run:

```bash
cd /home/gernot/dev/git/unidrive-dist
git branch -M main
git push -u origin main
```

Expected: branch pushed; GH repo's default branch becomes `main`.

- [ ] **Step 2: Verify on GitHub**

```bash
gh repo view gkrost/unidrive-dist --json defaultBranchRef
```

Expected: `defaultBranchRef.name = "main"`.

---

## Task 7: `release/fetch-artefacts.sh`

This is the load-bearing script of the entire release pipeline. It enforces the spec §3.4 tag-parsing regex, queries sibling GH Releases via the GitHub API, downloads JAR + per-arch Rust tarballs, and verifies SHA256 + GPG signatures.

**Files:**
- Create: `release/fetch-artefacts.sh`

- [ ] **Step 1: Create the script**

```bash
mkdir -p release
```

Create `release/fetch-artefacts.sh`:

```bash
#!/usr/bin/env bash
#
# release/fetch-artefacts.sh
#
# Pulls the JVM CLI JAR and the per-arch Rust tarballs from the
# sibling repos' GitHub Releases into ./artefacts/, verifies SHA256
# and GPG signatures, and prints a manifest.
#
# Usage:
#   release/fetch-artefacts.sh <dist-tag>
#
# Where <dist-tag> is the unidrive-dist tag we're cutting, e.g.:
#   v0.0.1
#   v0.0.1-pkg2
#
# The tag is parsed per spec §3.4:
#   ^v(\d+\.\d+\.\d+)(-pkg\d+)?$
# Group 1 = upstream SemVer.
# The -pkgN suffix is ignored when querying sibling repos (their
# tags never carry it).
#
# Sibling-release resolution:
#   For each sibling repo, query its GH Releases via `gh api` for
#   the latest release whose tag is at or before the upstream
#   SemVer (SemVer-ordered). The matched release MUST exist; if a
#   sibling has no release yet matching MAJOR.MINOR, we fail fast.
#
# Verification:
#   1. Recompute SHA256 of each artefact and compare against the
#      sibling's published .sha256 file.
#   2. `gpg --verify` each .asc against its artefact, using the
#      unidrive-releases GPG key in $GNUPGHOME (or imported from
#      $GPG_PUBLIC_KEY_FILE if $GNUPGHOME is empty).
#
# Environment:
#   GNUPGHOME              GPG home with unidrive-releases public key.
#                          Default: $HOME/.gnupg.
#   GPG_PUBLIC_KEY_FILE    Optional path to unidrive-releases.gpg
#                          (the public key). If set and $GNUPGHOME
#                          has no matching key, import it.
#   GH_TOKEN / GITHUB_TOKEN  Passed through to `gh`.

set -euo pipefail

UNIDRIVE_REPO=${UNIDRIVE_REPO:-gkrost/unidrive}
UNIDRIVE_MOUNT_REPO=${UNIDRIVE_MOUNT_REPO:-gkrost/unidrive-mount-linux}

die() {
    printf 'ERROR: %s\n' "$*" >&2
    exit 1
}

usage() {
    cat >&2 <<EOF
usage: $0 <dist-tag>

  <dist-tag> matches ^v(\\d+\\.\\d+\\.\\d+)(-pkg\\d+)?$.
  Examples:
    $0 v0.0.1
    $0 v0.0.1-pkg2
EOF
    exit 64
}

# --- 1. Argument parsing ---------------------------------------------
[[ $# -eq 1 ]] || usage
DIST_TAG=$1

# Spec §3.4 regex.
if [[ ! $DIST_TAG =~ ^v([0-9]+\.[0-9]+\.[0-9]+)(-pkg[0-9]+)?$ ]]; then
    die "tag '$DIST_TAG' doesn't match spec §3.4 regex ^v(\d+\.\d+\.\d+)(-pkg\d+)?\$"
fi
UPSTREAM_SEMVER=${BASH_REMATCH[1]}
PKG_SUFFIX=${BASH_REMATCH[2]:-}

printf '[fetch] dist tag:        %s\n' "$DIST_TAG"
printf '[fetch] upstream semver: %s\n' "$UPSTREAM_SEMVER"
printf '[fetch] pkg suffix:      %s\n' "${PKG_SUFFIX:-(none)}"

# --- 2. Tool prerequisites ------------------------------------------
for cmd in gh gpg sha256sum jq curl; do
    command -v "$cmd" >/dev/null 2>&1 || die "$cmd not found on PATH"
done

# Optional public-key import.
if [[ -n ${GPG_PUBLIC_KEY_FILE:-} && -f $GPG_PUBLIC_KEY_FILE ]]; then
    if ! gpg --list-keys releases@unidrive.krost.org >/dev/null 2>&1; then
        printf '[fetch] importing unidrive-releases public key from %s\n' \
               "$GPG_PUBLIC_KEY_FILE"
        gpg --batch --import "$GPG_PUBLIC_KEY_FILE"
    fi
fi

# Sanity-check the public key is present.
gpg --list-keys releases@unidrive.krost.org >/dev/null 2>&1 \
    || die "unidrive-releases public key not in GNUPGHOME=$GNUPGHOME"

# --- 3. Per-sibling release resolution -------------------------------
# Pick the latest sibling release with tag <= upstream semver. We use
# strict equality first (the common case for v0.0.1), then fall back
# to the SemVer-ordered query.
resolve_release_tag() {
    local repo=$1
    local target_semver=$2
    local target_tag="v$target_semver"

    # Strict equality?
    if gh release view --repo "$repo" "$target_tag" >/dev/null 2>&1; then
        printf '%s\n' "$target_tag"
        return 0
    fi

    # No exact match: query all v*-tagged releases, filter to the same
    # MAJOR.MINOR, pick the highest tag <= target.
    local target_major_minor
    target_major_minor=$(printf '%s\n' "$target_semver" | cut -d. -f1-2)

    local candidate
    candidate=$(
        gh release list --repo "$repo" --limit 100 \
            --json tagName --jq '.[].tagName' \
        | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' \
        | awk -F'[v.]' \
            -v target_major="${target_major_minor%.*}" \
            -v target_minor="${target_major_minor#*.}" \
            -v target_patch="${target_semver##*.}" '
            {
                major=$2; minor=$3; patch=$4
                if (major == target_major && minor == target_minor && patch+0 <= target_patch+0) {
                    print
                }
            }' \
        | sort -V \
        | tail -1
    )

    if [[ -z $candidate ]]; then
        die "$repo: no release found matching $target_major_minor.x at or before $target_tag"
    fi
    printf '%s\n' "$candidate"
}

JVM_TAG=$(resolve_release_tag "$UNIDRIVE_REPO" "$UPSTREAM_SEMVER")
MOUNT_TAG=$(resolve_release_tag "$UNIDRIVE_MOUNT_REPO" "$UPSTREAM_SEMVER")
printf '[fetch] jvm release:   %s @ %s\n' "$UNIDRIVE_REPO" "$JVM_TAG"
printf '[fetch] mount release: %s @ %s\n' "$UNIDRIVE_MOUNT_REPO" "$MOUNT_TAG"

JVM_SEMVER=${JVM_TAG#v}
MOUNT_SEMVER=${MOUNT_TAG#v}

# --- 4. Download artefacts -------------------------------------------
WORK=artefacts
rm -rf "$WORK"
mkdir -p "$WORK/jvm" "$WORK/mount"

download_set() {
    local repo=$1
    local tag=$2
    local subdir=$3
    shift 3
    for filename in "$@"; do
        printf '[fetch]   %s/%s/%s\n' "$repo" "$tag" "$filename"
        gh release download "$tag" --repo "$repo" --pattern "$filename" --dir "$subdir"
    done
}

download_set "$UNIDRIVE_REPO" "$JVM_TAG" "$WORK/jvm" \
    "unidrive-${JVM_SEMVER}.jar" \
    "unidrive-${JVM_SEMVER}.jar.sha256" \
    "unidrive-${JVM_SEMVER}.jar.asc" \
    "unidrive-${JVM_SEMVER}.jar.sha256.asc"

for arch in x86_64 aarch64; do
    download_set "$UNIDRIVE_MOUNT_REPO" "$MOUNT_TAG" "$WORK/mount" \
        "unidrive-mount-${MOUNT_SEMVER}-${arch}.tar.gz" \
        "unidrive-mount-${MOUNT_SEMVER}-${arch}.tar.gz.sha256" \
        "unidrive-mount-${MOUNT_SEMVER}-${arch}.tar.gz.asc" \
        "unidrive-mount-${MOUNT_SEMVER}-${arch}.tar.gz.sha256.asc"
done

# --- 5. Verify SHA256 ------------------------------------------------
verify_sha() {
    local dir=$1
    local sha_file=$2
    (cd "$dir" && sha256sum -c "$sha_file") \
        || die "SHA256 mismatch on $dir/$sha_file"
}

verify_sha "$WORK/jvm" "unidrive-${JVM_SEMVER}.jar.sha256"
for arch in x86_64 aarch64; do
    verify_sha "$WORK/mount" "unidrive-mount-${MOUNT_SEMVER}-${arch}.tar.gz.sha256"
done

# --- 6. Verify GPG signatures ----------------------------------------
verify_sig() {
    local sig=$1
    local file=$2
    gpg --verify "$sig" "$file" 2>&1 \
        | grep -q 'Good signature' \
        || die "GPG verify failed on $sig"
    printf '[fetch] GPG ok: %s\n' "$file"
}

verify_sig "$WORK/jvm/unidrive-${JVM_SEMVER}.jar.asc" \
           "$WORK/jvm/unidrive-${JVM_SEMVER}.jar"
verify_sig "$WORK/jvm/unidrive-${JVM_SEMVER}.jar.sha256.asc" \
           "$WORK/jvm/unidrive-${JVM_SEMVER}.jar.sha256"
for arch in x86_64 aarch64; do
    verify_sig "$WORK/mount/unidrive-mount-${MOUNT_SEMVER}-${arch}.tar.gz.asc" \
               "$WORK/mount/unidrive-mount-${MOUNT_SEMVER}-${arch}.tar.gz"
    verify_sig "$WORK/mount/unidrive-mount-${MOUNT_SEMVER}-${arch}.tar.gz.sha256.asc" \
               "$WORK/mount/unidrive-mount-${MOUNT_SEMVER}-${arch}.tar.gz.sha256"
done

# --- 7. Manifest -----------------------------------------------------
cat > "$WORK/manifest.txt" <<EOF
dist_tag=$DIST_TAG
upstream_semver=$UPSTREAM_SEMVER
pkg_suffix=$PKG_SUFFIX
jvm_repo=$UNIDRIVE_REPO
jvm_tag=$JVM_TAG
jvm_semver=$JVM_SEMVER
mount_repo=$UNIDRIVE_MOUNT_REPO
mount_tag=$MOUNT_TAG
mount_semver=$MOUNT_SEMVER
EOF

printf '\n[fetch] done. Manifest:\n'
cat "$WORK/manifest.txt"
```

- [ ] **Step 2: Make it executable + shellcheck-clean**

```bash
chmod +x release/fetch-artefacts.sh
shellcheck release/fetch-artefacts.sh
```

Expected: no warnings (or only minor SC2086 if any — fix those by quoting).

- [ ] **Step 3: Smoke against locally-built artefacts**

We can't fully smoke this script without real GH Releases. But we can exercise the tag-parsing branch by passing an invalid tag and verifying the error:

```bash
./release/fetch-artefacts.sh invalid-tag 2>&1 | grep -q "doesn't match spec" \
    && echo "tag-parse smoke: ok"
```

Expected: `tag-parse smoke: ok`.

- [ ] **Step 4: Commit**

```bash
git add release/fetch-artefacts.sh
git commit -m "$(cat <<'EOF'
release: fetch-artefacts.sh — pull + verify sibling release artefacts

Enforces spec §3.4 tag-parsing regex, queries each sibling repo's
GH Releases for the latest release at or before the dist tag's
upstream SemVer, downloads JAR + per-arch Rust tarballs, verifies
SHA256 + GPG signatures, and writes a manifest.

The signing key is NOT touched — only the public key in $GNUPGHOME
is used for verification. Per spec §5.
EOF
)"
```

---

## Task 8: Tarball channel — files

**Files:**
- Create: `packaging/tarball/install.sh` (migrated from `unidrive/dist/install.sh`)
- Create: `packaging/tarball/uninstall.sh` (migrated)
- Create: `packaging/tarball/unidrive.service` (copied)
- Create: `packaging/tarball/build-tarball.sh`

- [ ] **Step 1: Copy the existing installer + uninstaller**

```bash
mkdir -p packaging/tarball
cp /home/gernot/dev/git/unidrive/dist/install.sh    packaging/tarball/install.sh
cp /home/gernot/dev/git/unidrive/dist/uninstall.sh  packaging/tarball/uninstall.sh
cp /home/gernot/dev/git/unidrive/dist/unidrive.service packaging/tarball/unidrive.service
```

Verify:

```bash
head -15 packaging/tarball/install.sh
ls -la packaging/tarball/
```

- [ ] **Step 2: Adapt `install.sh` to the new tarball layout**

The current `install.sh` resolves the JAR from `core/app/cli/build/libs/` (sibling-repo path). In the tarball, the JAR sits at `lib/unidrive-<version>.jar` next to `install.sh`. Open `packaging/tarball/install.sh` and replace the JAR-resolution block.

Find this block (around the existing line ~40):

```bash
if [[ $# -ge 1 ]]; then
    CLI_JAR="$1"
    ...
else
    CLI_JAR="$(resolve_jar "${REPO_ROOT}/core/app/cli/build/libs" "unidrive")"
    ...
fi
```

Replace with:

```bash
# In the tarball layout, the JAR lives next to install.sh under lib/.
if [[ $# -ge 1 ]]; then
    CLI_JAR="$1"
    if [[ ! -f "${CLI_JAR}" ]]; then
        echo "ERROR: CLI JAR not found at: ${CLI_JAR}" >&2
        exit 1
    fi
else
    CLI_JAR="$(resolve_jar "${SCRIPT_DIR}/lib" "unidrive")"

    if [[ -z "${CLI_JAR}" ]]; then
        echo "ERROR: unidrive CLI JAR not found in lib/" >&2
        echo "Re-extract the tarball and re-run install.sh." >&2
        exit 1
    fi
fi
```

Also adapt the unidrive-mount handling block. Find the placeholder section that begins:

```bash
# Co-daemon (unidrive-mount Rust binary) — placeholder
```

Replace the entire block with:

```bash
# Co-daemon (unidrive-mount Rust binary)
MOUNT_SRC="${SCRIPT_DIR}/bin/unidrive-mount"
MOUNT_BIN="${INSTALL_LIB}/unidrive-mount"
if [[ -x "${MOUNT_SRC}" ]]; then
    cp "${MOUNT_SRC}" "${MOUNT_BIN}"
    chmod +x "${MOUNT_BIN}"
    echo "  ${MOUNT_BIN}"
else
    echo ""
    echo "WARNING: unidrive-mount binary not found at ${MOUNT_SRC}." >&2
    echo "         The CLI will install, but 'unidrive mount' will not work." >&2
    echo ""
fi
```

Verify the file is still shellcheck-clean:

```bash
shellcheck packaging/tarball/install.sh
```

- [ ] **Step 3: Adapt `uninstall.sh`**

Read `packaging/tarball/uninstall.sh`. If it has any references to sibling-repo build paths or assumes a `REPO_ROOT`, simplify it: the uninstaller only needs to remove `~/.local/lib/unidrive/`, `~/.local/bin/unidrive`, and `~/.config/systemd/user/unidrive.service` — none of which depend on tarball-vs-repo layout.

If the current uninstaller is already free of repo-path assumptions, leave it. Run:

```bash
shellcheck packaging/tarball/uninstall.sh
```

If it produces warnings, fix them inline. Otherwise no edit needed.

- [ ] **Step 4: Write `build-tarball.sh`**

Create `packaging/tarball/build-tarball.sh`:

```bash
#!/usr/bin/env bash
#
# packaging/tarball/build-tarball.sh
#
# Stages and tars one per-arch end-user bundle.
#
# Usage:
#   packaging/tarball/build-tarball.sh <semver> <arch>
#
# Reads from:
#   artefacts/jvm/unidrive-<semver>.jar
#   artefacts/mount/unidrive-mount-<semver>-<arch>.tar.gz
#   packaging/tarball/install.sh
#   packaging/tarball/uninstall.sh
#   packaging/tarball/unidrive.service
#   README.md  (in the unidrive sibling repo — see PRE-FETCH below)
#   LICENSE, NOTICE (this repo)
#
# Writes to:
#   build/tarball/unidrive-<semver>-linux-<arch>.tar.gz
#   build/tarball/unidrive-<semver>-linux-<arch>.tar.gz.sha256
#
# Signing happens later via release/sign-packages.sh (Plan P4b).

set -euo pipefail

[[ $# -eq 2 ]] || { echo "usage: $0 <semver> <arch>" >&2; exit 64; }
SEMVER=$1
ARCH=$2

case "$ARCH" in
    x86_64|aarch64) : ;;
    *) echo "ERROR: arch must be x86_64 or aarch64 (got: $ARCH)" >&2; exit 64 ;;
esac

REPO_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$REPO_ROOT"

# Verify pre-fetched artefacts.
JAR=artefacts/jvm/unidrive-${SEMVER}.jar
MOUNT_TARBALL=artefacts/mount/unidrive-mount-${SEMVER}-${ARCH}.tar.gz
test -f "$JAR" || { echo "ERROR: missing $JAR (run release/fetch-artefacts.sh)" >&2; exit 1; }
test -f "$MOUNT_TARBALL" || { echo "ERROR: missing $MOUNT_TARBALL" >&2; exit 1; }

# Stage.
OUT=build/tarball
STAGE=$OUT/stage/unidrive-${SEMVER}
rm -rf "$OUT/stage" "$OUT/unidrive-${SEMVER}-linux-${ARCH}".*
mkdir -p "$STAGE/lib" "$STAGE/bin" "$STAGE/systemd" "$STAGE/doc"

# Lay out the bundle.
cp "$JAR" "$STAGE/lib/"

# Extract the mount tarball and pull out the binary.
mount_extract=$(mktemp -d)
tar -C "$mount_extract" -xzf "$MOUNT_TARBALL"
mount_dir="$mount_extract/unidrive-mount-${SEMVER}"
cp "$mount_dir/unidrive-mount" "$STAGE/bin/"
chmod +x "$STAGE/bin/unidrive-mount"
rm -rf "$mount_extract"

cp packaging/tarball/install.sh   "$STAGE/install.sh"
cp packaging/tarball/uninstall.sh "$STAGE/uninstall.sh"
cp packaging/tarball/unidrive.service "$STAGE/systemd/unidrive.service"
chmod +x "$STAGE/install.sh" "$STAGE/uninstall.sh"

cp LICENSE "$STAGE/doc/LICENSE"
cp NOTICE  "$STAGE/doc/NOTICE"
# Try to include the sibling unidrive repo's README if we can find it.
# Acceptable fallback: a one-line stub. Sibling-checkout path per AGENTS.md.
if [[ -f ../unidrive/README.md ]]; then
    cp ../unidrive/README.md "$STAGE/doc/README.md"
else
    printf 'UniDrive %s — see https://unidrive.krost.org/\n' "$SEMVER" \
        > "$STAGE/doc/README.md"
fi

# SHA256SUMS over everything in the staged tree.
(
    cd "$STAGE"
    find . -type f ! -name SHA256SUMS -print0 \
        | xargs -0 sha256sum \
        | sort -k2 \
        > SHA256SUMS
)

# Tar.
TARBALL="$OUT/unidrive-${SEMVER}-linux-${ARCH}.tar.gz"
tar -C "$OUT/stage" -czf "$TARBALL" "unidrive-${SEMVER}"
sha256sum "$TARBALL" > "${TARBALL}.sha256"

# Cleanup the stage.
rm -rf "$OUT/stage"

echo "[build-tarball] produced:"
ls -la "$TARBALL" "${TARBALL}.sha256"
```

- [ ] **Step 5: Make executable + shellcheck**

```bash
chmod +x packaging/tarball/build-tarball.sh
shellcheck packaging/tarball/build-tarball.sh packaging/tarball/install.sh packaging/tarball/uninstall.sh
```

Expected: clean. If warnings remain, address them.

- [ ] **Step 6: Commit**

```bash
git add packaging/tarball/
git commit -m "$(cat <<'EOF'
packaging: tarball channel — install.sh + uninstall.sh + build script

install.sh and uninstall.sh migrated from unidrive/dist/ and adapted
to the tarball layout (JAR resolved from lib/ next to the script;
unidrive-mount binary copied from bin/ if present).

build-tarball.sh stages JAR + per-arch unidrive-mount + LICENSE +
NOTICE + install.sh + unidrive.service into
build/tarball/unidrive-<semver>-linux-<arch>.tar.gz with a
SHA256SUMS manifest in the tarball.

Per spec §4.1.
EOF
)"
```

---

## Task 9: `test/local-build.sh` — the dev-loop driver

For P4a we wire this script to handle just the tarball channel. P4b extends it to call deb / rpm / AUR / smoke matrix as those land.

**Files:**
- Create: `test/local-build.sh`

- [ ] **Step 1: Write the script**

```bash
mkdir -p test
```

Create `test/local-build.sh`:

```bash
#!/usr/bin/env bash
#
# test/local-build.sh
#
# Dev-loop driver: builds sibling artefacts from local checkouts,
# stages them as if they came from GH Releases, then runs the
# packaging recipes (currently: tarball only — Plan P4b extends).
#
# Usage:
#   test/local-build.sh [<unidrive-checkout>] [<mount-checkout>]
#
# Defaults:
#   unidrive-checkout    = ../unidrive
#   mount-checkout       = ../unidrive-mount-linux
#
# Behaviour:
#   1. Build the JVM shadowJar.
#   2. Build the Rust binary for the host arch only.
#   3. Compute SHA256 + stage everything into artefacts/ so that
#      packaging recipes operate on the same paths they would in CI.
#   4. Build the tarball for the host arch.
#
# This script intentionally does NOT sign anything (no key on the
# dev machine). Real signing happens in CI per spec §5.

set -euo pipefail

UNIDRIVE=${1:-../unidrive}
MOUNT=${2:-../unidrive-mount-linux}

[[ -d $UNIDRIVE/core ]]      || { echo "ERROR: unidrive checkout not found at $UNIDRIVE" >&2; exit 1; }
[[ -f $MOUNT/Cargo.toml ]]   || { echo "ERROR: unidrive-mount-linux checkout not found at $MOUNT" >&2; exit 1; }

REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$REPO_ROOT"

# --- 1. Read versions from sibling manifests -------------------------
JVM_SEMVER=$(awk -F'"' '/^[[:space:]]*version = / && !v { v=1; print $2 }' "$UNIDRIVE/core/build.gradle.kts")
[[ -n $JVM_SEMVER ]] || { echo "ERROR: failed to extract version from $UNIDRIVE/core/build.gradle.kts" >&2; exit 1; }
MOUNT_SEMVER=$(awk -F'"' '/^version = / {print $2; exit}' "$MOUNT/mount/Cargo.toml")
[[ -n $MOUNT_SEMVER ]] || { echo "ERROR: failed to extract version from $MOUNT/mount/Cargo.toml" >&2; exit 1; }

echo "[local-build] JVM   $JVM_SEMVER"
echo "[local-build] Mount $MOUNT_SEMVER"

# --- 2. Build the JVM shadowJar --------------------------------------
( cd "$UNIDRIVE/core" && ./gradlew :app:cli:shadowJar --no-daemon -q )
JAR_SRC=$UNIDRIVE/core/app/cli/build/libs/unidrive-${JVM_SEMVER}.jar
[[ -f $JAR_SRC ]] || { echo "ERROR: shadowJar did not produce $JAR_SRC" >&2; exit 1; }

# --- 3. Build the Rust binary for the host arch ----------------------
( cd "$MOUNT" && cargo build --release --bin unidrive-mount )
MOUNT_BIN_SRC=$MOUNT/target/release/unidrive-mount
[[ -x $MOUNT_BIN_SRC ]] || { echo "ERROR: cargo build did not produce $MOUNT_BIN_SRC" >&2; exit 1; }

HOST_ARCH=$(uname -m)
case "$HOST_ARCH" in
    x86_64|aarch64) : ;;
    *) echo "ERROR: unsupported host arch $HOST_ARCH" >&2; exit 1 ;;
esac

# --- 4. Stage artefacts/ as if downloaded from GH Releases -----------
rm -rf artefacts
mkdir -p artefacts/jvm artefacts/mount

cp "$JAR_SRC" "artefacts/jvm/unidrive-${JVM_SEMVER}.jar"
( cd artefacts/jvm && sha256sum "unidrive-${JVM_SEMVER}.jar" > "unidrive-${JVM_SEMVER}.jar.sha256" )

# Build a faux per-arch mount tarball matching the layout from
# unidrive-mount-linux's release workflow.
mount_stage=$(mktemp -d)
mkdir -p "$mount_stage/unidrive-mount-${MOUNT_SEMVER}"
cp "$MOUNT_BIN_SRC"   "$mount_stage/unidrive-mount-${MOUNT_SEMVER}/unidrive-mount"
cp "$MOUNT/LICENSE"   "$mount_stage/unidrive-mount-${MOUNT_SEMVER}/"
cp "$MOUNT/NOTICE"    "$mount_stage/unidrive-mount-${MOUNT_SEMVER}/"
tar -C "$mount_stage" \
    -czf "artefacts/mount/unidrive-mount-${MOUNT_SEMVER}-${HOST_ARCH}.tar.gz" \
    "unidrive-mount-${MOUNT_SEMVER}"
rm -rf "$mount_stage"
( cd artefacts/mount && \
    sha256sum "unidrive-mount-${MOUNT_SEMVER}-${HOST_ARCH}.tar.gz" \
        > "unidrive-mount-${MOUNT_SEMVER}-${HOST_ARCH}.tar.gz.sha256" )

echo "[local-build] artefacts staged"
ls -la artefacts/jvm artefacts/mount

# --- 5. Build the tarball (host arch only) ---------------------------
# Use the JVM semver as the dist semver. The build-tarball.sh script
# expects the JVM and mount semvers to match for the local-build case;
# if they diverge during dev, run from the JVM side intentionally.
DIST_SEMVER=$JVM_SEMVER
if [[ $JVM_SEMVER != "$MOUNT_SEMVER" ]]; then
    echo "[local-build] WARNING: JVM ($JVM_SEMVER) and mount ($MOUNT_SEMVER) versions differ;" >&2
    echo "[local-build] using JVM version as dist version. CI rejects this case." >&2
fi

packaging/tarball/build-tarball.sh "$DIST_SEMVER" "$HOST_ARCH"

echo
echo "[local-build] DONE."
echo "    tarball: build/tarball/unidrive-${DIST_SEMVER}-linux-${HOST_ARCH}.tar.gz"
```

- [ ] **Step 2: Make executable + shellcheck**

```bash
chmod +x test/local-build.sh
shellcheck test/local-build.sh
```

Expected: clean.

- [ ] **Step 3: Run the local build end-to-end**

This is the critical smoke for P4a — exercises fetch-artefacts logic indirectly + build-tarball.sh against real artefacts produced from local checkouts.

```bash
cd /home/gernot/dev/git/unidrive-dist
./test/local-build.sh
```

Expected (assuming both sibling repos build cleanly):

```
[local-build] JVM   0.0.1
[local-build] Mount 0.0.1
... (gradle output) ...
... (cargo output) ...
[local-build] artefacts staged
[build-tarball] produced:
-rw-r--r-- ... build/tarball/unidrive-0.0.1-linux-x86_64.tar.gz
-rw-r--r-- ... build/tarball/unidrive-0.0.1-linux-x86_64.tar.gz.sha256
[local-build] DONE.
    tarball: build/tarball/unidrive-0.0.1-linux-x86_64.tar.gz
```

If the JVM or mount version doesn't match `0.0.1`, the WARNING fires. Either:

- Plan P1 / P2 hasn't been merged on the sibling repo yet (the version bump). Merge them, then retry.
- Or proceed with the mismatch and accept that this is a dev-only build.

- [ ] **Step 4: Inspect the produced tarball**

```bash
tar -tzf build/tarball/unidrive-0.0.1-linux-x86_64.tar.gz | head -20
```

Expected listing:

```
unidrive-0.0.1/
unidrive-0.0.1/SHA256SUMS
unidrive-0.0.1/bin/
unidrive-0.0.1/bin/unidrive-mount
unidrive-0.0.1/doc/
unidrive-0.0.1/doc/LICENSE
unidrive-0.0.1/doc/NOTICE
unidrive-0.0.1/doc/README.md
unidrive-0.0.1/install.sh
unidrive-0.0.1/lib/
unidrive-0.0.1/lib/unidrive-0.0.1.jar
unidrive-0.0.1/systemd/
unidrive-0.0.1/systemd/unidrive.service
unidrive-0.0.1/uninstall.sh
```

- [ ] **Step 5: Manual install smoke against the produced tarball**

In a throwaway HOME, extract and install:

```bash
TESTROOT=$(mktemp -d)
mkdir -p "$TESTROOT/home"
export HOME=$TESTROOT/home

tar -C "$TESTROOT" -xzf build/tarball/unidrive-0.0.1-linux-x86_64.tar.gz
cd "$TESTROOT/unidrive-0.0.1"
bash install.sh
ls "$HOME/.local/bin/" "$HOME/.local/lib/unidrive/"

"$HOME/.local/bin/unidrive" --version 2>&1 | head -3

bash uninstall.sh
ls "$HOME/.local/bin/" "$HOME/.local/lib/unidrive/" 2>/dev/null || echo "(uninstalled cleanly)"

unset HOME
rm -rf "$TESTROOT"
```

Expected:
- After install: `unidrive` symlink/wrapper exists in `.local/bin/`; `unidrive-0.0.1.jar` and `unidrive-mount` in `.local/lib/unidrive/`.
- `unidrive --version` prints `0.0.1` (or the BuildInfo-enriched form — both are fine; spec §3.5 governs the user-visible canonical version which is `0.0.1`).
- After uninstall: directories are clean or absent.

If anything fails: stop and reconcile against `packaging/tarball/install.sh`.

- [ ] **Step 6: Commit**

```bash
git add test/local-build.sh
git commit -m "$(cat <<'EOF'
test: local-build.sh — dev-loop driver for the tarball channel

Builds sibling artefacts from ../unidrive and ../unidrive-mount-linux
local checkouts, stages them under artefacts/ to match the layout
fetch-artefacts.sh produces in CI, then invokes
packaging/tarball/build-tarball.sh for the host arch.

No signing on the dev machine — per spec §5 the key lives only on
the krost-infra VPS.

Currently wires the tarball channel only. Plans P4b adds deb / rpm /
AUR steps and a Docker smoke matrix.
EOF
)"
```

---

## Task 10: Final review + push

**Files:** none changed

- [ ] **Step 1: Review the branch**

```bash
cd /home/gernot/dev/git/unidrive-dist
git log --oneline
git diff --stat $(git rev-list --max-parents=0 HEAD)..HEAD
```

Expected: ~8 commits; diff shows the full scaffolding tree (LICENSE, NOTICE, README, AGENTS, BACKLOG, CLOSED, RELEASES, docs/, packaging/tarball/, release/fetch-artefacts.sh, test/local-build.sh, .gitignore).

- [ ] **Step 2: Push**

```bash
git push origin main
```

(There's no PR here — this is the initial main-line state of the repo. PRs only come once the repo is established and the first protected-branch rules are set.)

If you've configured branch protection in advance: open a PR from a feature branch and merge.

- [ ] **Step 3: Open the GitHub repo settings `[MAINTAINER]`**

In the browser:

- https://github.com/gkrost/unidrive-dist/settings — set description, topics (`linux`, `cloud-storage`, `packaging`).
- https://github.com/gkrost/unidrive-dist/settings/branches — add a branch-protection rule for `main`: require status checks once Plan P4b adds the `test-packaging.yml` workflow. Skip for now.

- [ ] **Step 4: Confirm the four GH secrets exist**

In the browser at https://github.com/gkrost/unidrive-dist/settings/secrets/actions:

- `GPG_PRIVATE_KEY` — present from Plan P3 Task 7 (or deferred to here; paste now if not).
- `GPG_KEY_FINGERPRINT` — same.
- `DIST_DEPLOY_KEY` — deferred to P4a Task 0 Step 4; paste now if not done.
- `SIGNER_DEPLOY_KEY` — same.

(The fifth — `AUR_BOT_KEY` — comes in Plan P4b.)

---

## Self-Review Notes

**Spec coverage for P4a:**

| Spec requirement | Implementing task |
|---|---|
| §2.2 separate-repo justification | Task 2 (README) + Task 3 (AGENTS) |
| §4.1 tarball channel | Tasks 8, 9 |
| §4.7 RELEASES.md changelog strategy | Task 4 (scaffold) |
| §6 repo layout (subset: scaffolding, release/, packaging/tarball/, docs/, test/) | Tasks 1–9 |
| §6.1 AGENTS.md version-exception clause | Task 3 |
| §3.4 fetch-artefacts.sh tag-parsing rule | Task 7 |
| §3.3 sibling-release-resolution (latest at or before SemVer) | Task 7 |
| §10 AC #3 prerequisite (tarball-bundle production) | Tasks 8, 9 verify locally |

**Deferred to Plan P4b:**

| Spec requirement | Deferred to |
|---|---|
| §4.2 (APT channel) | P4b |
| §4.3 (DNF channel) | P4b |
| §4.4 (AUR channel) | P4b |
| §5.2 sign-packages.sh release-time flow | P4b |
| §6 layout: deb/, rpm/, aur/, sign-packages.sh, publish-*.sh, release.sh, smoke matrix, CI | P4b |
| §7.3.1 unidrive.repo content (stays in P4b — packaged as part of `repo-server/`) | P4b |
| §8.2 smoke matrix | P4b |

**Deferred to Plan P5:**

| Spec requirement | Deferred to |
|---|---|
| First `RELEASES.md` content | P5 |
| `unidrive.krost.org/install/` landing page | P5 |
| First real tag push end-to-end | P5 |
| §10 AC #1–#8 verification | P5 |

**Placeholder scan:** no TODO / TBD / FIXME in the plan body.

**Type/name consistency:**

- Sibling-checkout paths consistent: `../unidrive`, `../unidrive-mount-linux`.
- Artefact filename patterns consistent across `fetch-artefacts.sh`, `build-tarball.sh`, `local-build.sh`: `unidrive-X.Y.Z.jar`, `unidrive-mount-X.Y.Z-<arch>.tar.gz`, `unidrive-X.Y.Z-linux-<arch>.tar.gz`.
- Directory conventions consistent: `artefacts/jvm/`, `artefacts/mount/`, `build/tarball/`.
- GH secrets (`GPG_PRIVATE_KEY`, `GPG_KEY_FINGERPRINT`, `DIST_DEPLOY_KEY`, `SIGNER_DEPLOY_KEY`) named consistently with Plans P1, P2, P3.

**Maintainer-only steps** tagged `[MAINTAINER]` (Task 0, Task 10 Step 3+4).

**Cross-plan dependencies:**

- Inputs: Plans P1, P2 must be merged (their workflow files exist so the version bump from P2 has landed on `unidrive-mount-linux/main`); Plan P3 must have provisioned the GPG key + deferred secrets.
- Outputs consumed by P4b: `fetch-artefacts.sh`, `packaging/tarball/`, the AGENTS.md exception clause (so P4b can add deb/rpm `Version:` strings without spec friction).
- Outputs consumed by P5: the entire unidrive-dist repo scaffolding.
