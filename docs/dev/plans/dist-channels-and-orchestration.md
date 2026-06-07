# Plan P4b: unidrive-dist deb + rpm + AUR + Release Orchestration + Smoke Matrix

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Cross-repo note:** Plan describes work in the **`unidrive-dist`** repo (created in Plan P4a). Plan file lives in `unidrive/docs/dev/plans/` per the convention established by P4a.

**Goal:** Complete the unidrive-dist repo by adding the three remaining packaging channels (`.deb`, `.rpm`, AUR `PKGBUILD`), the `release/` orchestration scripts (`sign-packages.sh`, `publish-apt.sh`, `publish-dnf.sh`, `publish-aur.sh`, `publish-gh-release.sh`, `release.sh`), the Docker smoke matrix (`test/{deb,rpm,aur,tarball}-install-in-docker.sh`, `test/smoke-all.sh`), the GH Actions CI (`.github/workflows/{release,test-packaging}.yml`), and the AUR bot-account setup.

**Architecture:** Each channel recipe is self-contained in `packaging/<channel>/`. The build scripts consume artefacts staged by `release/fetch-artefacts.sh` (from P4a) and produce unsigned packages under `build/<channel>/`. `sign-packages.sh` rsyncs unsigned packages to `pkg-signer` on the krost-infra VPS, triggers `sign-drop`, and rsyncs the signed copies back. Per-channel `publish-*.sh` scripts then push to their destinations: apt+dnf via rsync to `pkg-publisher@VPS`, AUR via SSH-keyed git push to `aur@aur.archlinux.org`, GH Release via `gh release upload`. `release.sh` is the top-level orchestrator. The Docker smoke matrix exercises each channel's install path in its target distro container.

**Tech Stack:** Bash, `dpkg-deb` + `debhelper`-style metadata, `rpmbuild`, `makepkg` (AUR), `gh` CLI, `rsync` over SSH, `gpg`, `sha256sum`, Docker (for smoke).

**Spec reference:** §4.2 (.deb), §4.3 (.rpm), §4.4 (AUR), §4.6 (cross-channel), §4.7 (RELEASES.md → distro changelog conversion), §5.1 (pkg-signer), §5.2 (signing flow), §6 (repo layout — remaining items), §7.3.1 (`unidrive.repo`), §8 (testing).

**Scope explicitly NOT in this plan:**

- No first-release content in `RELEASES.md` (Plan P5).
- No real publish to live apt/dnf repos beyond a one-time dry run (Plan P5 does the real publish).
- No DNS or krost-infra changes (Plan P3 owns those).
- No first AUR push (Plan P5 — needs the bot account credentials live and the real signed artefact).

**Pre-flight assumptions:**

- Plan P4a is merged on `unidrive-dist/main`. The repo skeleton exists; `release/fetch-artefacts.sh` works.
- Plan P3 is executed: `pkg-server`, `pkg-signer`, DNS, SSH users, GPG key all live on the VPS. `DIST_DEPLOY_KEY` + `SIGNER_DEPLOY_KEY` + `GPG_PRIVATE_KEY` + `GPG_KEY_FINGERPRINT` GH secrets exist on the unidrive-dist repo.
- The maintainer has admin rights on the unidrive-dist repo (to add `AUR_BOT_KEY` later in this plan).
- Sibling artefacts can be produced locally via `test/local-build.sh` (P4a Task 9) for dev-loop testing.

---

## File Structure

All paths in `unidrive-dist/`.

| Path | Purpose |
|---|---|
| `packaging/deb/debian/control` | Package metadata |
| `packaging/deb/debian/rules` | debhelper rules |
| `packaging/deb/debian/postinst` | (empty body per spec §4.2 fix) |
| `packaging/deb/debian/prerm` | (empty — no auto-stop of user services) |
| `packaging/deb/debian/copyright` | Apache-2.0 |
| `packaging/deb/debian/unidrive.install` | Install paths manifest |
| `packaging/deb/unidrive.service` | systemd-user unit, installed to `/usr/lib/systemd/user/` |
| `packaging/deb/build-deb.sh` | Build script |
| `packaging/rpm/unidrive.spec` | RPM spec file |
| `packaging/rpm/build-rpm.sh` | Build script |
| `packaging/aur/PKGBUILD.template` | PKGBUILD with placeholders for version/sha256 |
| `packaging/aur/build-aur-srcinfo.sh` | Renders PKGBUILD + .SRCINFO from template |
| `release/sign-packages.sh` | rsync to pkg-signer → trigger sign-drop → rsync back |
| `release/publish-apt.sh` | rsync to pkg-publisher VPS path for apt |
| `release/publish-dnf.sh` | rsync to pkg-publisher VPS path for dnf |
| `release/publish-aur.sh` | SSH-keyed git push to AUR |
| `release/publish-gh-release.sh` | `gh release upload` of the tarball bundle |
| `release/release.sh` | Top-level orchestrator |
| `release/render-changelog.sh` | RELEASES.md → debian/changelog + rpm %changelog |
| `repo-server/apt/Release.template` | apt Release-file skeleton |
| `repo-server/dnf/.gitkeep` | (createrepo writes here at publish time) |
| `repo-server/README.md` | How krost-infra consumes this directory |
| `test/deb-install-in-docker.sh` | Ubuntu 24.04 smoke |
| `test/rpm-install-in-docker.sh` | Fedora 40 smoke |
| `test/aur-install-in-docker.sh` | archlinux:latest smoke |
| `test/tarball-install-in-docker.sh` | Debian 13 smoke |
| `test/smoke-all.sh` | The gate |
| `.github/workflows/test-packaging.yml` | PR-triggered smoke matrix |
| `.github/workflows/release.yml` | Tag-triggered orchestration |

Extended in P4a's `test/local-build.sh`:
- Call deb / rpm / AUR build scripts after the tarball, so the dev loop covers all four channels.

---

## Task 0: Branch + prerequisites

- [ ] **Step 1: Working branch**

```bash
cd /home/gernot/dev/git/unidrive-dist
git checkout main && git pull
git checkout -b feature/channels-and-orchestration
```

Expected: branch created off latest main.

- [ ] **Step 2: Verify P4a state**

```bash
test -f release/fetch-artefacts.sh \
  && test -f packaging/tarball/build-tarball.sh \
  && test -f test/local-build.sh \
  && test -f AGENTS.md \
  && echo "P4a state ok"
```

Expected: `P4a state ok`. If anything missing, P4a is incomplete — go back and finish P4a first.

- [ ] **Step 3: Verify dev-loop tarball build still works**

```bash
./test/local-build.sh
ls -la build/tarball/
```

Expected: a `unidrive-0.0.1-linux-x86_64.tar.gz` and its `.sha256`. This is the baseline this plan extends.

---

## Task 1: deb channel — control + rules + scripts

**Files:**
- Create: `packaging/deb/debian/control`
- Create: `packaging/deb/debian/rules`
- Create: `packaging/deb/debian/postinst`
- Create: `packaging/deb/debian/prerm`
- Create: `packaging/deb/debian/copyright`
- Create: `packaging/deb/debian/unidrive.install`
- Create: `packaging/deb/unidrive.service`

- [ ] **Step 1: Make the deb tree**

```bash
mkdir -p packaging/deb/debian
```

- [ ] **Step 2: `packaging/deb/debian/control`**

Create with this content:

```
Source: unidrive
Section: utils
Priority: optional
Maintainer: UniDrive Releases <releases@unidrive.krost.org>
Build-Depends: debhelper-compat (= 13)
Standards-Version: 4.6.0
Homepage: https://unidrive.krost.org
Vcs-Browser: https://github.com/gkrost/unidrive-dist
Vcs-Git: https://github.com/gkrost/unidrive-dist.git

Package: unidrive
Architecture: amd64 arm64
Depends: ${misc:Depends},
         openjdk-21-jre-headless | java-runtime-headless (>= 21),
         libfuse3-3 (>= 3.16),
         fuse3
Recommends: systemd
Suggests: kde-cli-tools
Description: Multi-cloud sync daemon with sparse hydration (FUSE)
 UniDrive is a zero-telemetry Linux daemon that synchronises files
 between cloud providers (Internxt, OneDrive) and a local FUSE mount
 with sparse-hydration. The mount is materialised on demand using
 FUSE_PASSTHROUGH on kernel >= 6.9.
 .
 This package contains the JVM CLI and the Rust FUSE co-daemon
 (unidrive-mount).
```

- [ ] **Step 3: `packaging/deb/debian/rules`**

```bash
cat > packaging/deb/debian/rules <<'RULES'
#!/usr/bin/make -f
# UniDrive deb-build rules. Most heavy lifting is done by build-deb.sh
# which stages files into debian/tmp; dh installs from there.

%:
	dh $@

# We provide files already; no compile step.
override_dh_auto_build:

override_dh_auto_test:

override_dh_strip:
	# unidrive-mount is a pre-built Rust binary already stripped at
	# build time on the sibling repo's release CI. dh_strip would
	# re-run strip and fail on the JAR (which isn't an ELF).
RULES
chmod +x packaging/deb/debian/rules
```

- [ ] **Step 4: `packaging/deb/debian/postinst` (essentially empty per spec §4.2)**

```bash
cat > packaging/deb/debian/postinst <<'POSTINST'
#!/bin/sh
# UniDrive postinst.
#
# Per spec §4.2: systemd auto-discovers user units under
# /usr/lib/systemd/user/ on the next user login. No daemon-reload
# needed. `systemctl --user daemon-reload` from root targets root's
# own session, which is the wrong thing. `systemctl --global
# daemon-reload` is not a valid command.
#
# This script does not auto-enable or auto-start anything. The user
# enables `unidrive.service` explicitly when ready.

set -e

#DEBHELPER#

exit 0
POSTINST
chmod +x packaging/deb/debian/postinst
```

- [ ] **Step 5: `packaging/deb/debian/prerm`**

```bash
cat > packaging/deb/debian/prerm <<'PRERM'
#!/bin/sh
# UniDrive prerm.
#
# No auto-stop of user services. If the user has the service running,
# they will get an error when they next reboot or manually restart
# without the package's files; that's the correct behaviour for a
# removal initiated outside their session.

set -e

#DEBHELPER#

exit 0
PRERM
chmod +x packaging/deb/debian/prerm
```

- [ ] **Step 6: `packaging/deb/debian/copyright`**

```bash
cat > packaging/deb/debian/copyright <<'COPYRIGHT'
Format: https://www.debian.org/doc/packaging-manuals/copyright-format/1.0/
Upstream-Name: unidrive
Upstream-Contact: UniDrive Releases <releases@unidrive.krost.org>
Source: https://github.com/gkrost/unidrive

Files: *
Copyright: 2024-present, Gernot Krost <gernot@krost.org>
License: Apache-2.0
 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at
 .
     http://www.apache.org/licenses/LICENSE-2.0
 .
 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 .
 On Debian systems, the complete text of the Apache 2.0 license can
 be found in `/usr/share/common-licenses/Apache-2.0'.
COPYRIGHT
```

- [ ] **Step 7: `packaging/deb/debian/unidrive.install`**

This file tells `dh_install` which files in `debian/tmp/` go into the binary package and where.

```
usr/lib/unidrive/unidrive.jar
usr/lib/unidrive/unidrive-mount
usr/bin/unidrive
usr/lib/systemd/user/unidrive.service
usr/share/doc/unidrive/README.md
usr/share/doc/unidrive/LICENSE
usr/share/doc/unidrive/NOTICE
```

- [ ] **Step 8: `packaging/deb/unidrive.service` (system-installed user unit)**

```ini
[Unit]
Description=UniDrive cloud storage sync daemon
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=%h/.local/bin/unidrive sync --watch
EnvironmentFile=-%h/.config/unidrive/vault-env
SuccessExitStatus=143
Restart=on-failure
RestartSec=30

[Install]
WantedBy=default.target
```

Wait — this is the *system-installed* path version. The `ExecStart` should call `/usr/bin/unidrive`, not `~/.local/bin/unidrive`. Replace `%h/.local/bin/unidrive` with `/usr/bin/unidrive`:

```bash
cat > packaging/deb/unidrive.service <<'UNIT'
[Unit]
Description=UniDrive cloud storage sync daemon
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=/usr/bin/unidrive sync --watch
EnvironmentFile=-%h/.config/unidrive/vault-env
SuccessExitStatus=143
Restart=on-failure
RestartSec=30

[Install]
WantedBy=default.target
UNIT
```

- [ ] **Step 9: Commit**

```bash
git add packaging/deb/
git commit -m "$(cat <<'EOF'
packaging/deb: control + rules + postinst/prerm + install manifest + service

Apache-2.0 copyright file, debhelper compat=13, postinst empty per
spec §4.2 (systemd auto-discovers user units; no daemon-reload from
root). System-installed user unit at /usr/lib/systemd/user/ calls
/usr/bin/unidrive (FHS-compliant absolute path) rather than the
per-user wrapper.

Per spec §4.2.
EOF
)"
```

---

## Task 2: deb channel — `build-deb.sh`

**Files:**
- Create: `packaging/deb/build-deb.sh`

- [ ] **Step 1: Write the build script**

```bash
cat > packaging/deb/build-deb.sh <<'BUILDSH'
#!/usr/bin/env bash
#
# packaging/deb/build-deb.sh
#
# Usage:
#   packaging/deb/build-deb.sh <semver> <pkgrel> <arch>
#
# Reads:
#   artefacts/jvm/unidrive-<semver>.jar
#   artefacts/mount/unidrive-mount-<semver>-<deb-arch>.tar.gz
#   packaging/deb/**
#   packaging/tarball/install.sh (for the wrapper logic)
#   RELEASES.md (for debian/changelog generation)
#
# Writes:
#   build/deb/unidrive_<semver>-<pkgrel>_<deb-arch>.deb
#
# Where <arch> is "amd64" or "arm64" (deb arch names; the Rust
# tarball uses "x86_64" or "aarch64").

set -euo pipefail

[[ $# -eq 3 ]] || { echo "usage: $0 <semver> <pkgrel> <arch>" >&2; exit 64; }
SEMVER=$1
PKGREL=$2
DEB_ARCH=$3

case "$DEB_ARCH" in
    amd64)  RUST_ARCH=x86_64 ;;
    arm64)  RUST_ARCH=aarch64 ;;
    *) echo "ERROR: deb arch must be amd64 or arm64 (got: $DEB_ARCH)" >&2; exit 64 ;;
esac

REPO_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$REPO_ROOT"

# Inputs.
JAR=artefacts/jvm/unidrive-${SEMVER}.jar
MOUNT_TARBALL=artefacts/mount/unidrive-mount-${SEMVER}-${RUST_ARCH}.tar.gz
test -f "$JAR" || { echo "ERROR: missing $JAR" >&2; exit 1; }
test -f "$MOUNT_TARBALL" || { echo "ERROR: missing $MOUNT_TARBALL" >&2; exit 1; }

# Stage.
OUT=build/deb
WORK=$OUT/work-${DEB_ARCH}
rm -rf "$WORK"
mkdir -p "$WORK/debian"

# Copy the debian/ tree.
cp -r packaging/deb/debian/. "$WORK/debian/"

# Render debian/changelog from RELEASES.md.
release/render-changelog.sh deb "$SEMVER" "$PKGREL" > "$WORK/debian/changelog"

# Stage the binary tree under debian/tmp/ (dh_install reads .install
# manifest from this layout).
TMPTREE=$WORK/debian/tmp
mkdir -p \
    "$TMPTREE/usr/lib/unidrive" \
    "$TMPTREE/usr/bin" \
    "$TMPTREE/usr/lib/systemd/user" \
    "$TMPTREE/usr/share/doc/unidrive"

# JAR — installed as unidrive.jar (unversioned).
cp "$JAR" "$TMPTREE/usr/lib/unidrive/unidrive.jar"

# unidrive-mount binary.
mount_extract=$(mktemp -d)
tar -C "$mount_extract" -xzf "$MOUNT_TARBALL"
cp "$mount_extract/unidrive-mount-${SEMVER}/unidrive-mount" \
   "$TMPTREE/usr/lib/unidrive/unidrive-mount"
chmod 0755 "$TMPTREE/usr/lib/unidrive/unidrive-mount"
rm -rf "$mount_extract"

# Wrapper script for /usr/bin/unidrive.
cat > "$TMPTREE/usr/bin/unidrive" <<'WRAP'
#!/usr/bin/env bash
# /usr/bin/unidrive — UniDrive CLI wrapper (deb).
# Selects the JVM flag form based on detected Java major version.
# Per spec §4.6: --enable-native-access=ALL-UNNAMED is JDK 21–22
# syntax; JDK 23+ may require a module name. MVP supports JDK 21
# only; this wrapper future-proofs without committing to a fix.
set -euo pipefail
JAR=/usr/lib/unidrive/unidrive.jar
JAVA=${JAVA:-java}
exec "$JAVA" -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
     --enable-native-access=ALL-UNNAMED \
     -jar "$JAR" "$@"
WRAP
chmod 0755 "$TMPTREE/usr/bin/unidrive"

# systemd user unit.
cp packaging/deb/unidrive.service \
   "$TMPTREE/usr/lib/systemd/user/unidrive.service"

# Docs.
cp LICENSE NOTICE "$TMPTREE/usr/share/doc/unidrive/"
if [[ -f ../unidrive/README.md ]]; then
    cp ../unidrive/README.md "$TMPTREE/usr/share/doc/unidrive/README.md"
else
    printf 'UniDrive %s — see https://unidrive.krost.org/\n' "$SEMVER" \
        > "$TMPTREE/usr/share/doc/unidrive/README.md"
fi

# Build.
( cd "$WORK" && dpkg-buildpackage -us -uc -b -a"$DEB_ARCH" --no-sign )

# Move the .deb out of the parent dir.
DEB=$OUT/unidrive_${SEMVER}-${PKGREL}_${DEB_ARCH}.deb
mv "$OUT"/unidrive_${SEMVER}-${PKGREL}_${DEB_ARCH}.deb "$DEB" 2>/dev/null \
    || mv "$OUT"/work-*/../*.deb "$DEB" 2>/dev/null \
    || mv "$OUT"/*.deb "$DEB" 2>/dev/null \
    || true
test -f "$DEB" || {
    echo "ERROR: didn't find produced .deb. Look under $OUT/" >&2
    find "$OUT" -name '*.deb' >&2
    exit 1
}
sha256sum "$DEB" > "${DEB}.sha256"

# Cleanup.
rm -rf "$WORK"

echo "[build-deb] produced:"
ls -la "$DEB" "${DEB}.sha256"
BUILDSH
chmod +x packaging/deb/build-deb.sh
```

- [ ] **Step 2: Shellcheck**

```bash
shellcheck packaging/deb/build-deb.sh
```

Expected: no SC2086 / no unquoted-var warnings. If any, fix inline.

- [ ] **Step 3: Commit**

```bash
git add packaging/deb/build-deb.sh
git commit -m "packaging/deb: build-deb.sh — dpkg-buildpackage driver"
```

---

## Task 3: `release/render-changelog.sh`

This is shared by deb and rpm builds. Reads `RELEASES.md` and emits either Debian-style changelog (one stanza) or rpm `%changelog` (one stanza).

**Files:**
- Create: `release/render-changelog.sh`

- [ ] **Step 1: Write the script**

```bash
cat > release/render-changelog.sh <<'CHGSH'
#!/usr/bin/env bash
#
# release/render-changelog.sh
#
# Usage:
#   release/render-changelog.sh deb <semver> <pkgrel>
#   release/render-changelog.sh rpm <semver> <release>
#   release/render-changelog.sh gh  <semver>
#
# Reads RELEASES.md (top-of-file format defined in spec §4.7) and
# emits one stanza in the requested distro format on stdout.
#
# RELEASES.md section header pattern:  ^## <semver> — <name>
# Bullet lines under the section:      ^- <text>

set -euo pipefail

[[ $# -ge 2 ]] || { echo "usage: $0 <deb|rpm|gh> <semver> [<release>]" >&2; exit 64; }
FORMAT=$1
SEMVER=$2
PKGREL=${3:-1}

REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)
RELEASES_MD=$REPO_ROOT/RELEASES.md
test -f "$RELEASES_MD" || { echo "ERROR: $RELEASES_MD missing" >&2; exit 1; }

# Extract the section for $SEMVER.
SECTION=$(awk -v ver="$SEMVER" '
    /^## / {
        in_section = ($0 ~ ("^## " ver " ")) || ($0 == ("## " ver))
        if (in_section) next
    }
    /^## / && in_section { exit }
    in_section { print }
' "$RELEASES_MD")

if [[ -z $SECTION ]]; then
    echo "ERROR: no section for version $SEMVER in $RELEASES_MD" >&2
    exit 1
fi

# Collect bullet lines (lines starting with "- ").
BULLETS=$(printf '%s\n' "$SECTION" | grep -E '^- ' || true)
if [[ -z $BULLETS ]]; then
    echo "ERROR: section for $SEMVER has no bullet lines" >&2
    exit 1
fi

MAINTAINER='UniDrive Releases <releases@unidrive.krost.org>'

case "$FORMAT" in
    deb)
        # Debian changelog stanza.
        # Format: package (version-pkgrel) distribution; urgency=low
        #         <blank>
        #           * bullet
        #         <blank>
        #          -- Maintainer  date
        DATE=$(date -u -R)  # RFC 5322 / 2822 date
        printf 'unidrive (%s-%s) unstable; urgency=low\n\n' "$SEMVER" "$PKGREL"
        printf '%s\n' "$BULLETS" | sed 's/^- /  * /'
        printf '\n -- %s  %s\n' "$MAINTAINER" "$DATE"
        ;;
    rpm)
        # rpm %changelog stanza.
        # Format: * Day Mon DD YYYY Maintainer - version-release
        #         - bullet
        DATE=$(date -u +'%a %b %d %Y')
        printf '* %s %s - %s-%s\n' "$DATE" "$MAINTAINER" "$SEMVER" "$PKGREL"
        printf '%s\n' "$BULLETS"
        ;;
    gh)
        # GH Release body: just the section content as markdown.
        printf '%s\n' "$SECTION"
        ;;
    *)
        echo "ERROR: unknown format '$FORMAT' (use deb|rpm|gh)" >&2
        exit 64
        ;;
esac
CHGSH
chmod +x release/render-changelog.sh
```

- [ ] **Step 2: Test against a stub RELEASES.md entry**

```bash
# Temporarily seed RELEASES.md with a sample section.
cat > /tmp/RELEASES.md.bak <<'EOF'
# Releases

## 0.0.0-test — render-changelog smoke

### unidrive (JVM)
- Sample bullet one
- Sample bullet two

### Packaging
- Sample packaging bullet
EOF
mv RELEASES.md RELEASES.md.orig
cp /tmp/RELEASES.md.bak RELEASES.md

# Smoke deb format.
release/render-changelog.sh deb 0.0.0-test 1
echo --- RPM ---
release/render-changelog.sh rpm 0.0.0-test 1
echo --- GH ---
release/render-changelog.sh gh 0.0.0-test

# Restore.
mv RELEASES.md.orig RELEASES.md
rm /tmp/RELEASES.md.bak
```

Expected:

- deb form: `unidrive (0.0.0-test-1) unstable; urgency=low` header, three `  * ...` bullet lines, RFC-5322 maintainer trailer.
- rpm form: `* <date> UniDrive Releases <...> - 0.0.0-test-1` header, three `- ...` bullets.
- gh form: the whole markdown section verbatim.

Note: the section header parsing matches `## 0.0.0-test ` (with a trailing space or end-of-line). The bullets the script collects include all `- ` lines under the section, which includes the bullets inside `### unidrive (JVM)` and `### Packaging`. That's the intended behaviour — the distro-format changelog includes everything for that release.

- [ ] **Step 3: Shellcheck**

```bash
shellcheck release/render-changelog.sh
```

- [ ] **Step 4: Commit**

```bash
git add release/render-changelog.sh
git commit -m "$(cat <<'EOF'
release: render-changelog.sh — RELEASES.md → deb/rpm/gh formats

One script, three output forms. Spec §4.7 defines the format; the
script extracts the section for a given semver and emits the requested
distro-native form on stdout. Used by build-deb.sh, build-rpm.sh,
and publish-gh-release.sh.
EOF
)"
```

---

## Task 4: rpm channel — spec + build

**Files:**
- Create: `packaging/rpm/unidrive.spec`
- Create: `packaging/rpm/build-rpm.sh`

- [ ] **Step 1: Make the rpm dir**

```bash
mkdir -p packaging/rpm
```

- [ ] **Step 2: `packaging/rpm/unidrive.spec`**

```bash
cat > packaging/rpm/unidrive.spec <<'SPEC'
Name:           unidrive
Version:        @VERSION@
Release:        @RELEASE@%{?dist}
Summary:        Multi-cloud sync daemon with sparse hydration (FUSE)
License:        ASL 2.0
URL:            https://unidrive.krost.org
BuildArch:      @BUILD_ARCH@

# Sources are pre-built; we don't use %prep / %build.
# Pass --define "build_root <path>" pointing at the staged tree.
%global _build_id_links none

Requires:       java-21-openjdk-headless
Requires:       fuse3-libs >= 3.16
Requires:       fuse3

%description
UniDrive is a zero-telemetry Linux daemon that synchronises files
between cloud providers (Internxt, OneDrive) and a local FUSE mount
with sparse-hydration. The mount is materialised on demand using
FUSE_PASSTHROUGH on kernel >= 6.9.

This package contains the JVM CLI and the Rust FUSE co-daemon
(unidrive-mount).

%install
# build-rpm.sh has already laid out the staged tree under
# %{_buildrootdir}; just propagate it.
mkdir -p %{buildroot}
cp -a @STAGED_TREE@/. %{buildroot}/

%files
%license /usr/share/doc/unidrive/LICENSE
%doc     /usr/share/doc/unidrive/NOTICE
%doc     /usr/share/doc/unidrive/README.md
/usr/lib/unidrive/unidrive.jar
/usr/lib/unidrive/unidrive-mount
/usr/bin/unidrive
/usr/lib/systemd/user/unidrive.service

%post
# Per spec §4.2: empty %post — systemd auto-discovers user units.

%preun
# No action — see /usr/share/doc/unidrive/README.md for manual stop.

%changelog
@CHANGELOG@
SPEC
```

The `@VERSION@`, `@RELEASE@`, `@BUILD_ARCH@`, `@STAGED_TREE@`, `@CHANGELOG@` placeholders are substituted by `build-rpm.sh` at build time.

- [ ] **Step 3: `packaging/rpm/build-rpm.sh`**

```bash
cat > packaging/rpm/build-rpm.sh <<'BUILDRPM'
#!/usr/bin/env bash
#
# packaging/rpm/build-rpm.sh
#
# Usage:
#   packaging/rpm/build-rpm.sh <semver> <release> <arch>
#
# Where <arch> is "x86_64" or "aarch64" (matches both rpm and Rust).
#
# Reads:
#   artefacts/jvm/unidrive-<semver>.jar
#   artefacts/mount/unidrive-mount-<semver>-<arch>.tar.gz
#   packaging/rpm/unidrive.spec  (template with @VAR@ placeholders)
#   RELEASES.md
#
# Writes:
#   build/rpm/unidrive-<semver>-<release>.<arch>.rpm

set -euo pipefail

[[ $# -eq 3 ]] || { echo "usage: $0 <semver> <release> <arch>" >&2; exit 64; }
SEMVER=$1
RELEASE=$2
ARCH=$3

case "$ARCH" in
    x86_64|aarch64) : ;;
    *) echo "ERROR: arch must be x86_64 or aarch64 (got: $ARCH)" >&2; exit 64 ;;
esac

REPO_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$REPO_ROOT"

JAR=artefacts/jvm/unidrive-${SEMVER}.jar
MOUNT_TARBALL=artefacts/mount/unidrive-mount-${SEMVER}-${ARCH}.tar.gz
test -f "$JAR" || { echo "ERROR: missing $JAR" >&2; exit 1; }
test -f "$MOUNT_TARBALL" || { echo "ERROR: missing $MOUNT_TARBALL" >&2; exit 1; }

OUT=build/rpm
WORK=$OUT/work-${ARCH}
rm -rf "$WORK"
mkdir -p "$WORK"/{SPECS,SOURCES,BUILD,RPMS,SRPMS,BUILDROOT}

# Stage the binary tree.
TREE=$WORK/tree
mkdir -p \
    "$TREE/usr/lib/unidrive" \
    "$TREE/usr/bin" \
    "$TREE/usr/lib/systemd/user" \
    "$TREE/usr/share/doc/unidrive"

cp "$JAR" "$TREE/usr/lib/unidrive/unidrive.jar"

mount_extract=$(mktemp -d)
tar -C "$mount_extract" -xzf "$MOUNT_TARBALL"
cp "$mount_extract/unidrive-mount-${SEMVER}/unidrive-mount" \
   "$TREE/usr/lib/unidrive/unidrive-mount"
chmod 0755 "$TREE/usr/lib/unidrive/unidrive-mount"
rm -rf "$mount_extract"

cat > "$TREE/usr/bin/unidrive" <<'WRAP'
#!/usr/bin/env bash
set -euo pipefail
JAR=/usr/lib/unidrive/unidrive.jar
JAVA=${JAVA:-java}
exec "$JAVA" -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
     --enable-native-access=ALL-UNNAMED \
     -jar "$JAR" "$@"
WRAP
chmod 0755 "$TREE/usr/bin/unidrive"

# Reuse the deb's service file (identical FHS layout).
cp packaging/deb/unidrive.service \
   "$TREE/usr/lib/systemd/user/unidrive.service"

cp LICENSE NOTICE "$TREE/usr/share/doc/unidrive/"
if [[ -f ../unidrive/README.md ]]; then
    cp ../unidrive/README.md "$TREE/usr/share/doc/unidrive/README.md"
else
    printf 'UniDrive %s — see https://unidrive.krost.org/\n' "$SEMVER" \
        > "$TREE/usr/share/doc/unidrive/README.md"
fi

# Render changelog into a tempfile (multi-line content can't pass
# through sed safely).
CHGLOG=$WORK/changelog.txt
release/render-changelog.sh rpm "$SEMVER" "$RELEASE" > "$CHGLOG"

# Substitute placeholders in the spec.
SPEC=$WORK/SPECS/unidrive.spec
awk -v ver="$SEMVER" \
    -v rel="$RELEASE" \
    -v arch="$ARCH" \
    -v tree="$TREE" \
    -v chgfile="$CHGLOG" '
    {
        gsub(/@VERSION@/, ver)
        gsub(/@RELEASE@/, rel)
        gsub(/@BUILD_ARCH@/, arch)
        gsub(/@STAGED_TREE@/, tree)
    }
    /^@CHANGELOG@$/ {
        while ((getline line < chgfile) > 0) print line
        next
    }
    { print }
' packaging/rpm/unidrive.spec > "$SPEC"

# Build (binary only; we don't ship a source rpm).
rpmbuild \
    --define "_topdir $WORK" \
    --target "$ARCH" \
    -bb "$SPEC"

# Move the rpm out.
RPM=$OUT/unidrive-${SEMVER}-${RELEASE}.${ARCH}.rpm
find "$WORK/RPMS" -name '*.rpm' -exec mv {} "$RPM" \;
test -f "$RPM" || { echo "ERROR: rpm not found under $WORK/RPMS" >&2; exit 1; }
sha256sum "$RPM" > "${RPM}.sha256"

# Cleanup.
rm -rf "$WORK"

echo "[build-rpm] produced:"
ls -la "$RPM" "${RPM}.sha256"
BUILDRPM
chmod +x packaging/rpm/build-rpm.sh
```

- [ ] **Step 4: Shellcheck**

```bash
shellcheck packaging/rpm/build-rpm.sh
```

- [ ] **Step 5: Commit**

```bash
git add packaging/rpm/
git commit -m "$(cat <<'EOF'
packaging/rpm: unidrive.spec + build-rpm.sh

Templated spec with @VERSION@/@RELEASE@/@BUILD_ARCH@/@STAGED_TREE@/
@CHANGELOG@ placeholders. build-rpm.sh stages JAR + mount binary +
wrapper + service + docs under a staged tree, renders %changelog
from RELEASES.md, runs rpmbuild -bb.

Per spec §4.3.
EOF
)"
```

---

## Task 5: AUR channel — PKGBUILD template + builder

**Files:**
- Create: `packaging/aur/PKGBUILD.template`
- Create: `packaging/aur/build-aur-srcinfo.sh`

- [ ] **Step 1: Make the aur dir**

```bash
mkdir -p packaging/aur
```

- [ ] **Step 2: `packaging/aur/PKGBUILD.template`**

Per spec §4.4, AUR sources from the published GH Release tarball, not from raw git tags. Template:

```bash
cat > packaging/aur/PKGBUILD.template <<'PKG'
# Maintainer: UniDrive Releases <releases@unidrive.krost.org>
pkgname=unidrive
pkgver=@PKGVER@
pkgrel=@PKGREL@
pkgdesc='Multi-cloud sync daemon with sparse hydration (FUSE)'
arch=('x86_64' 'aarch64')
url='https://unidrive.krost.org'
license=('Apache')
depends=('java-runtime>=21' 'fuse3>=3.16')

source=(
    "https://github.com/gkrost/unidrive-dist/releases/download/v${pkgver}/unidrive-${pkgver}-linux-${CARCH}.tar.gz"
    "https://github.com/gkrost/unidrive-dist/releases/download/v${pkgver}/unidrive-${pkgver}-linux-${CARCH}.tar.gz.asc"
)

# Per-arch SHA256 (filled by build-aur-srcinfo.sh).
sha256sums_x86_64=(
    '@SHA256_X86_64@'
    'SKIP'
)
sha256sums_aarch64=(
    '@SHA256_AARCH64@'
    'SKIP'
)

validpgpkeys=('@GPG_FPR@')

package() {
    cd "${srcdir}/unidrive-${pkgver}"

    install -Dm644 lib/unidrive-${pkgver}.jar  "${pkgdir}/usr/lib/unidrive/unidrive.jar"
    install -Dm755 bin/unidrive-mount         "${pkgdir}/usr/lib/unidrive/unidrive-mount"

    # Wrapper.
    install -d "${pkgdir}/usr/bin"
    cat > "${pkgdir}/usr/bin/unidrive" <<'WRAP'
#!/usr/bin/env bash
set -euo pipefail
JAR=/usr/lib/unidrive/unidrive.jar
JAVA=${JAVA:-java}
exec "$JAVA" -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 \
     --enable-native-access=ALL-UNNAMED \
     -jar "$JAR" "$@"
WRAP
    chmod 0755 "${pkgdir}/usr/bin/unidrive"

    install -Dm644 systemd/unidrive.service \
        "${pkgdir}/usr/lib/systemd/user/unidrive.service"

    install -Dm644 doc/LICENSE  "${pkgdir}/usr/share/licenses/unidrive/LICENSE"
    install -Dm644 doc/NOTICE   "${pkgdir}/usr/share/doc/unidrive/NOTICE"
    install -Dm644 doc/README.md "${pkgdir}/usr/share/doc/unidrive/README.md"
}
PKG
```

- [ ] **Step 3: `packaging/aur/build-aur-srcinfo.sh`**

```bash
cat > packaging/aur/build-aur-srcinfo.sh <<'BUILDAUR'
#!/usr/bin/env bash
#
# packaging/aur/build-aur-srcinfo.sh
#
# Usage:
#   packaging/aur/build-aur-srcinfo.sh <semver> <pkgrel> <gpg-fpr>
#
# Reads the published tarballs from build/tarball/ (i.e., the tarballs
# this same release will publish to its own GH Release). Computes
# their SHA256s and substitutes them into PKGBUILD.template along
# with pkgver/pkgrel/gpg fingerprint. Then runs `makepkg --printsrcinfo`
# to generate .SRCINFO.
#
# Writes:
#   build/aur/PKGBUILD
#   build/aur/.SRCINFO

set -euo pipefail

[[ $# -eq 3 ]] || { echo "usage: $0 <semver> <pkgrel> <gpg-fpr>" >&2; exit 64; }
PKGVER=$1
PKGREL=$2
GPG_FPR=$3

REPO_ROOT=$(cd "$(dirname "$0")/../.." && pwd)
cd "$REPO_ROOT"

# Compute SHA256s of the per-arch tarballs that this release will
# publish to its own GH Release. These must exist already — built by
# packaging/tarball/build-tarball.sh per arch.
T_X86=build/tarball/unidrive-${PKGVER}-linux-x86_64.tar.gz
T_ARM=build/tarball/unidrive-${PKGVER}-linux-aarch64.tar.gz
test -f "$T_X86" || { echo "ERROR: missing $T_X86 (run build-tarball.sh x86_64 first)" >&2; exit 1; }
test -f "$T_ARM" || { echo "ERROR: missing $T_ARM (run build-tarball.sh aarch64 first)" >&2; exit 1; }

SHA_X86=$(sha256sum "$T_X86" | awk '{print $1}')
SHA_ARM=$(sha256sum "$T_ARM" | awk '{print $1}')

mkdir -p build/aur

# Render PKGBUILD from template.
sed -e "s/@PKGVER@/${PKGVER}/g" \
    -e "s/@PKGREL@/${PKGREL}/g" \
    -e "s/@SHA256_X86_64@/${SHA_X86}/g" \
    -e "s/@SHA256_AARCH64@/${SHA_ARM}/g" \
    -e "s/@GPG_FPR@/${GPG_FPR}/g" \
    packaging/aur/PKGBUILD.template \
    > build/aur/PKGBUILD

# Generate .SRCINFO via makepkg.
# makepkg refuses to run as root; if running in CI, ensure we're not root.
if [ "$(id -u)" -eq 0 ]; then
    echo "ERROR: makepkg refuses to run as root. Re-run as an unprivileged user." >&2
    exit 1
fi

# We don't actually build the package here — `--printsrcinfo` parses
# the PKGBUILD and emits .SRCINFO. No network, no compilation.
( cd build/aur && makepkg --printsrcinfo > .SRCINFO )

echo "[build-aur] produced:"
ls -la build/aur/PKGBUILD build/aur/.SRCINFO
BUILDAUR
chmod +x packaging/aur/build-aur-srcinfo.sh
```

- [ ] **Step 4: Shellcheck**

```bash
shellcheck packaging/aur/build-aur-srcinfo.sh
```

- [ ] **Step 5: Commit**

```bash
git add packaging/aur/
git commit -m "$(cat <<'EOF'
packaging/aur: PKGBUILD.template + build-aur-srcinfo.sh

Template references the GH Release tarball this same release will
publish to its own repo (closing the loop — AUR users download from
our GH Release, not from a separate AUR source mirror). per-arch
SHA256 substitution + GPG fingerprint validation. .SRCINFO generated
via `makepkg --printsrcinfo`.

Per spec §4.4.
EOF
)"
```

---

## Task 6: Extend `test/local-build.sh` to cover deb/rpm/AUR

**Files:**
- Modify: `test/local-build.sh`

- [ ] **Step 1: Read current state**

```bash
grep -n 'DONE' test/local-build.sh
```

Note the line of the "DONE" echo block — the new channel builds insert before it.

- [ ] **Step 2: Append channel calls before the DONE block**

Edit `test/local-build.sh`. Replace the final `echo "[local-build] DONE."` block with:

```bash
# --- 5b. Build per-arch tarballs (host arch only for cross-arch we'd need cross) ---
packaging/tarball/build-tarball.sh "$DIST_SEMVER" "$HOST_ARCH"

# For AUR's PKGBUILD we need both arches' tarballs to compute SHA256s
# of source=()'s URL targets. In dev-loop, we cheat: copy the host-arch
# tarball under both names so AUR's checksum step has something to hash
# (the resulting PKGBUILD won't actually install correctly on a
# different arch from a dev build — that's accepted in the dev loop).
if [[ ! -f build/tarball/unidrive-${DIST_SEMVER}-linux-x86_64.tar.gz ]]; then
    cp "build/tarball/unidrive-${DIST_SEMVER}-linux-${HOST_ARCH}.tar.gz" \
       "build/tarball/unidrive-${DIST_SEMVER}-linux-x86_64.tar.gz"
fi
if [[ ! -f build/tarball/unidrive-${DIST_SEMVER}-linux-aarch64.tar.gz ]]; then
    cp "build/tarball/unidrive-${DIST_SEMVER}-linux-${HOST_ARCH}.tar.gz" \
       "build/tarball/unidrive-${DIST_SEMVER}-linux-aarch64.tar.gz"
fi

# --- 6. Build per-channel packages for the host arch ---
case "$HOST_ARCH" in
    x86_64) DEB_ARCH=amd64 ;;
    aarch64) DEB_ARCH=arm64 ;;
esac

# Need a RELEASES.md section to satisfy render-changelog.sh. Seed if
# missing.
if ! grep -q "^## ${DIST_SEMVER} " RELEASES.md; then
    echo "[local-build] WARN: no RELEASES.md entry for ${DIST_SEMVER}; using placeholder."
    cat > /tmp/local-build-releases-stub <<EOF
## ${DIST_SEMVER} — local-build placeholder
- (local-build placeholder; real entry lands in Plan P5)

EOF
    # Insert above the marker line so we don't pollute the file.
    sed -i "/<!-- Releases below this line/r /tmp/local-build-releases-stub" RELEASES.md
fi

packaging/deb/build-deb.sh "$DIST_SEMVER" 1 "$DEB_ARCH"
packaging/rpm/build-rpm.sh "$DIST_SEMVER" 1 "$HOST_ARCH"

# AUR needs a fingerprint. In dev-loop, fake one (the value isn't
# verified by makepkg --printsrcinfo). Real fingerprint comes from
# the GPG key (release/release.sh handles this in CI).
FAKE_FPR=$(printf '%040d' 0)
packaging/aur/build-aur-srcinfo.sh "$DIST_SEMVER" 1 "$FAKE_FPR"

echo
echo "[local-build] DONE."
echo "    tarball: build/tarball/unidrive-${DIST_SEMVER}-linux-${HOST_ARCH}.tar.gz"
echo "    deb:     build/deb/unidrive_${DIST_SEMVER}-1_${DEB_ARCH}.deb"
echo "    rpm:     build/rpm/unidrive-${DIST_SEMVER}-1.${HOST_ARCH}.rpm"
echo "    aur:     build/aur/PKGBUILD + .SRCINFO"
```

(Replace the existing final `echo "[local-build] DONE." ...` lines.)

- [ ] **Step 3: Run end-to-end**

```bash
cd /home/gernot/dev/git/unidrive-dist
./test/local-build.sh
```

Expected: produces tarball, deb, rpm, and AUR PKGBUILD+.SRCINFO. Restore RELEASES.md afterwards if the dev-loop placeholder added a section:

```bash
git diff RELEASES.md
# If the dev-loop added a placeholder section, revert it before committing.
git checkout -- RELEASES.md  # only if the diff shows the placeholder section
```

- [ ] **Step 4: Commit**

```bash
git add test/local-build.sh
git commit -m "test: local-build.sh extends to deb/rpm/AUR channels"
```

---

## Task 7: `release/sign-packages.sh`

**Files:**
- Create: `release/sign-packages.sh`

- [ ] **Step 1: Write the script**

```bash
cat > release/sign-packages.sh <<'SIGNSH'
#!/usr/bin/env bash
#
# release/sign-packages.sh
#
# Pushes unsigned packages to pkg-signer on the krost-infra VPS,
# triggers sign-drop, pulls signed copies back into a sibling
# `signed/` directory.
#
# Usage:
#   release/sign-packages.sh
#
# Reads:
#   build/deb/*.deb
#   build/rpm/*.rpm
#   build/tarball/*.tar.gz
#   build/tarball/*.tar.gz.SHA256SUMS  (if present — written by build-tarball.sh)
#   build/aur/PKGBUILD                 (NOT signed — AUR doesn't sign PKGBUILDs)
#
# Writes:
#   build/signed/*  (signed copies)
#
# Environment:
#   SIGNER_SSH_USER     default: signer
#   SIGNER_SSH_HOST     default: 87.106.246.31
#   SIGNER_SSH_PORT     default: 22222
#   SIGNER_SSH_KEY      path to SSH private key (default: from agent)
#
# The actual transport is two-stage: SSH to the krost-infra bastion
# (gernot@VPS:22222) and from there `docker exec` into the pkg-signer
# container. To keep the script simple we use the ProxyJump form:
#
#   ssh -J gernot@VPS:22222 -i KEY signer@pkg-signer
#
# This requires pkg-signer's internal-network IP to be reachable from
# the bastion, which it is (the gernot user can `docker exec` and
# bash-pipe; we use port-forward + ProxyJump). The CI runner sets up
# this hop via an ssh config block injected at job start.

set -euo pipefail

SIGNER_SSH_USER=${SIGNER_SSH_USER:-signer}
SIGNER_SSH_HOST=${SIGNER_SSH_HOST:-pkg-signer-via-bastion}
SIGNER_SSH_PORT=${SIGNER_SSH_PORT:-22}
SIGNER_SSH_KEY=${SIGNER_SSH_KEY:-}

REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$REPO_ROOT"

SSH_OPTS=(-p "$SIGNER_SSH_PORT" -o StrictHostKeyChecking=accept-new)
SFTP_OPTS=(-P "$SIGNER_SSH_PORT" -o StrictHostKeyChecking=accept-new)
if [[ -n $SIGNER_SSH_KEY ]]; then
    SSH_OPTS+=(-i "$SIGNER_SSH_KEY")
    SFTP_OPTS+=(-i "$SIGNER_SSH_KEY")
fi

SIGNER="${SIGNER_SSH_USER}@${SIGNER_SSH_HOST}"

# Collect files to sign.
TO_SIGN=()
for pattern in 'build/deb/*.deb' 'build/rpm/*.rpm' 'build/tarball/*.tar.gz' \
               'build/tarball/SHA256SUMS' 'build/repo-server/apt/dists/stable/Release' \
               'build/repo-server/dnf/repodata/repomd.xml'; do
    for f in $pattern; do
        [[ -f $f ]] && TO_SIGN+=("$f")
    done
done

if [[ ${#TO_SIGN[@]} -eq 0 ]]; then
    echo "ERROR: nothing to sign. Did you run the channel builds first?" >&2
    exit 1
fi

echo "[sign] pushing ${#TO_SIGN[@]} artefact(s) to pkg-signer..."
for f in "${TO_SIGN[@]}"; do
    echo "  $f"
done

# Use sftp batch mode to upload everything in one connection.
BATCH=$(mktemp)
{
    echo "cd /var/lib/signer/drop"
    for f in "${TO_SIGN[@]}"; do
        echo "put $f"
    done
    echo "bye"
} > "$BATCH"

sftp -b "$BATCH" "${SFTP_OPTS[@]}" "$SIGNER"
rm -f "$BATCH"

# Trigger sign-drop.
echo "[sign] triggering sign-drop..."
ssh "${SSH_OPTS[@]}" "$SIGNER" sign-drop

# Pull signed artefacts back.
mkdir -p build/signed
echo "[sign] pulling signed artefacts..."
sftp -r -b - "${SFTP_OPTS[@]}" "$SIGNER" <<EOF
cd /var/lib/signer/signed
lcd build/signed
mget *
bye
EOF

# Confirm the signer has nothing left.
echo "[sign] clearing signer's signed/..."
ssh "${SSH_OPTS[@]}" "$SIGNER" clear-signed

echo "[sign] done."
ls -la build/signed/
SIGNSH
chmod +x release/sign-packages.sh
```

- [ ] **Step 2: Shellcheck**

```bash
shellcheck release/sign-packages.sh
```

- [ ] **Step 3: Commit (no live smoke yet — Plan P5 exercises this against the real signer)**

```bash
git add release/sign-packages.sh
git commit -m "$(cat <<'EOF'
release: sign-packages.sh — sftp to pkg-signer, sign-drop, sftp back

Two-stage transport: ProxyJump via the bastion to reach the
internal-network pkg-signer container. Collects .deb / .rpm /
tarballs / apt Release / dnf repomd.xml, batches the sftp upload,
triggers sign-drop, pulls signed copies back into build/signed/,
then issues clear-signed.

Per spec §5.2. Live smoke against the real signer happens in
Plan P5.
EOF
)"
```

---

This is Part 1 of P4b. Tasks 8–17 (publish-apt, publish-dnf, publish-aur, publish-gh-release, release.sh, repo-server config, smoke matrix, CI workflows, AUR bot account setup) continue in **Part 2** at the same plan file — see `dist-channels-and-orchestration-part-2.md`.

(The plan was split mid-flow because of an output-token cap during initial drafting; both halves form one logical implementation plan and are executed in sequence.)
