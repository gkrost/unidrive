# UniDrive Distribution & Release Engineering — Design Spec

> Status: brainstormed and approved. Awaiting implementation plan.
>
> Scope: how UniDrive ships to end users on Linux. Covers the new `unidrive-dist`
> repository, release coordination across three repos, the four MVP distribution
> channels, GPG signing, and the krost-infra integration for hosting the apt/dnf
> repositories.

## §1. Why this spec exists

UniDrive consists today of two source repos:

- **`unidrive`** — JVM (Kotlin, Gradle composite, JDK 21+). Produces a fat
  shadowJar. Ships the `unidrive` CLI and sync engine.
- **`unidrive-mount-linux`** — Rust (Cargo, edition 2024). Produces a single
  binary `unidrive-mount`. Kernel ≥ 6.9 and libfuse ≥ 3.16 hard floors. FUSE
  co-daemon for Phase 2/3 of the sparse-hydration roadmap.

There is no release process. The current `unidrive/dist/install.sh` is a
local-development convenience: it picks up a freshly-built JAR from
`core/app/cli/build/libs/` and drops it under `~/.local/`. No signed artefacts,
no distro packages, no public repo, no cross-repo coordination, no installer
for the Rust co-daemon (the installer has a placeholder comment saying the
binary is not yet released).

This spec defines the release surface that turns "we have two codebases" into
"a Linux user can `apt install unidrive` and have a working sync daemon."

## §2. The triplet: three repos, one release

A third repository, **`unidrive-dist`**, joins the existing two. Its single
job is distribution: packaging, signing, publishing, and orchestrating the
cross-repo release dance. It contains no JVM or Rust source code.

```
┌─────────────────────────────┐       ┌──────────────────────────────┐
│ unidrive (JVM, Gradle)      │       │ unidrive-mount-linux (Rust)  │
│   tag vX.Y.Z →              │       │   tag vX.Y.Z →               │
│   GH Release publishes:     │       │   GH Release publishes:      │
│     unidrive-X.Y.Z.jar  │       │     unidrive-mount-X.Y.Z-    │
│     .sha256 + .asc          │       │       x86_64.tar.gz          │
│                             │       │     unidrive-mount-X.Y.Z-    │
│                             │       │       aarch64.tar.gz         │
│                             │       │     .sha256 + .asc each      │
└─────────────────────────────┘       └──────────────────────────────┘
                │                                    │
                └──────────────┬─────────────────────┘
                               ▼
              ┌────────────────────────────────┐
              │ unidrive-dist (packaging-only) │
              │   tag vX.Y.Z (or vX.Y.Z-pkgN)  │
              │   CI:                          │
              │     fetch artefacts            │
              │     verify SHA256 + GPG        │
              │     build .deb .rpm AUR        │
              │     sign packages + repo meta  │
              │     publish:                   │
              │       - GH Release (tarball)   │
              │       - apt/dnf via SSH        │
              │       - AUR via git push       │
              └────────────────────────────────┘
                               │
                               ▼
              ┌────────────────────────────────┐
              │ krost-infra (Docker Compose)   │
              │   new container: pkg-server    │
              │   serves:                      │
              │     apt.unidrive.krost.org     │
              │     dnf.unidrive.krost.org     │
              └────────────────────────────────┘
```

### §2.1 Component responsibilities

- **`unidrive`** knows how to build a fat JAR. Tagged release publishes the
  JAR + SHA256 + detached signature to GH Releases. No knowledge of distro
  packaging.
- **`unidrive-mount-linux`** knows how to build a Rust binary for
  `x86_64-unknown-linux-gnu` and `aarch64-unknown-linux-gnu`. Tagged release
  publishes per-arch tarballs containing the binary + LICENSE + NOTICE, with
  SHA256 + detached signature.
- **`unidrive-dist`** knows about distro packaging. Pulls artefacts, builds
  `.deb`, `.rpm`, AUR `PKGBUILD`, and the canonical end-user tarball bundle.
- **`krost-infra`** hosts the static apt/dnf repo server. One new container,
  follows the existing krost-infra AGENTS.md security checklist.

### §2.2 Why a third repo, not packaging inside unidrive

`unidrive` is the JVM engine. Adding deb/rpm/snap toolchains to its CI would
mix concerns. The unidrive AGENTS.md "abstractions earn their keep" rule cuts
both ways here — a separate `unidrive-dist` earns its keep because:

1. Packaging mechanics evolve at a different cadence from the engine.
2. The Rust co-daemon is upstream-equal to the JVM in the user's eyes; neither
   repo is the natural home for "and now glue them into a distro package."
3. Cross-repo release orchestration needs a place to live. That place isn't
   inside one of the things being orchestrated.

The cost — three repos to keep in version-sync — is paid by the explicit
lockstep tagging protocol in §3.

## §3. Versioning and release process

### §3.1 First MVP version

**`v0.0.1` across all three repos.** Concrete contract, not an example.

### §3.2 SemVer interpretation during 0.x

| Bump | Trigger |
|---|---|
| `0.MINOR.0` (`0.1.0`, `0.2.0`) | Breaking change: IPC contract, CLI surface, kernel/libfuse floor |
| `0.0.PATCH` (`0.0.2`, `0.0.3`) | Compatible change (feature or bugfix; we don't distinguish during 0.x) |
| `1.0.0` | First stable release. Strict SemVer applies from here on. |

Strict-SemVer table for 1.x onward:

| Bump | Trigger |
|---|---|
| MAJOR | Breaking IPC contract; breaking CLI; kernel/libfuse floor raised |
| MINOR | New feature; new provider; new IPC verb; new CLI subcommand |
| PATCH | Bugfix in any of the three repos |

### §3.3 Tagging protocol

**Sibling repos tag only when their own code has changed for this release.**
unidrive-dist's tag is authoritative — it defines the release. The
user-visible coordinated SemVer is preserved (still one `0.0.1` everywhere)
without forcing empty-payload tags on unchanged sibling repos.

Cut releases in this order:

```
1. (If unidrive source changed for this release.)
   unidrive @ main green (./gradlew check) → git tag vX.Y.Z → push.
   GH Action builds shadowJar, signs, attaches to GH Release.

2. (If unidrive-mount-linux source changed for this release.)
   unidrive-mount-linux @ main green (cargo test) → git tag vX.Y.Z → push.
   GH Action cross-compiles x86_64 + aarch64, signs tarballs, attaches.

3. unidrive-dist @ main green (Docker smoke) → git tag vX.Y.Z → push.
   GH Action runs release/release.sh which:
     a. fetch-artefacts.sh queries each sibling repo's GH Releases via the
        GitHub API for the latest release whose tag is at or before vX.Y.Z
        (SemVer-ordered). The matched release MUST exist; if a sibling has
        no release yet matching vX.Y.Z's MAJOR.MINOR, fail fast.
     b. SHA256 + GPG verify each artefact downloaded.
     c. Build .deb, .rpm, AUR source, tarball bundle.
     d. Sign packages + repo metadata with project key (via pkg-signer, §5).
     e. publish-apt.sh / publish-dnf.sh rsync to krost-infra over SSH.
     f. publish-gh-release.sh uploads tarball bundle to dist's GH Release.
     g. publish-aur.sh ssh-pushes PKGBUILD update.
```

**Worked example.** Release `v0.0.2` fixes only a JVM bug. Sequence:

- `unidrive`: tag `v0.0.2`, GH Release publishes the new JAR.
- `unidrive-mount-linux`: no tag, still at `v0.0.1` (its existing release
  remains the latest).
- `unidrive-dist`: tag `v0.0.2`. `fetch-artefacts.sh` pulls
  `unidrive 0.0.2` and `unidrive-mount-linux 0.0.1`, builds `0.0.2-1` deb/rpm.

If steps 1 or 2 fail (or are skipped because nothing changed), no
user-visible state changes. If step 3 fails mid-way, the publish-* scripts
are idempotent — re-running with the same tag is safe.

### §3.4 Packaging-only respin convention

When a release needs to be cut because of a packaging fix only (e.g. a `.deb`
postinst bug), without any change to JVM or Rust code:

- **Upstream tags** (`unidrive`, `unidrive-mount-linux`) — only bump when their
  own code changes. `v0.0.1` stays `v0.0.1` on those repos.
- **`unidrive-dist`** — tags `v0.0.1-pkg2`, `v0.0.1-pkg3`, etc. for
  packaging-only respins.
- **Distro version strings** use native revision suffixes:
  - deb: `0.0.1-1`, `0.0.1-2`
  - rpm: `Release: 1`, `Release: 2`
  - AUR: `pkgrel=1`, `pkgrel=2`
- **User-visible version** — stays `0.0.1` in `unidrive --version`, in GH
  Release titles, in marketing copy. The packaging revision is a distro-tooling
  detail.

**`fetch-artefacts.sh` tag-parsing rule** (the correctness invariant for the
respin workflow). Input is the dist repo's tag from `$GITHUB_REF_NAME` (or
`git describe --exact-match HEAD` for local builds). The parsing is:

```
Tag pattern:    ^v(\d+\.\d+\.\d+)(-pkg\d+)?$
                   ↑ captured: upstream SemVer
Examples:
  v0.0.1        → upstream = 0.0.1, packaging revision = 1 (default)
  v0.0.1-pkg2   → upstream = 0.0.1, packaging revision = 2
  v0.1.0-rc1    → REJECTED (no pre-release suffixes in MVP)
  v1.2.3-pkg10  → upstream = 1.2.3, packaging revision = 10
```

The script extracts capture group 1 as the "upstream SemVer" used to query
sibling GH Releases (per §3.3 step 3a). The `-pkgN` suffix (if present) is
extracted into the distro-native revision: deb `Release: -N`, rpm
`Release: N%{?dist}`, AUR `pkgrel=N`. Tags not matching the pattern fail
fast in CI.

### §3.5 `--version` output

Both `unidrive --version` and `unidrive-mount --version` print exactly the
user-visible version (e.g. `0.0.1`). No commit SHA, no build date, no
packaging revision. Build-info enrichment is a deferred BACKLOG item, not MVP.

### §3.6 Rollback story

- **Bad release on `apt.unidrive.krost.org`:** SSH to krost-infra, `mv` the
  previous good `dists/stable/` back from the timestamped backup directory
  (kept by publish-* scripts; retention: last 5).
- **Bad GH Release tarball:** delete the GH Release (keep the tag), cut a
  PATCH or `-pkgN`.
- **Bad AUR PKGBUILD:** push a corrective PKGBUILD bump.
- **No automatic rollback.** Explicit human reversal only.

### §3.7 Failure modes

1. **Sibling repo CI fails to produce a signed artefact.** Dist CI's SHA256
   verify fails. Release simply doesn't happen.
2. **Signing key compromise mid-release.** Manual playbook in
   `docs/release-process.md`. Revoke public key on `unidrive.krost.org`,
   generate new keypair, re-sign existing repo metadata, push key-rotation
   announcement.
3. **`pkg-server` container down at release time.** Publish scripts retry SSH
   3× with backoff, then leave signed artefacts in `pending/` on the CI
   runner. Manual `scp` recovers later.

## §4. Distribution channels (MVP)

Four channels. Snap and Flatpak are explicitly deferred.

### §4.1 Channel 1: Tarball + `install.sh` (canonical, per-user)

Single source of truth lives in `unidrive-dist/packaging/tarball/`. The
existing `unidrive/dist/install.sh` is migrated here. The `unidrive/dist/`
directory becomes a thin pointer documenting that the canonical installer
lives in `unidrive-dist`.

Tarball contents (`unidrive-X.Y.Z-linux-x86_64.tar.gz`,
`unidrive-X.Y.Z-linux-aarch64.tar.gz`):

```
unidrive-X.Y.Z/
├── install.sh
├── uninstall.sh
├── lib/unidrive-X.Y.Z.jar
├── bin/unidrive-mount             ← architecture-specific
├── systemd/unidrive.service       ← systemd --user unit
├── doc/{README.md, LICENSE, NOTICE}
├── SHA256SUMS
└── SHA256SUMS.asc                 ← signed with project key
```

`install.sh` lays out:
- `~/.local/lib/unidrive/unidrive-X.Y.Z.jar`
- `~/.local/lib/unidrive/unidrive-mount`
- `~/.local/bin/unidrive` (wrapper script, generated by `install.sh` at
  install time with the versioned JAR path baked in — matching the current
  `unidrive/dist/install.sh` heredoc pattern)
- `~/.config/systemd/user/unidrive.service`

Before extracting, the user is instructed to `gpg --verify SHA256SUMS.asc`
against the public key fetched from `unidrive.krost.org/install/`.

### §4.2 Channel 2: APT (`.deb`)

Target distros: Ubuntu 24.04 LTS, Ubuntu 26.04 (Kubuntu inherits), Debian 12
(bookworm), Debian 13 (trixie).

Single package `unidrive` (not split into `-cli` + `-mount`). The two binaries
share an IPC contract and are co-released; splitting gains nothing.

`debian/control`:

```
Package: unidrive
Architecture: amd64 arm64
Depends: openjdk-21-jre-headless | java-runtime-headless (>= 21),
         libfuse3-3 (>= 3.16),
         fuse3
Recommends: systemd
Suggests: kde-cli-tools
Description: Multi-cloud sync daemon with sparse hydration (FUSE)
```

The alternative `java-runtime-headless (>= 21)` is the standard Debian
virtual package (provided by all conforming JREs); `java21-runtime` is
**not** a standard virtual package and would not resolve on Ubuntu 24.04 /
Debian 12 — confirmed prior to spec finalisation against the Debian
java-common documentation. Implementation must verify with
`grep-aptavail -F Provides java-runtime-headless` on the target distros
before tagging the first release.

Install paths (FHS-compliant, system-wide):

```
/usr/lib/unidrive/unidrive.jar             ← unversioned symlink to versioned file in same dir
/usr/lib/unidrive/unidrive-mount
/usr/bin/unidrive                          ← wrapper
/usr/lib/systemd/user/unidrive.service     ← distro-shipped user unit
/usr/share/doc/unidrive/{README, LICENSE, NOTICE, changelog.Debian.gz}
                                           ← no man page in MVP; see §9
```

`postinst` runs as root and is essentially empty. systemd discovers user
units under `/usr/lib/systemd/user/` automatically on the next user login,
so no `daemon-reload` is needed. (`systemctl --user daemon-reload` from
root targets root's own `systemd --user` session, which is the wrong thing;
`systemctl --global daemon-reload` is not a valid command.) `postinst`
**does not** auto-enable, auto-start, or otherwise touch any user's
session. The user enables the service explicitly when ready.

The kernel-floor check (≥ 6.9) is enforced at runtime by `unidrive-mount`, not
at install time. Install-time would block legitimate use cases (kernel-upgrade
in progress, container image builds).

### §4.3 Channel 3: DNF (`.rpm`)

Target distros: Fedora 40+, RHEL 10, AlmaLinux/Rocky 10+.

**RHEL 9 caveat:** kernel 5.14 does not meet the 6.9 floor. The rpm installs
cleanly but `unidrive-mount` exits 78 on startup. RHEL 9 users are de-facto
unsupported. Documented in `unidrive-dist/docs/compatibility.md`.

**RHEL 10 kernel verification:** RHEL 10 reached GA on 20 May 2025 with
kernel 6.12.0-55.9.1.el10_0, which is above the 6.9 floor. RHEL 10 is a
supported target. (Verified against Phoronix and Linuxiac reporting at
spec time; revisit if Red Hat ever ships a kernel downgrade.)

`unidrive.spec`:

```
Name:           unidrive
Version:        X.Y.Z
Release:        1%{?dist}
Summary:        Multi-cloud sync daemon with sparse hydration (FUSE)
License:        ASL 2.0
URL:            https://unidrive.krost.org
BuildArch:      x86_64 aarch64

Requires:       java-21-openjdk-headless
Requires:       fuse3-libs >= 3.16
Requires:       fuse3
```

Install paths match the deb layout. `%post` is empty for the same reason
as the deb `postinst` (§4.2): systemd auto-discovers user units. No
auto-enable.

### §4.4 Channel 4: AUR (`PKGBUILD`)

Source repo: `aur.archlinux.org/unidrive.git` — separate AUR-hosted git repo.

**AUR push-authentication mechanics:** the AUR does **not** support repo-level
deploy keys. Pushes to an AUR package repo are authenticated by the SSH key
of the Arch Linux account that owns (or co-maintains) the package. Therefore:

- A dedicated Arch Linux bot account (e.g. `unidrive-bot`) is registered on
  `aur.archlinux.org`. The maintainer's personal AUR account co-maintains
  the package so account-recovery doesn't single-point on the bot.
- The bot account's SSH public key is registered in its Arch account
  settings.
- The bot account's SSH **private** key is stored as the GH Actions secret
  `AUR_BOT_KEY` on the unidrive-dist repo.
- `publish-aur.sh` uses `AUR_BOT_KEY` to ssh-push the updated PKGBUILD.
- If the bot account is compromised: the maintainer (co-maintainer) revokes
  its SSH key via the AUR web UI, regenerates a fresh keypair, updates
  `AUR_BOT_KEY`, and rotates.

```bash
pkgname=unidrive
pkgver=X.Y.Z
pkgrel=1
arch=('x86_64' 'aarch64')
license=('Apache')
depends=('java-runtime>=21' 'fuse3>=3.16')
url='https://unidrive.krost.org'

source=("https://github.com/gkrost/unidrive-dist/releases/download/vX.Y.Z/unidrive-X.Y.Z-linux-${CARCH}.tar.gz"
        "https://github.com/gkrost/unidrive-dist/releases/download/vX.Y.Z/unidrive-X.Y.Z-linux-${CARCH}.tar.gz.asc")

validpgpkeys=('<long fingerprint of unidrive-releases public key>')
```

AUR sources from the pre-built GH Release tarball, not from raw git tags —
AUR builds happen on the user's machine, and we don't want every Arch user to
need JDK + Rust toolchains.

### §4.5 Why Snap and Flatpak are deferred

**Snap:**

- FUSE under strict confinement is unidiomatic. `fuse-support` is an
  auto-connect-only-by-Canonical interface; realistic path is `classic`
  confinement, which requires a separate manual Snap Store review board
  approval that can take weeks-to-months and may be rejected.
- systemd-user units inside snaps are second-class. Snaps express daemons as
  system-wide services. Our entire installer story is per-user systemd;
  squaring this means either a different runtime model inside the snap or
  shell-out hacks that review will dislike.
- Audience overlap between "Ubuntu user who needs Snap discoverability" and
  "user willing to install a FUSE-based cloud-sync tool with OAuth and kernel
  ≥ 6.9" is small.

**Flatpak:** same FUSE-sandbox pain plus Flathub review latency.

**Re-evaluation criteria** (mirroring the unidrive AGENTS.md re-opening
criteria for retired surfaces): (a) a funded effort with ongoing maintenance,
(b) a specific paying customer or partner requiring it, or (c) a community
contributor who proposes to own the platform-tier code.

### §4.6 Cross-channel concerns

- **Java 21 (only) for MVP** across all four channels. The
  `--enable-native-access=ALL-UNNAMED` flag from the current install.sh must
  be preserved in every channel's wrapper script. Failure mode this avoids:
  silent `IllegalAccessException` at runtime when a future JDK tightens
  FFI policy.

  **Shelf life:** the `ALL-UNNAMED` form is JDK 21–22 syntax. JDK 22+
  prefers `--enable-native-access=<module-name>`; later JDKs may deprecate
  or remove `ALL-UNNAMED` entirely. MVP pins to JDK 21 (the LTS that
  Ubuntu 24.04, Debian 12, Fedora 40, and RHEL 10 all ship as
  `openjdk-21-jre-headless`/equivalent). When a target distro defaults to
  JDK 23+, the wrapper script must detect `java --version` and select the
  correct flag form — filed as a BACKLOG design constraint in
  unidrive-dist.
- **Per-user state is never touched by packaging.** `~/.config/unidrive/`,
  `~/.local/share/unidrive/`, `~/.cache/unidrive/` are created by
  `unidrive auth` and the running daemon. Install creates none of them.
  Uninstall removes none of them. Matches the unidrive `dist/uninstall.sh`
  contract.
- **No auto-start, no auto-enable.** Every channel installs the systemd-user
  unit but the user runs `systemctl --user enable --now unidrive.service`
  explicitly. Rationale: the unidrive-mount-linux AGENTS.md "the user
  explicitly chose to mount" philosophy extended to the whole sync daemon.
  Cloud-sync starting unprompted contradicts the README's anti-telemetry
  pitch.

### §4.7 Changelog strategy

Single source of truth: `unidrive-dist/RELEASES.md`. Each release commit
appends a new section to this file, sourced from the matching `CLOSED.md`
entries in the sibling repos and from `unidrive-dist`'s own `CLOSED.md` for
packaging-only items.

Format (one section per release):

```markdown
## 0.0.1 — first MVP

### unidrive (JVM)
- Tracking-set engine wired into the CLI
- (etc., from unidrive/CLOSED.md entries since last release)

### unidrive-mount-linux (FUSE co-daemon)
- Phase 2 FUSE mount with FUSE_PASSTHROUGH for hydrated files
- (etc., from unidrive-mount-linux/CLOSED.md)

### Packaging
- First .deb (Ubuntu 24.04 LTS + Debian 13; Ubuntu 26.04 added when GA)
- First .rpm (Fedora 40, RHEL 10)
- First AUR PKGBUILD
- (etc., from unidrive-dist/CLOSED.md)
```

At build time, `build-deb.sh` and `build-rpm.sh` extract the relevant
release's section and reformat it into the distro-native format:

- **deb:** rendered into `debian/changelog` syntax (one stanza per release
  with `package (version) distribution; urgency=low` header, timestamped
  signature, two-space indent).
- **rpm:** rendered into `%changelog` syntax (one stanza per release with
  `* DAY MON DD YYYY Maintainer <email> - version-release` header, bullet
  lines prefixed with `-`).

The AUR `PKGBUILD` does not carry an in-package changelog — AUR users see
the matching GitHub release notes (which themselves are generated from the
same `RELEASES.md` section at release time by `publish-gh-release.sh`).

Hand-writing `RELEASES.md` once per release. No git-log scraping. Source
content is the maintainer's curated narrative across all three repos, not
mechanical commit-message extraction.

## §5. GPG signing

A project-specific key, **not** the maintainer's personal key.

```
Name:        unidrive-releases
Email:       releases@unidrive.krost.org
Fingerprint: <to be generated>
Usage:       sign-only (no encryption, no certify-others)
```

**The signing key never leaves the krost-infra server.** It is stored there
in a restricted-permissions GPG home directory owned by a dedicated
`pkg-signer` system user.

### §5.1 `pkg-signer` container (krost-infra)

A second new krost-infra container, alongside `pkg-server`. Image:
`debian:13-slim` plus `gnupg`, `dpkg-sig`, `rpm-sign`, `apt-utils`,
`createrepo_c`. Exposes a minimal ssh-keyed sign-only service: a chrooted
SSH user `signer` whose forced command is a shell script that:

1. Accepts unsigned `.deb`, `.rpm`, or apt/dnf repo-metadata files via stdin
   (or a tightly-scoped sftp drop directory).
2. Signs them with the `unidrive-releases` key.
3. Returns the signed artefact (or copies it into a sibling `signed/`
   directory).
4. Refuses any other input. Logs every sign operation with timestamp,
   artefact filename, and SHA256.

Resource limits, healthcheck, no-new-privileges, read-only-with-tmpfs all
per krost-infra AGENTS.md. The GPG home directory is on a dedicated docker
volume (`signer-gnupg`) mounted read-write only inside this container; no
other container, including `pkg-server`, can read it.

### §5.2 Release-time signing flow

The unidrive-dist GH Actions release runner:

1. Builds unsigned `.deb`, `.rpm`, AUR source tarball, end-user tarball.
2. SSHes to `pkg-signer` (using a separate `SIGNER_DEPLOY_KEY` GH secret —
   distinct from `DIST_DEPLOY_KEY` which only reaches `pkg-server`), pushes
   the unsigned artefacts into the signer's drop directory.
3. Triggers signing (the forced-command shell script signs everything
   queued).
4. Pulls the signed artefacts back.
5. SSHes to `pkg-publisher` (the `DIST_DEPLOY_KEY` user) and rsyncs the
   signed artefacts to `pkg-server`'s `pkg-data` volume.

**The signing key never touches the GH Actions runner's filesystem or
memory.** Compromise of `DIST_DEPLOY_KEY` lets an attacker overwrite signed
packages on `pkg-server` (still bad — see §7.5) but does not give them
signing capability. Compromise of `SIGNER_DEPLOY_KEY` lets an attacker
submit arbitrary artefacts to be signed, but does not extract the key (the
forced command refuses to expose `~/.gnupg/`). Defence in depth.

### §5.3 Rationale for project-specific identity

- Clean separation: if the key is ever compromised, only UniDrive releases
  need re-keying, not the maintainer's personal git/email identity.
- Future-proof: if UniDrive is ever handed to a co-maintainer or funded
  organisation, the key transfers as a project asset, not a personal one.

The public key is published at `https://unidrive.krost.org/install/unidrive-releases.gpg`
with its fingerprint plainly visible on the install page.

## §6. `unidrive-dist` repo layout

```
unidrive-dist/
├── README.md
├── AGENTS.md
├── BACKLOG.md
├── CLOSED.md
├── RELEASES.md            # single source of truth for changelog (§4.7)
├── LICENSE                # Apache-2.0
├── NOTICE
│
├── packaging/             # recipes per channel
│   ├── deb/{debian/, build-deb.sh, unidrive.service, unidrive.install}
│   ├── rpm/{unidrive.spec, build-rpm.sh}
│   ├── aur/{PKGBUILD, .SRCINFO, publish-aur.sh}
│   └── tarball/{install.sh, uninstall.sh, unidrive.service, build-tarball.sh}
│
├── release/               # orchestration
│   ├── fetch-artefacts.sh
│   ├── sign-packages.sh
│   ├── publish-apt.sh
│   ├── publish-dnf.sh
│   ├── publish-gh-release.sh
│   └── release.sh         # top-level orchestrator
│
├── repo-server/           # config destined for krost-infra
│   ├── apt/Release.template
│   ├── dnf/               # createrepo skeleton
│   ├── nginx.conf
│   ├── docker-compose.snippet
│   └── README.md
│
├── docs/
│   ├── adr/0001-version-coupling.md
│   ├── release-process.md
│   └── compatibility.md
│
├── .github/workflows/
│   ├── release.yml        # tag-triggered
│   └── test-packaging.yml # PR-triggered
│
└── test/
    ├── deb-install-in-docker.sh
    ├── rpm-install-in-docker.sh
    ├── aur-install-in-docker.sh
    ├── tarball-install-in-docker.sh
    ├── local-build.sh
    └── smoke-all.sh
```

### §6.1 unidrive-dist AGENTS.md exception clause

The unidrive AGENTS.md and unidrive-mount-linux AGENTS.md both forbid IDs,
dates, and version numbers in commit messages, file names, and document
content. `unidrive-dist` is the **explicit exception**: its job is releases,
which inherently carry versions.

The exception is bounded:

- Versions are permitted in release artefacts, GH Release pages, the
  `debian/changelog` file, and the `Version:` field of `*.spec` files.
- Versions are permitted in `docs/release-process.md` when documenting
  past releases or example workflows.
- Source-code commit messages and `BACKLOG.md` entries still don't mention
  versions. Describe work, not releases.

## §7. krost-infra integration

### §7.1 New container: `pkg-server`

A single nginx-based static-file server. Drop-in to
`krost-infra/docker-compose.yml`:

```yaml
  pkg-server:
    image: nginx:1.27-alpine
    container_name: pkg-server
    restart: unless-stopped
    networks:
      - web
    volumes:
      - ./pkg-server/nginx.conf:/etc/nginx/nginx.conf:ro
      - pkg-data:/var/www/pkg:ro
    security_opt:
      - no-new-privileges:true
    read_only: true
    tmpfs:
      - /var/cache/nginx:size=10m
      - /var/run:size=1m
      - /tmp:size=5m
    deploy:
      resources:
        limits:
          memory: 64M
          cpus: "0.25"
          pids: 64
    healthcheck:
      test: ["CMD", "wget", "-qO-", "http://127.0.0.1/health"]
      interval: 30s
      timeout: 5s
      retries: 3
      start_period: 10s
    logging:
      driver: json-file
      options:
        max-size: "10m"
        max-file: "3"
    labels:
      - "traefik.enable=false"

volumes:
  pkg-data:                   # add to existing volumes: block
```

Conforms to the krost-infra AGENTS.md Security Checklist for new services
(no-new-privileges, resource limits, healthcheck, read_only + tmpfs, log
rotation, `traefik.enable=false` with router in `dynamic.yml`).

### §7.2 Traefik dynamic config

Drop-in to `krost-infra/traefik/dynamic.yml`:

```yaml
http:
  routers:
    pkg-server-apt:
      rule: "Host(`apt.unidrive.krost.org`)"
      service: pkg-server
      entryPoints: [websecure]
      tls: { certResolver: letsencrypt }
      middlewares: [error-pages]
    pkg-server-dnf:
      rule: "Host(`dnf.unidrive.krost.org`)"
      service: pkg-server
      entryPoints: [websecure]
      tls: { certResolver: letsencrypt }
      middlewares: [error-pages]
  services:
    pkg-server:
      loadBalancer:
        servers:
          - url: "http://pkg-server:80"
```

### §7.3 `pkg-data` volume layout

Managed by `unidrive-dist`'s publish-* scripts over rsync-SSH:

```
/var/www/pkg/
├── apt/
│   ├── dists/stable/
│   │   ├── Release          ← signed
│   │   ├── Release.gpg
│   │   ├── InRelease        ← inline-signed
│   │   └── main/binary-{amd64,arm64}/Packages.gz
│   ├── pool/main/u/unidrive/unidrive_X.Y.Z-N_{amd64,arm64}.deb
│   ├── unidrive-releases.gpg
│   └── index.html
├── dnf/
│   ├── unidrive.repo       ← see §7.3.1 for required contents
│   ├── unidrive-releases.gpg
│   ├── repodata/{repomd.xml, repomd.xml.asc, ...}
│   └── {x86_64,aarch64}/unidrive-X.Y.Z-N.{x86_64,aarch64}.rpm
├── backups/<UTC-timestamp>/  ← rolling, kept last 5
└── health                    ← "ok\n" for the container healthcheck
```

### §7.3.1 `unidrive.repo` contents

The DNF repo descriptor file shipped at `dnf.unidrive.krost.org/unidrive.repo`:

```ini
[unidrive]
name=UniDrive
baseurl=https://dnf.unidrive.krost.org/$basearch/
enabled=1
gpgcheck=1
repo_gpgcheck=1
gpgkey=https://unidrive.krost.org/install/unidrive-releases.gpg
```

Both `gpgcheck=1` (signature on individual packages) and `repo_gpgcheck=1`
(signature on `repomd.xml` itself) are mandatory. Users drop this file into
`/etc/yum.repos.d/`. The apt analogue is the `[signed-by=...]` clause in
the user's `sources.list.d/unidrive.list`, documented on
`unidrive.krost.org/install/`.

### §7.4 DNS records

Add via manual DNS update (one-time):

```
apt.unidrive.krost.org  IN CNAME  unidrive.krost.org.
dnf.unidrive.krost.org  IN CNAME  unidrive.krost.org.
```

CNAMEs (not A records) so a server-IP change only updates the
`unidrive.krost.org` A record.

`unidrive.krost.org` itself is already deployed (served by the existing
`site-unidrive` container). Its content gets an `/install/` page describing
how to add the apt/dnf repo and the public key fingerprint.

### §7.5 SSH deploy accounts and key rotation

Two distinct SSH users on the krost-infra server, each with a separate GH
Actions secret holding its private key:

| User | GH secret | Forced-command scope |
|---|---|---|
| `pkg-publisher` | `DIST_DEPLOY_KEY` | `rsync` only, chrooted to `~/docker/pkg-server/pkg-data/` |
| `signer` | `SIGNER_DEPLOY_KEY` | `pkg-signer` container's sign-only forced command (§5.1) |

Both user setups are added to `krost-infra/setup-server.sh`. Both private
keys are Ed25519, generated with passphrase=empty (GH Actions can't prompt
for a passphrase), and never exist in plain text outside the GH secrets
store and the one-time generation step.

**Threat model:** an attacker who steals `DIST_DEPLOY_KEY` can overwrite
already-signed packages on `pkg-server` (downgrade attack, package
substitution if signing is also bypassed elsewhere — but they cannot
re-sign without the signing key). An attacker who steals
`SIGNER_DEPLOY_KEY` can submit arbitrary unsigned artefacts and receive
them back signed, which is effectively full release authority — this key
is the more sensitive of the two.

**Rotation playbook** (mirrors §3.7 GPG-key revocation; lives in
`unidrive-dist/docs/release-process.md`):

1. On the krost-infra server, remove the compromised key from
   `~pkg-publisher/.ssh/authorized_keys` or `~signer/.ssh/authorized_keys`.
2. Generate a fresh Ed25519 keypair locally.
3. Append the new public key to the appropriate `authorized_keys`.
4. Update the matching GH Actions secret (`DIST_DEPLOY_KEY` or
   `SIGNER_DEPLOY_KEY`) with the new private key.
5. Audit `~/docker/pkg-server/pkg-data/backups/` (5 most recent generations
   kept; see §3.6) and the `pkg-signer` log of every sign operation. Look
   for artefacts published or signed since the last known-good release.
6. If compromise window included a release: cut a fresh release with the
   user-visible version unchanged, increment the `-pkgN` suffix on
   unidrive-dist. Publish a security advisory on `unidrive.krost.org`.

No automatic rotation. Threat-driven manual rotation only, same posture as
the GPG key.

### §7.6 krost-infra documentation updates

Per krost-infra AGENTS.md "Documentation-Stack Sync Rules":

- `CLAUDE.md` services table → add `pkg-server`
- `CLAUDE.md` architecture diagram → add the new container
- `DEPLOY.md` services table → add `apt.unidrive.krost.org` and
  `dnf.unidrive.krost.org` rows
- `DEPLOY.md` DNS table → add the two CNAME records
- `maintenance/html/apt.unidrive.krost.org/503.html` and
  `maintenance/html/dnf.unidrive.krost.org/503.html` → create
- `.env.example` → no new secrets

## §8. Testing & dev loop

### §8.1 The gate

```bash
cd unidrive-dist && ./test/smoke-all.sh
```

Pass = green, anything else = red. No coverage tool, no lint baseline. Matches
the unidrive AGENTS.md "no CI policing" stance applied at the right altitude.

### §8.2 Smoke matrix

Each script builds the relevant package fresh in CI and exercises it inside
a Docker container:

| Test script | Container | What it does |
|---|---|---|
| `test/deb-install-in-docker.sh` | `ubuntu:24.04` (and `ubuntu:26.04` when available) | `dpkg -i unidrive_*.deb`; assert install paths; `unidrive --version`; `unidrive-mount --help`; `systemctl --user cat unidrive.service`; uninstall; assert clean removal |
| `test/rpm-install-in-docker.sh` | `fedora:40` | `rpm -i`; same assertions. Adds RHEL 10 when image available. |
| `test/aur-install-in-docker.sh` | `archlinux:latest` | Creates a non-root `build` user with passwordless sudo for `pacman`, then runs `makepkg -si --noconfirm` as `build` against a PKGBUILD pointing at a `file://` of the locally-built tarball. `makepkg` refuses to run as root by design. Then `unidrive --version`. |
| `test/tarball-install-in-docker.sh` | `debian:13` | `install.sh`; `~/.local/bin/unidrive --version`; `uninstall.sh`; assert clean removal (minus per-user state) |

### §8.3 Explicit non-tests

- **No live OAuth flow.** Covered by upstream `./gradlew check` and
  `cargo test`. Packaging tests verify packaging, not provider integration.
- **No real FUSE mount in CI.** Needs `--cap-add SYS_ADMIN` + `/dev/fuse`,
  often disallowed. `unidrive-mount --help` and `--check-kernel-floor` smokes
  catch the packaging-level failures (rpath, libfuse symbol resolution).
- **No kernel-floor failure test in CI.** Tested in `unidrive-mount-linux`'s
  own `cargo test`.
- **No repo-server integration test.** krost-infra has its own validation
  via `docker compose config -q` plus the existing krost-infra smoke. The
  contract between `unidrive-dist` and krost-infra is rsync-over-SSH.

### §8.4 End-to-end manual smoke

One-time per first release (and per new distro added to the matrix):

```bash
# On a fresh Ubuntu 24.04 LTS VM (or 26.04 once GA):
curl -fsSL https://unidrive.krost.org/install/unidrive-releases.gpg \
  | sudo tee /etc/apt/keyrings/unidrive.gpg > /dev/null
echo "deb [signed-by=/etc/apt/keyrings/unidrive.gpg] \
  https://apt.unidrive.krost.org stable main" \
  | sudo tee /etc/apt/sources.list.d/unidrive.list
sudo apt update && sudo apt install unidrive

systemctl --user enable --now unidrive.service
unidrive auth                                # real OAuth
unidrive sync ~/cloud --provider onedrive
unidrive mount ~/cloud                       # real FUSE mount
ls ~/cloud
cat ~/cloud/<some-file>                      # triggers hydration
fusermount3 -u ~/cloud
sudo apt remove unidrive
```

Documented in `unidrive-dist/docs/release-process.md`. Not re-run for routine
PATCH releases.

### §8.5 Dev loop

```bash
# In unidrive-dist/, against local checkouts of the sibling repos:
./test/local-build.sh ../unidrive ../unidrive-mount-linux
```

The script accepts the two sibling-repo paths as arguments, but
`unidrive-dist/README.md` documents the canonical layout
(`../unidrive` and `../unidrive-mount-linux` siblings of `../unidrive-dist`)
so first-time contributors can clone-three-and-go without reading the
script.

That script:

1. Builds the JAR (`cd ../unidrive/core && ./gradlew :app:cli:shadowJar -q`)
2. Builds the Rust binary (`cd ../unidrive-mount-linux && cargo build --release`)
3. Stages artefacts as if downloaded from GH Releases
4. Runs all packaging recipes against the staged tree
5. Runs all Docker smoke tests

Lets a packaging-recipe change iterate without cutting any tags.

### §8.6 CI scope

- **GH Actions on PR for unidrive-dist** → `test/smoke-all.sh` matrix (one
  job per distro container). Blocks merge.
- **GH Actions on tag for unidrive-dist** → `release/release.sh`. Requires
  sibling-repo tags to exist; fails fast otherwise.
- **No nightly builds, no scheduled scans, no Dependabot for packaging
  recipes.** Distro packaging changes at the cadence of upstream releases;
  nothing autonomous to keep current.

## §9. Out of scope for MVP (deferred to BACKLOG)

Filed as design constraints in unidrive-dist's `BACKLOG.md`, not work items:

- **Snap and Flatpak channels.** Per §4.5.
- **Reproducible builds.** Rust tarballs aren't byte-reproducible
  (timestamp-in-objects). Achievable but expensive; defer.
- **Multi-arch beyond amd64/arm64.** No `riscv64`, no `armv7l`. JVM JAR runs
  on any arch with Java 21; the Rust binary doesn't ship for those.
- **`--version` build-info enrichment.** Per §3.5.
- **In-app auto-update.** Each channel uses its native one (`apt update`,
  `dnf update`, AUR helpers). No bespoke updater. Matches the "user
  explicitly chose" philosophy.
- **Telemetry-less download counters.** The README promises zero telemetry.
  GH Releases download counts and `apt`/`dnf` access-log aggregates are the
  only signal. No "anonymous install ping."
- **Hardware-backed signing key (HSM, YubiKey).** §5 puts the key on a
  krost-infra container volume with restricted permissions. Moving the key
  to hardware-backed storage (YubiKey plugged into the server, or an HSM
  service) is a security-strengthening follow-up.
- **Man page** (`/usr/share/man/man1/unidrive.1.gz`). `unidrive --help` is
  the canonical reference. A real man page generated from CLI help output is
  a follow-up.
- **Staging vs. production repos.** Single-environment for MVP. A staging
  apt repo (for e.g. testing signing rotation) is a follow-up if ever needed.

## §10. Acceptance criteria for MVP release

A release of `unidrive v0.0.1` is complete when **all** of:

1. `unidrive` tag `v0.0.1` exists; its GH Release contains
   `unidrive-0.0.1.jar` + `.sha256` + `.asc`. (Matches the existing
   `core/app/cli/build/libs/` artifact naming convention.)
2. `unidrive-mount-linux` tag `v0.0.1` exists; its GH Release contains
   `unidrive-mount-0.0.1-{x86_64,aarch64}.tar.gz` + `.sha256` + `.asc` per
   arch.
3. `unidrive-dist` tag `v0.0.1` exists; its GH Release contains the
   end-user tarball bundle for both arches.
4. `apt.unidrive.krost.org` serves a signed apt repo containing
   `unidrive_0.0.1-1_{amd64,arm64}.deb`.
5. `dnf.unidrive.krost.org` serves a signed dnf repo containing
   `unidrive-0.0.1-1.{x86_64,aarch64}.rpm`.
6. AUR `unidrive` package has `pkgver=0.0.1, pkgrel=1`, sources resolve,
   `makepkg -si` succeeds on a clean Arch system.
7. The end-to-end manual smoke from §8.4 passes on a fresh Ubuntu 24.04
   LTS VM using the apt repo (the canonical user journey). Ubuntu 26.04
   replaces this as the primary smoke target once it reaches GA.
8. `unidrive.krost.org/install/` has the public-key fingerprint and per-channel
   instructions.

## §11. Glossary

- **Channel** — a single distribution mechanism (tarball, apt, dnf, AUR).
- **Lockstep** — the convention that all three repos tag the same version
  for the same release.
- **`-pkgN` suffix** — the unidrive-dist-only tag suffix for packaging-only
  respins (e.g. `v0.0.1-pkg2`). Sibling repos never carry this suffix.
- **Triplet** — the three coupled repos (`unidrive`,
  `unidrive-mount-linux`, `unidrive-dist`).
- **Per-user state** — `~/.config/unidrive/`, `~/.local/share/unidrive/`,
  `~/.cache/unidrive/`. Never touched by packaging.
- **Hard floor** — kernel ≥ 6.9, libfuse ≥ 3.16. Enforced at runtime, not
  at install time.
