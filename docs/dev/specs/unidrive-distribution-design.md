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
│     unidrive-cli-X.Y.Z.jar  │       │     unidrive-mount-X.Y.Z-    │
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

### §3.3 Tagging protocol (the lockstep)

Cut releases in this fixed order. Deviation aborts the dist release.

```
1. unidrive @ main is green (./gradlew check) → git tag vX.Y.Z → push.
   GH Action builds shadowJar, signs, attaches to GH Release.

2. unidrive-mount-linux @ main green (cargo test) → git tag vX.Y.Z → push.
   GH Action cross-compiles x86_64 + aarch64, signs tarballs, attaches.

3. unidrive-dist @ main green (Docker smoke) → git tag vX.Y.Z → push.
   GH Action runs release/release.sh which:
     a. fetch-artefacts.sh requires vX.Y.Z to exist on both sibling repos.
        Fails fast if missing.
     b. SHA256 + GPG verify each artefact.
     c. Build .deb, .rpm, AUR source, tarball bundle.
     d. Sign packages + repo metadata with project key.
     e. publish-apt.sh / publish-dnf.sh rsync to krost-infra over SSH.
     f. publish-gh-release.sh uploads tarball bundle to dist's GH Release.
     g. publish-aur.sh ssh-pushes PKGBUILD update.
```

If steps 1 or 2 fail, no user-visible state has changed. If step 3 fails
mid-way, the publish-* scripts are idempotent — re-running with the same tag
is safe.

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

Cost: `fetch-artefacts.sh` must parse `v0.0.1-pkgN` as referring to upstream
tag `v0.0.1`. Trivial.

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
├── lib/unidrive-cli-X.Y.Z.jar
├── bin/unidrive-mount             ← architecture-specific
├── systemd/unidrive.service       ← systemd --user unit
├── doc/{README.md, LICENSE, NOTICE}
├── SHA256SUMS
└── SHA256SUMS.asc                 ← signed with project key
```

`install.sh` lays out:
- `~/.local/lib/unidrive/unidrive-cli-X.Y.Z.jar`
- `~/.local/lib/unidrive/unidrive-mount`
- `~/.local/bin/unidrive` (wrapper script)
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
Depends: openjdk-21-jre-headless | java21-runtime,
         libfuse3-3 (>= 3.16),
         fuse3
Recommends: systemd
Suggests: kde-cli-tools
Description: Multi-cloud sync daemon with sparse hydration (FUSE)
```

Install paths (FHS-compliant, system-wide):

```
/usr/lib/unidrive/unidrive-cli.jar
/usr/lib/unidrive/unidrive-mount
/usr/bin/unidrive                          ← wrapper
/usr/lib/systemd/user/unidrive.service     ← distro-shipped user unit
/usr/share/doc/unidrive/{README, LICENSE, NOTICE, changelog.Debian.gz}
/usr/share/man/man1/unidrive.1.gz          ← stub for MVP
```

`postinst` is idempotent, runs as root, calls
`systemctl --global daemon-reload || true`, and **does not** auto-enable or
auto-start the service.

The kernel-floor check (≥ 6.9) is enforced at runtime by `unidrive-mount`, not
at install time. Install-time would block legitimate use cases (kernel-upgrade
in progress, container image builds).

### §4.3 Channel 3: DNF (`.rpm`)

Target distros: Fedora 40+, RHEL 10, AlmaLinux/Rocky 10+.

**RHEL 9 caveat:** kernel 5.14 does not meet the 6.9 floor. The rpm installs
cleanly but `unidrive-mount` exits 78 on startup. RHEL 9 users are de-facto
unsupported. Documented in `unidrive-dist/docs/compatibility.md`.

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

Install paths match the deb layout. `%post` scriptlet calls
`systemctl --global daemon-reload || true`. No auto-enable.

### §4.4 Channel 4: AUR (`PKGBUILD`)

Source repo: `aur.archlinux.org/unidrive.git` — separate AUR-hosted git repo,
pushed to by `unidrive-dist` via SSH deploy key.

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

- **Java 21+ requirement** across all four channels. The `--enable-native-access=ALL-UNNAMED`
  flag from the current install.sh must be preserved in every channel's
  wrapper script. Failure mode this avoids: silent `IllegalAccessException`
  at runtime when a future JDK tightens FFI policy.
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

## §5. GPG signing

A project-specific key, **not** the maintainer's personal key.

```
Name:        unidrive-releases
Email:       releases@unidrive.krost.org
Fingerprint: <to be generated>
Usage:       sign-only (no encryption, no certify-others)
```

Stored in a restricted secret store on the krost-infra server. The dist CI
runner accesses it via a chrooted SSH user (`pkg-publisher`) with rsync-only
permissions to `~/docker/pkg-server/pkg-data/`. The signing itself happens
in CI by detaching the signing operation to a remote signing-service call
(simpler MVP: the CI runner pulls the key into ephemeral memory for the
duration of the release, signs, scrubs).

(Choice between in-CI signing and a remote signing service is an
implementation-plan question, not a spec question. MVP starts with in-CI
signing; a remote signing-service migration is BACKLOG.)

Rationale for project-specific identity:

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
│   ├── unidrive.repo
│   ├── unidrive-releases.gpg
│   ├── repodata/{repomd.xml, repomd.xml.asc, ...}
│   └── {x86_64,aarch64}/unidrive-X.Y.Z-N.{x86_64,aarch64}.rpm
├── backups/<UTC-timestamp>/  ← rolling, kept last 5
└── health                    ← "ok\n" for the container healthcheck
```

### §7.4 DNS records

Add via manual DNS update (one-time):

```
apt.unidrive.krost.org  IN A  87.106.246.31
dnf.unidrive.krost.org  IN A  87.106.246.31
```

`unidrive.krost.org` itself is already deployed (served by the existing
`site-unidrive` container). Its content gets an `/install/` page describing
how to add the apt/dnf repo and the public key fingerprint.

### §7.5 SSH deploy account

The dist CI runner rsyncs to a dedicated SSH user `pkg-publisher` on the
krost-infra server, chrooted to `~/docker/pkg-server/pkg-data/`. The deploy
private key lives in GH Actions secrets as `DIST_DEPLOY_KEY`. User setup is
added to `krost-infra/setup-server.sh`.

### §7.6 krost-infra documentation updates

Per krost-infra AGENTS.md "Documentation-Stack Sync Rules":

- `CLAUDE.md` services table → add `pkg-server`
- `CLAUDE.md` architecture diagram → add the new container
- `DEPLOY.md` services table → add `apt.unidrive.krost.org` and
  `dnf.unidrive.krost.org` rows
- `DEPLOY.md` DNS table → add the two A records
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
| `test/deb-install-in-docker.sh` | `ubuntu:26.04` | `dpkg -i unidrive_*.deb`; assert install paths; `unidrive --version`; `unidrive-mount --help`; `systemctl --user cat unidrive.service`; uninstall; assert clean removal |
| `test/rpm-install-in-docker.sh` | `fedora:40` | `rpm -i`; same assertions. Adds RHEL 10 when image available. |
| `test/aur-install-in-docker.sh` | `archlinux:latest` | `makepkg -si` against PKGBUILD pointing at a file:// of the locally-built tarball; `unidrive --version` |
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
# On a fresh Ubuntu 26.04 VM:
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
- **Remote signing service.** §5 starts with in-CI signing. Migration to a
  dedicated signing daemon (e.g. on the krost-infra server) is a BACKLOG
  item if security review demands it.
- **Staging vs. production repos.** Single-environment for MVP. A staging
  apt repo (for e.g. testing signing rotation) is a follow-up if ever needed.

## §10. Acceptance criteria for MVP release

A release of `unidrive v0.0.1` is complete when **all** of:

1. `unidrive` tag `v0.0.1` exists; its GH Release contains
   `unidrive-cli-0.0.1.jar` + `.sha256` + `.asc`.
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
7. The end-to-end manual smoke from §8.4 passes on a fresh Ubuntu 26.04
   VM using the apt repo (the canonical user journey).
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
