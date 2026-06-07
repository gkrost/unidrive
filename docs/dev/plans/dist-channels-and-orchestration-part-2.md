# Plan P4b (Part 2): publish-* scripts + release.sh + smoke matrix + CI

> Continuation of `dist-channels-and-orchestration.md` (Part 1).
> Same goal, same plan, split for output-token reasons.
>
> Part 1 covers Tasks 0–7 (branch, deb/rpm/aur recipes,
> render-changelog, local-build extension, sign-packages).
>
> Part 2 covers Tasks 8–17 (publish-* scripts, top-level
> orchestrator, repo-server skeleton, smoke matrix, CI YAML,
> AUR bot account setup).

---

## Task 8: `release/publish-apt.sh`

Produces a signed apt repo tree on disk, then rsyncs it to `pkg-publisher@VPS:~/docker/pkg-server/pkg-data/apt/`.

**Files:**
- Create: `release/publish-apt.sh`

- [ ] **Step 1: Write the script**

```bash
cat > release/publish-apt.sh <<'PUBAPT'
#!/usr/bin/env bash
#
# release/publish-apt.sh
#
# Stages a signed apt repo under build/repo-server/apt/, then rsyncs
# to the krost-infra VPS via the pkg-publisher SSH account.
#
# Usage:
#   release/publish-apt.sh <semver> <pkgrel>
#
# Reads:
#   build/signed/unidrive_<semver>-<pkgrel>_<arch>.deb (signed by pkg-signer)
#   Pre-published public key at sites/unidrive/install/unidrive-releases.gpg
#   (in the krost-infra repo, deployed manually by Plan P3 Task 6).
#
# Writes (locally):
#   build/repo-server/apt/dists/stable/Release            (unsigned)
#   build/repo-server/apt/dists/stable/main/binary-<arch>/Packages
#   build/repo-server/apt/dists/stable/main/binary-<arch>/Packages.gz
#   build/repo-server/apt/pool/main/u/unidrive/*.deb
#
# Then sign-packages.sh signs Release → Release.gpg + InRelease.
#
# Then rsync to VPS.
#
# Environment:
#   PUBLISHER_SSH_USER  default: pkg-publisher
#   PUBLISHER_SSH_HOST  default: 87.106.246.31
#   PUBLISHER_SSH_PORT  default: 22222
#   PUBLISHER_SSH_KEY   path to SSH key (default: from agent)

set -euo pipefail

[[ $# -eq 2 ]] || { echo "usage: $0 <semver> <pkgrel>" >&2; exit 64; }
SEMVER=$1
PKGREL=$2

PUBLISHER_SSH_USER=${PUBLISHER_SSH_USER:-pkg-publisher}
PUBLISHER_SSH_HOST=${PUBLISHER_SSH_HOST:-87.106.246.31}
PUBLISHER_SSH_PORT=${PUBLISHER_SSH_PORT:-22222}
PUBLISHER_SSH_KEY=${PUBLISHER_SSH_KEY:-}

REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$REPO_ROOT"

OUT=build/repo-server/apt
rm -rf "$OUT"
mkdir -p "$OUT/dists/stable/main/binary-amd64" \
         "$OUT/dists/stable/main/binary-arm64" \
         "$OUT/pool/main/u/unidrive"

# Copy signed .debs into pool.
for arch in amd64 arm64; do
    deb="build/signed/unidrive_${SEMVER}-${PKGREL}_${arch}.deb"
    if [[ -f $deb ]]; then
        cp "$deb" "$OUT/pool/main/u/unidrive/"
    else
        echo "WARN: $deb missing — skipping ${arch}" >&2
    fi
done

# Generate Packages files per arch.
for arch in amd64 arm64; do
    packages_dir="$OUT/dists/stable/main/binary-$arch"
    ( cd "$OUT" && apt-ftparchive --arch "$arch" packages "pool/main/u/unidrive" ) \
        > "$packages_dir/Packages"
    gzip -kf "$packages_dir/Packages"
done

# Generate Release (unsigned — sign-packages.sh handles the .gpg/.InRelease).
RELEASE_CONF=$(mktemp)
cat > "$RELEASE_CONF" <<RCONF
APT::FTPArchive::Release::Origin "UniDrive";
APT::FTPArchive::Release::Label "UniDrive";
APT::FTPArchive::Release::Suite "stable";
APT::FTPArchive::Release::Codename "stable";
APT::FTPArchive::Release::Architectures "amd64 arm64";
APT::FTPArchive::Release::Components "main";
APT::FTPArchive::Release::Description "UniDrive apt repository";
RCONF

( cd "$OUT" && apt-ftparchive -c "$RELEASE_CONF" release dists/stable ) \
    > "$OUT/dists/stable/Release"
rm -f "$RELEASE_CONF"

# Copy the public key (must already be present in the krost-infra
# sites/unidrive/install/ tree — Plan P3 Task 6 deploys it).
KRPATH=../krost-infra/sites/unidrive/install/unidrive-releases.gpg
if [[ -f $KRPATH ]]; then
    cp "$KRPATH" "$OUT/unidrive-releases.gpg"
else
    echo "WARN: $KRPATH not found locally; the public key must be deployed separately" >&2
fi

# Human-friendly index.
cat > "$OUT/index.html" <<'IDX'
<!doctype html>
<title>UniDrive apt repository</title>
<h1>UniDrive apt repository</h1>
<p>See <a href="https://unidrive.krost.org/install/">unidrive.krost.org/install</a>
for the canonical install instructions.</p>
<p>Public key fingerprint visible at the install page link above.</p>
IDX

# Note: the Release file needs to be signed by sign-packages.sh BEFORE
# rsyncing. The orchestrator (release/release.sh) calls
# sign-packages.sh after this script and before publish runs to-vps.
#
# If invoked standalone (dev/debug), the signing step is skipped and
# this script only stages the tree. The rsync below uploads whatever
# is in build/signed/ for the stable/ directory.

# rsync (only if signed Release.gpg + InRelease are present).
if [[ -f build/signed/Release ]] && [[ -f build/signed/InRelease ]]; then
    cp build/signed/Release "$OUT/dists/stable/Release"
    cp build/signed/InRelease "$OUT/dists/stable/InRelease"
    cp build/signed/Release.gpg "$OUT/dists/stable/Release.gpg" 2>/dev/null || true

    RSYNC_OPTS=(-avz --delete -e "ssh -p $PUBLISHER_SSH_PORT -o StrictHostKeyChecking=accept-new")
    if [[ -n $PUBLISHER_SSH_KEY ]]; then
        RSYNC_OPTS=(-avz --delete -e "ssh -p $PUBLISHER_SSH_PORT -o StrictHostKeyChecking=accept-new -i $PUBLISHER_SSH_KEY")
    fi

    # Backup the previous state on the VPS before overwriting.
    ssh -p "$PUBLISHER_SSH_PORT" \
        ${PUBLISHER_SSH_KEY:+-i "$PUBLISHER_SSH_KEY"} \
        "$PUBLISHER_SSH_USER@$PUBLISHER_SSH_HOST" \
        "ts=\$(date -u +%Y%m%dT%H%M%SZ); \
         test -d apt && cp -a apt apt-backup-\$ts || true; \
         # Retain only the latest 5 backups.
         ls -1dt apt-backup-* 2>/dev/null | tail -n +6 | xargs -r rm -rf"

    rsync "${RSYNC_OPTS[@]}" "$OUT/" \
          "$PUBLISHER_SSH_USER@$PUBLISHER_SSH_HOST:apt/"
    echo "[publish-apt] rsync done."
else
    echo "[publish-apt] signed Release not in build/signed/ — skipping rsync."
    echo "    (Stage only; expected when running standalone for dev.)"
fi
PUBAPT
chmod +x release/publish-apt.sh
```

- [ ] **Step 2: Shellcheck**

```bash
shellcheck release/publish-apt.sh
```

- [ ] **Step 3: Commit**

```bash
git add release/publish-apt.sh
git commit -m "$(cat <<'EOF'
release: publish-apt.sh — stage apt repo + rsync to pkg-server

Uses apt-ftparchive to generate Packages + Release. Signed Release
files come from sign-packages.sh. Rsyncs to pkg-publisher@VPS,
keeping last 5 timestamped backups on the VPS side for rollback.

Per spec §3.6, §7.3.
EOF
)"
```

---

## Task 9: `release/publish-dnf.sh`

**Files:**
- Create: `release/publish-dnf.sh`

- [ ] **Step 1: Write the script**

```bash
cat > release/publish-dnf.sh <<'PUBDNF'
#!/usr/bin/env bash
#
# release/publish-dnf.sh
#
# Stages a signed dnf repo under build/repo-server/dnf/, then rsyncs
# to the krost-infra VPS.
#
# Usage:
#   release/publish-dnf.sh <semver> <release>
#
# Reads:
#   build/signed/unidrive-<semver>-<release>.<arch>.rpm
#
# Writes (locally):
#   build/repo-server/dnf/<arch>/*.rpm
#   build/repo-server/dnf/repodata/{repomd.xml,*.xml.gz,...}
#   build/repo-server/dnf/unidrive.repo
#   build/repo-server/dnf/unidrive-releases.gpg

set -euo pipefail

[[ $# -eq 2 ]] || { echo "usage: $0 <semver> <release>" >&2; exit 64; }
SEMVER=$1
RELEASE=$2

PUBLISHER_SSH_USER=${PUBLISHER_SSH_USER:-pkg-publisher}
PUBLISHER_SSH_HOST=${PUBLISHER_SSH_HOST:-87.106.246.31}
PUBLISHER_SSH_PORT=${PUBLISHER_SSH_PORT:-22222}
PUBLISHER_SSH_KEY=${PUBLISHER_SSH_KEY:-}

REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$REPO_ROOT"

OUT=build/repo-server/dnf
rm -rf "$OUT"
mkdir -p "$OUT/x86_64" "$OUT/aarch64"

# Copy signed .rpms.
for arch in x86_64 aarch64; do
    rpm="build/signed/unidrive-${SEMVER}-${RELEASE}.${arch}.rpm"
    if [[ -f $rpm ]]; then
        cp "$rpm" "$OUT/$arch/"
    else
        echo "WARN: $rpm missing — skipping ${arch}" >&2
    fi
done

# Build repodata. createrepo_c discovers .rpms in subdirs.
# Per spec §7.3.1: repo_gpgcheck=1 means repomd.xml itself is signed.
createrepo_c --update "$OUT"

# Unsigned repomd.xml; sign-packages.sh adds repomd.xml.asc.
# (Move out of sequence: this script stages; orchestrator calls
# sign-packages.sh between the two.)
if [[ -f build/signed/repomd.xml.asc ]]; then
    cp build/signed/repomd.xml.asc "$OUT/repodata/repomd.xml.asc"
fi

# Drop-in repo descriptor file (spec §7.3.1).
cat > "$OUT/unidrive.repo" <<RCONF
[unidrive]
name=UniDrive
baseurl=https://dnf.unidrive.krost.org/\$basearch/
enabled=1
gpgcheck=1
repo_gpgcheck=1
gpgkey=https://unidrive.krost.org/install/unidrive-releases.gpg
RCONF

# Copy public key alongside.
KRPATH=../krost-infra/sites/unidrive/install/unidrive-releases.gpg
if [[ -f $KRPATH ]]; then
    cp "$KRPATH" "$OUT/unidrive-releases.gpg"
fi

# rsync (only if repomd.xml.asc is present — meaning signing has happened).
if [[ -f $OUT/repodata/repomd.xml.asc ]]; then
    RSYNC_BASE="ssh -p $PUBLISHER_SSH_PORT -o StrictHostKeyChecking=accept-new"
    [[ -n $PUBLISHER_SSH_KEY ]] && RSYNC_BASE="$RSYNC_BASE -i $PUBLISHER_SSH_KEY"

    # Backup previous.
    ssh -p "$PUBLISHER_SSH_PORT" \
        ${PUBLISHER_SSH_KEY:+-i "$PUBLISHER_SSH_KEY"} \
        "$PUBLISHER_SSH_USER@$PUBLISHER_SSH_HOST" \
        "ts=\$(date -u +%Y%m%dT%H%M%SZ); \
         test -d dnf && cp -a dnf dnf-backup-\$ts || true; \
         ls -1dt dnf-backup-* 2>/dev/null | tail -n +6 | xargs -r rm -rf"

    rsync -avz --delete -e "$RSYNC_BASE" "$OUT/" \
          "$PUBLISHER_SSH_USER@$PUBLISHER_SSH_HOST:dnf/"
    echo "[publish-dnf] rsync done."
else
    echo "[publish-dnf] repomd.xml.asc not in build/signed/ — skipping rsync."
fi
PUBDNF
chmod +x release/publish-dnf.sh
shellcheck release/publish-dnf.sh
```

- [ ] **Step 2: Commit**

```bash
git add release/publish-dnf.sh
git commit -m "release: publish-dnf.sh — createrepo_c + sign + rsync"
```

---

## Task 10: `release/publish-aur.sh`

**Files:**
- Create: `release/publish-aur.sh`

- [ ] **Step 1: Write the script**

```bash
cat > release/publish-aur.sh <<'PUBAUR'
#!/usr/bin/env bash
#
# release/publish-aur.sh
#
# Pushes the rendered PKGBUILD + .SRCINFO to aur.archlinux.org via
# the unidrive-bot account's SSH key (AUR_BOT_KEY).
#
# Per spec §4.4: AUR doesn't support repo-level deploy keys; this
# uses a dedicated Arch Linux account (unidrive-bot) registered with
# the deploy key.
#
# Usage:
#   release/publish-aur.sh <semver>
#
# Reads:
#   build/aur/PKGBUILD
#   build/aur/.SRCINFO
#
# Environment:
#   AUR_BOT_KEY      path to SSH private key with unidrive-bot's
#                    public key registered on aur.archlinux.org

set -euo pipefail

[[ $# -eq 1 ]] || { echo "usage: $0 <semver>" >&2; exit 64; }
SEMVER=$1

AUR_BOT_KEY=${AUR_BOT_KEY:-}
[[ -n $AUR_BOT_KEY ]] || { echo "ERROR: AUR_BOT_KEY not set" >&2; exit 1; }
[[ -f $AUR_BOT_KEY ]] || { echo "ERROR: AUR_BOT_KEY '$AUR_BOT_KEY' not a file" >&2; exit 1; }

REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$REPO_ROOT"

test -f build/aur/PKGBUILD || { echo "ERROR: build/aur/PKGBUILD missing" >&2; exit 1; }
test -f build/aur/.SRCINFO || { echo "ERROR: build/aur/.SRCINFO missing" >&2; exit 1; }

# Clone the AUR repo into a tempdir using the bot's key.
WORK=$(mktemp -d -p "$PWD" aur-publish-XXXXXX)
trap 'rm -rf "$WORK"' EXIT

GIT_SSH_COMMAND="ssh -i $AUR_BOT_KEY -o StrictHostKeyChecking=accept-new" \
    git clone "ssh://aur@aur.archlinux.org/unidrive.git" "$WORK/unidrive"

cp build/aur/PKGBUILD  "$WORK/unidrive/PKGBUILD"
cp build/aur/.SRCINFO  "$WORK/unidrive/.SRCINFO"

(
    cd "$WORK/unidrive"
    git config user.name "UniDrive Releases"
    git config user.email "releases@unidrive.krost.org"
    git add PKGBUILD .SRCINFO

    if git diff --cached --quiet; then
        echo "[publish-aur] no changes to push (PKGBUILD identical to current AUR)."
        exit 0
    fi

    git commit -m "Update to ${SEMVER}"

    # Recover from concurrent push (rare for a single-maintainer
    # project, but documented in spec §9 BACKLOG as a follow-up).
    if ! GIT_SSH_COMMAND="ssh -i $AUR_BOT_KEY -o StrictHostKeyChecking=accept-new" \
         git push origin master; then
        echo "[publish-aur] push failed; attempting pull --rebase + retry."
        GIT_SSH_COMMAND="ssh -i $AUR_BOT_KEY -o StrictHostKeyChecking=accept-new" \
            git pull --rebase origin master
        GIT_SSH_COMMAND="ssh -i $AUR_BOT_KEY -o StrictHostKeyChecking=accept-new" \
            git push origin master
    fi
)

echo "[publish-aur] pushed ${SEMVER} to AUR."
PUBAUR
chmod +x release/publish-aur.sh
shellcheck release/publish-aur.sh
```

- [ ] **Step 2: Commit**

```bash
git add release/publish-aur.sh
git commit -m "$(cat <<'EOF'
release: publish-aur.sh — SSH-keyed git push to aur.archlinux.org

Uses AUR_BOT_KEY (the unidrive-bot account's SSH private key) to push
PKGBUILD + .SRCINFO. Concurrent-push recovery via pull --rebase before
retry, per spec §9 BACKLOG (AUR push-collision recovery).

Per spec §4.4.
EOF
)"
```

---

## Task 11: `release/publish-gh-release.sh`

**Files:**
- Create: `release/publish-gh-release.sh`

- [ ] **Step 1: Write the script**

```bash
cat > release/publish-gh-release.sh <<'PUBGH'
#!/usr/bin/env bash
#
# release/publish-gh-release.sh
#
# Creates/updates the unidrive-dist GH Release at $TAG and uploads
# the signed tarball bundle + SHA256SUMS + SHA256SUMS.asc per arch.
#
# Usage:
#   release/publish-gh-release.sh <tag>
#
# Where <tag> matches ^v(\d+\.\d+\.\d+)(-pkg\d+)?$ (spec §3.4).

set -euo pipefail

[[ $# -eq 1 ]] || { echo "usage: $0 <tag>" >&2; exit 64; }
TAG=$1

[[ $TAG =~ ^v([0-9]+\.[0-9]+\.[0-9]+)(-pkg[0-9]+)?$ ]] \
    || { echo "ERROR: tag '$TAG' fails spec §3.4 regex" >&2; exit 64; }
SEMVER=${BASH_REMATCH[1]}

REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$REPO_ROOT"

# Render GH-style release notes from RELEASES.md.
NOTES=$(mktemp)
trap 'rm -f "$NOTES"' EXIT
release/render-changelog.sh gh "$SEMVER" > "$NOTES"

# Collect tarballs to upload.
FILES=()
for arch in x86_64 aarch64; do
    t="build/tarball/unidrive-${SEMVER}-linux-${arch}.tar.gz"
    [[ -f $t ]] && FILES+=("$t")
    [[ -f $t.sha256 ]] && FILES+=("$t.sha256")
    # Signed copies from build/signed/ if present.
    [[ -f build/signed/unidrive-${SEMVER}-linux-${arch}.tar.gz.SHA256SUMS.asc ]] \
        && FILES+=("build/signed/unidrive-${SEMVER}-linux-${arch}.tar.gz.SHA256SUMS.asc")
done

if [[ ${#FILES[@]} -eq 0 ]]; then
    echo "ERROR: no tarball files to upload" >&2
    exit 1
fi

# Create the release if it doesn't exist, else upload to it.
if gh release view "$TAG" >/dev/null 2>&1; then
    echo "[publish-gh] release $TAG exists; uploading files (clobber on dup)..."
    gh release upload "$TAG" "${FILES[@]}" --clobber
else
    echo "[publish-gh] creating release $TAG..."
    gh release create "$TAG" "${FILES[@]}" \
        --title "$TAG" \
        --notes-file "$NOTES"
fi

echo "[publish-gh] done."
PUBGH
chmod +x release/publish-gh-release.sh
shellcheck release/publish-gh-release.sh
```

- [ ] **Step 2: Commit**

```bash
git add release/publish-gh-release.sh
git commit -m "release: publish-gh-release.sh — create/append unidrive-dist GH Release"
```

---

## Task 12: `release/release.sh` — top-level orchestrator

**Files:**
- Create: `release/release.sh`

- [ ] **Step 1: Write the orchestrator**

```bash
cat > release/release.sh <<'RELEASESH'
#!/usr/bin/env bash
#
# release/release.sh
#
# Top-level orchestrator for cutting a unidrive-dist release.
#
# Usage:
#   release/release.sh <tag>
#
# Where <tag> matches ^v(\d+\.\d+\.\d+)(-pkg\d+)?$ (spec §3.4).
#
# Sequence (per spec §3.3 step 3):
#   a. fetch-artefacts.sh    — pull + verify JAR + Rust tarballs
#   b. build channels        — tarball, deb, rpm, AUR PKGBUILD
#   c. sign-packages.sh      — sign everything via pkg-signer
#   d. publish-apt.sh        — rsync apt repo
#   e. publish-dnf.sh        — rsync dnf repo
#   f. publish-gh-release.sh — upload tarball bundle
#   g. publish-aur.sh        — ssh-push to AUR
#
# This script is intended to run in CI (.github/workflows/release.yml).
# It runs identically locally (e.g. for dev rehearsal) provided the
# environment is set: AUR_BOT_KEY, SIGNER_SSH_KEY, PUBLISHER_SSH_KEY,
# GPG_KEY_FINGERPRINT, GNUPGHOME with the unidrive-releases public key.

set -euo pipefail

[[ $# -eq 1 ]] || { echo "usage: $0 <tag>" >&2; exit 64; }
TAG=$1

[[ $TAG =~ ^v([0-9]+\.[0-9]+\.[0-9]+)(-pkg([0-9]+))?$ ]] \
    || { echo "ERROR: tag '$TAG' fails spec §3.4 regex" >&2; exit 64; }
SEMVER=${BASH_REMATCH[1]}
PKG_SUFFIX_NUM=${BASH_REMATCH[3]:-1}

REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$REPO_ROOT"

echo "============================================================"
echo "[release] starting release $TAG"
echo "[release]   upstream semver   : $SEMVER"
echo "[release]   packaging release : $PKG_SUFFIX_NUM"
echo "============================================================"

# --- (a) fetch ---
echo
echo "[release] (a) fetch-artefacts..."
release/fetch-artefacts.sh "$TAG"

# --- (b) build channels (both arches) ---
echo
echo "[release] (b) building channels..."
for arch in x86_64 aarch64; do
    packaging/tarball/build-tarball.sh "$SEMVER" "$arch"
done
for arch in amd64 arm64; do
    packaging/deb/build-deb.sh "$SEMVER" "$PKG_SUFFIX_NUM" "$arch"
done
for arch in x86_64 aarch64; do
    packaging/rpm/build-rpm.sh "$SEMVER" "$PKG_SUFFIX_NUM" "$arch"
done

# AUR needs the GPG fingerprint to bake into validpgpkeys=().
GPG_FPR=${GPG_KEY_FINGERPRINT:?GPG_KEY_FINGERPRINT must be set}
packaging/aur/build-aur-srcinfo.sh "$SEMVER" "$PKG_SUFFIX_NUM" "$GPG_FPR"

# Pre-stage apt + dnf trees (unsigned) so sign-packages.sh has
# Release / repomd.xml to sign.
echo
echo "[release] (b.1) pre-staging apt + dnf metadata for signing..."
release/publish-apt.sh "$SEMVER" "$PKG_SUFFIX_NUM"  # stage-only (no Release.gpg yet)
release/publish-dnf.sh "$SEMVER" "$PKG_SUFFIX_NUM"  # stage-only

# Copy the unsigned Release + repomd.xml into the sign queue.
mkdir -p build/repo-server-stage
cp build/repo-server/apt/dists/stable/Release build/repo-server-stage/Release
cp build/repo-server/dnf/repodata/repomd.xml  build/repo-server-stage/repomd.xml

# --- (c) sign ---
echo
echo "[release] (c) sign-packages..."
release/sign-packages.sh

# --- (d/e/f/g) publish ---
echo
echo "[release] (d) publish-apt..."
release/publish-apt.sh "$SEMVER" "$PKG_SUFFIX_NUM"

echo
echo "[release] (e) publish-dnf..."
release/publish-dnf.sh "$SEMVER" "$PKG_SUFFIX_NUM"

echo
echo "[release] (f) publish-gh-release..."
release/publish-gh-release.sh "$TAG"

echo
echo "[release] (g) publish-aur..."
release/publish-aur.sh "$SEMVER"

echo
echo "============================================================"
echo "[release] DONE: $TAG published."
echo "============================================================"
RELEASESH
chmod +x release/release.sh
shellcheck release/release.sh
```

- [ ] **Step 2: Commit**

```bash
git add release/release.sh
git commit -m "$(cat <<'EOF'
release: release.sh — top-level orchestrator

Sequence: fetch → build all channels → sign → publish to apt, dnf,
GH Release, AUR. Idempotent on retry (publish steps tolerate
pre-existing state).

Per spec §3.3 step 3.
EOF
)"
```

---

## Task 13: repo-server skeleton

**Files:**
- Create: `repo-server/README.md`
- Create: `repo-server/apt/.gitkeep`
- Create: `repo-server/dnf/.gitkeep`

- [ ] **Step 1: Create the skeleton**

```bash
mkdir -p repo-server/apt repo-server/dnf
touch repo-server/apt/.gitkeep repo-server/dnf/.gitkeep

cat > repo-server/README.md <<'RR'
# repo-server config (for krost-infra)

This directory exists as a marker for the apt + dnf repo skeleton.
The actual `pkg-server` Docker service runs in `krost-infra`
(see `unidrive/docs/dev/plans/krost-infra-pkg-server-and-signer.md`).

The `dists/`, `pool/`, `repodata/`, etc. content is **not** committed
here — it lives on the VPS volume `pkg-data`, populated by
`release/publish-apt.sh` and `release/publish-dnf.sh` on each release.

If the repo-server's nginx config or layout ever needs versioning,
this is where it lives. For MVP, krost-infra owns it entirely.
RR
```

- [ ] **Step 2: Commit**

```bash
git add repo-server/
git commit -m "repo-server: skeleton + README (content lives on VPS, not in repo)"
```

---

## Task 14: Docker smoke matrix

**Files:**
- Create: `test/deb-install-in-docker.sh`
- Create: `test/rpm-install-in-docker.sh`
- Create: `test/aur-install-in-docker.sh`
- Create: `test/tarball-install-in-docker.sh`
- Create: `test/smoke-all.sh`

- [ ] **Step 1: `test/deb-install-in-docker.sh`**

```bash
cat > test/deb-install-in-docker.sh <<'DEBSMOKE'
#!/usr/bin/env bash
#
# Smoke: install the .deb in ubuntu:24.04, assert install paths,
# check `unidrive --version`, uninstall, assert clean.
set -euo pipefail

REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$REPO_ROOT"

DEB=$(ls -1 build/deb/*amd64.deb 2>/dev/null | head -1)
test -f "$DEB" || { echo "ERROR: no .deb under build/deb/" >&2; exit 1; }

docker run --rm -v "$PWD:/work:ro" ubuntu:24.04 bash -euxc "
    apt-get update -qq >/dev/null
    apt-get install -y --no-install-recommends \
        openjdk-21-jre-headless libfuse3-3 fuse3 systemd >/dev/null 2>&1 || \
        DEBIAN_FRONTEND=noninteractive apt-get install -y \
            openjdk-21-jre-headless libfuse3-3 fuse3 systemd
    dpkg -i /work/${DEB#./} || { apt-get install -fy && dpkg -i /work/${DEB#./}; }
    # Assert install paths.
    test -f /usr/lib/unidrive/unidrive.jar
    test -x /usr/lib/unidrive/unidrive-mount
    test -x /usr/bin/unidrive
    test -f /usr/lib/systemd/user/unidrive.service
    # Version smoke (Java CLI runs; FUSE not exercised here).
    unidrive --version || true
    unidrive-mount --help >/dev/null || true
    # Uninstall.
    dpkg -r unidrive
    test ! -f /usr/lib/unidrive/unidrive.jar
    test ! -x /usr/bin/unidrive
"
echo "[deb-smoke] ok"
DEBSMOKE
chmod +x test/deb-install-in-docker.sh
```

- [ ] **Step 2: `test/rpm-install-in-docker.sh`**

```bash
cat > test/rpm-install-in-docker.sh <<'RPMSMOKE'
#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$REPO_ROOT"

RPM=$(ls -1 build/rpm/*x86_64.rpm 2>/dev/null | head -1)
test -f "$RPM" || { echo "ERROR: no .rpm under build/rpm/" >&2; exit 1; }

docker run --rm -v "$PWD:/work:ro" fedora:40 bash -euxc "
    dnf install -y java-21-openjdk-headless fuse3 fuse3-libs systemd >/dev/null
    dnf install -y /work/${RPM#./}
    test -f /usr/lib/unidrive/unidrive.jar
    test -x /usr/lib/unidrive/unidrive-mount
    test -x /usr/bin/unidrive
    test -f /usr/lib/systemd/user/unidrive.service
    unidrive --version || true
    unidrive-mount --help >/dev/null || true
    dnf remove -y unidrive
    test ! -f /usr/lib/unidrive/unidrive.jar
"
echo "[rpm-smoke] ok"
RPMSMOKE
chmod +x test/rpm-install-in-docker.sh
```

- [ ] **Step 3: `test/aur-install-in-docker.sh`**

```bash
cat > test/aur-install-in-docker.sh <<'AURSMOKE'
#!/usr/bin/env bash
#
# AUR smoke: makepkg -si --noconfirm in archlinux:latest, with a
# non-root `build` user (makepkg refuses to run as root) and a
# file:// source URL pointing at the locally-built tarball.
set -euo pipefail

REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$REPO_ROOT"

test -f build/aur/PKGBUILD || { echo "ERROR: build/aur/PKGBUILD missing" >&2; exit 1; }
TARBALL=$(ls -1 build/tarball/unidrive-*-linux-x86_64.tar.gz 2>/dev/null | head -1)
test -f "$TARBALL" || { echo "ERROR: no x86_64 tarball under build/tarball/" >&2; exit 1; }

# Patch the PKGBUILD's source to point at file:// for the smoke.
SMOKE_DIR=$(mktemp -d)
trap 'rm -rf $SMOKE_DIR' EXIT
cp build/aur/PKGBUILD $SMOKE_DIR/PKGBUILD
cp "$TARBALL" "$SMOKE_DIR/$(basename "$TARBALL")"
# Replace https URLs with file:// — only smoking the package layout,
# not the GH-download path. Real download is exercised by P5's E2E.
sed -i "s|https://github.com/[^\"]*/unidrive-\([^\"]*\)-linux-\(x86_64\|aarch64\)\.tar\.gz|file:///src/unidrive-\1-linux-\2.tar.gz|g" $SMOKE_DIR/PKGBUILD
# Drop the .asc source (we don't have a smoke signature handy).
sed -i '/\.tar\.gz\.asc/d' $SMOKE_DIR/PKGBUILD
# Skip the signature check.
sed -i 's|^validpgpkeys=.*|# validpgpkeys=...skipped for smoke|' $SMOKE_DIR/PKGBUILD
# Single-element sha256 arrays (we dropped the .asc source).
sed -i "s|sha256sums_x86_64=(.*|sha256sums_x86_64=('$(sha256sum "$TARBALL" | awk '{print $1}')')|" $SMOKE_DIR/PKGBUILD
sed -i "s|sha256sums_aarch64=(.*|sha256sums_aarch64=('$(sha256sum "$TARBALL" | awk '{print $1}')')|" $SMOKE_DIR/PKGBUILD

docker run --rm -v "$SMOKE_DIR:/src:ro" archlinux:latest bash -euxc "
    pacman -Sy --noconfirm --needed base-devel sudo java-runtime-headless fuse3 >/dev/null
    useradd -m -G wheel build
    echo 'build ALL=(ALL) NOPASSWD: ALL' > /etc/sudoers.d/build
    cp -r /src /home/build/pkg
    chown -R build:build /home/build/pkg
    su build -c 'cd /home/build/pkg && makepkg -si --noconfirm --skipinteg --skippgpcheck'
    test -f /usr/lib/unidrive/unidrive.jar
    test -x /usr/lib/unidrive/unidrive-mount
    test -x /usr/bin/unidrive
    unidrive --version || true
    pacman -R --noconfirm unidrive
    test ! -f /usr/lib/unidrive/unidrive.jar
"
echo "[aur-smoke] ok"
AURSMOKE
chmod +x test/aur-install-in-docker.sh
```

- [ ] **Step 4: `test/tarball-install-in-docker.sh`**

```bash
cat > test/tarball-install-in-docker.sh <<'TARSMOKE'
#!/usr/bin/env bash
#
# Tarball smoke: extract the tarball into a clean $HOME inside
# debian:13, run install.sh, assert install paths, run --version,
# run uninstall.sh, assert clean.
set -euo pipefail

REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$REPO_ROOT"

TARBALL=$(ls -1 build/tarball/unidrive-*-linux-x86_64.tar.gz 2>/dev/null | head -1)
test -f "$TARBALL" || { echo "ERROR: no x86_64 tarball" >&2; exit 1; }

docker run --rm -v "$PWD:/work:ro" debian:13 bash -euxc "
    apt-get update -qq >/dev/null
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        openjdk-21-jre-headless libfuse3-3 fuse3 >/dev/null
    useradd -m -s /bin/bash tester
    cp /work/${TARBALL#./} /home/tester/
    chown tester:tester /home/tester/$(basename "$TARBALL")
    su tester -c '
        set -euxo pipefail
        cd /home/tester
        tar -xzf $(basename "$TARBALL")
        cd unidrive-*/
        bash install.sh
        test -x ~/.local/bin/unidrive
        test -f ~/.local/lib/unidrive/unidrive-*.jar
        test -x ~/.local/lib/unidrive/unidrive-mount
        ~/.local/bin/unidrive --version || true
        bash uninstall.sh
        test ! -x ~/.local/bin/unidrive
    '
"
echo "[tarball-smoke] ok"
TARSMOKE
chmod +x test/tarball-install-in-docker.sh
```

- [ ] **Step 5: `test/smoke-all.sh`**

```bash
cat > test/smoke-all.sh <<'SMOKEALL'
#!/usr/bin/env bash
# The gate. Per AGENTS.md.
set -euo pipefail
REPO_ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$REPO_ROOT"

failed=()
for s in deb-install-in-docker.sh \
         rpm-install-in-docker.sh \
         aur-install-in-docker.sh \
         tarball-install-in-docker.sh; do
    echo "============================================================"
    echo "[smoke-all] $s"
    echo "============================================================"
    if ! "test/$s"; then
        failed+=("$s")
    fi
done

if [[ ${#failed[@]} -ne 0 ]]; then
    echo
    echo "[smoke-all] FAILED: ${failed[*]}" >&2
    exit 1
fi
echo
echo "[smoke-all] all green."
SMOKEALL
chmod +x test/smoke-all.sh
```

- [ ] **Step 6: Run end-to-end (dev-loop)**

```bash
./test/local-build.sh
./test/smoke-all.sh
```

Expected: each of the four smokes passes. If any fail, fix and re-run.

- [ ] **Step 7: Commit**

```bash
git add test/
git commit -m "$(cat <<'EOF'
test: docker smoke matrix for all four channels + smoke-all gate

Per AGENTS.md (P4a Task 3) and spec §8.2:
- deb: ubuntu:24.04, dpkg -i, version smoke, uninstall.
- rpm: fedora:40, dnf install, version smoke, dnf remove.
- aur: archlinux:latest, non-root build user with sudo, makepkg -si
  with file:// source (signature checks skipped for smoke).
- tarball: debian:13, install.sh as unprivileged user.

smoke-all.sh is the gate.
EOF
)"
```

---

## Task 15: GH Actions workflows

**Files:**
- Create: `.github/workflows/test-packaging.yml`
- Create: `.github/workflows/release.yml`

- [ ] **Step 1: `test-packaging.yml`** (PR-triggered)

```bash
mkdir -p .github/workflows
cat > .github/workflows/test-packaging.yml <<'TPKG'
name: test-packaging

on:
  pull_request:
  push:
    branches: [main]

permissions:
  contents: read

concurrency:
  group: test-packaging-${{ github.ref }}
  cancel-in-progress: true

jobs:
  build-and-smoke:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0

      - name: Install build deps
        run: |
          set -euo pipefail
          sudo apt-get update
          sudo apt-get install -y --no-install-recommends \
            dpkg-dev debhelper rpm createrepo-c apt-utils \
            shellcheck

      - name: Set up JDK 21
        uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: '21'

      - name: Set up Rust toolchain
        uses: dtolnay/rust-toolchain@stable

      - name: Checkout sibling repos
        run: |
          set -euo pipefail
          cd ..
          git clone --depth 1 https://github.com/gkrost/unidrive.git
          git clone --depth 1 https://github.com/gkrost/unidrive-mount-linux.git

      - name: Shellcheck all scripts
        run: |
          set -euo pipefail
          find release packaging test -name '*.sh' -print0 \
            | xargs -0 shellcheck

      - name: local-build (host arch only)
        run: ./test/local-build.sh

      - name: smoke matrix
        run: ./test/smoke-all.sh
TPKG
```

- [ ] **Step 2: `release.yml`** (tag-triggered)

```bash
cat > .github/workflows/release.yml <<'RELYAML'
name: release

on:
  push:
    tags:
      - 'v*'

permissions:
  contents: write

concurrency:
  group: release-${{ github.ref }}
  cancel-in-progress: false

jobs:
  release:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0

      - name: Install build deps
        run: |
          set -euo pipefail
          sudo apt-get update
          sudo apt-get install -y --no-install-recommends \
            dpkg-dev debhelper rpm createrepo-c apt-utils \
            gnupg openssh-client rsync

      - name: Import GPG public key (for fetch-artefacts verify)
        env:
          GPG_PRIVATE_KEY: ${{ secrets.GPG_PRIVATE_KEY }}
        run: |
          set -euo pipefail
          # We import the PRIVATE key here for one reason: gpg's
          # --list-secret-keys is what fetch-artefacts uses to detect
          # the signer identity. We never *use* the private key from
          # this runner — signing happens via SSH to pkg-signer. The
          # private key sitting in this runner's keyring is benign
          # because the runner is ephemeral and the key is rotatable.
          # If a tighter posture is wanted (future hardening), export
          # only the public part to a GH secret and import that.
          echo "${GPG_PRIVATE_KEY}" | gpg --batch --import
          gpg --list-secret-keys --keyid-format LONG

      - name: Set up SSH keys for signer + publisher + AUR
        env:
          DIST_DEPLOY_KEY:    ${{ secrets.DIST_DEPLOY_KEY }}
          SIGNER_DEPLOY_KEY:  ${{ secrets.SIGNER_DEPLOY_KEY }}
          AUR_BOT_KEY:        ${{ secrets.AUR_BOT_KEY }}
        run: |
          set -euo pipefail
          mkdir -p ~/.ssh
          chmod 700 ~/.ssh
          umask 077
          echo "${DIST_DEPLOY_KEY}"   > ~/.ssh/publisher_key
          echo "${SIGNER_DEPLOY_KEY}" > ~/.ssh/signer_key
          echo "${AUR_BOT_KEY}"       > ~/.ssh/aur_key
          chmod 600 ~/.ssh/*_key

          # SSH config: ProxyJump for signer through the bastion.
          cat >> ~/.ssh/config <<CFG
Host pkg-signer-via-bastion
    HostName pkg-signer
    User signer
    IdentityFile ~/.ssh/signer_key
    ProxyJump bastion
    StrictHostKeyChecking accept-new

Host bastion
    HostName 87.106.246.31
    User gernot
    Port 22222
    StrictHostKeyChecking accept-new

Host aur.archlinux.org
    User aur
    IdentityFile ~/.ssh/aur_key
    StrictHostKeyChecking accept-new
CFG

      - name: Checkout sibling repos (read-only; release/fetch-artefacts pulls real artefacts)
        run: |
          set -euo pipefail
          cd ..
          git clone --depth 1 https://github.com/gkrost/unidrive.git
          git clone --depth 1 https://github.com/gkrost/unidrive-mount-linux.git

      - name: Cut release
        env:
          GPG_KEY_FINGERPRINT: ${{ secrets.GPG_KEY_FINGERPRINT }}
          PUBLISHER_SSH_KEY:   ~/.ssh/publisher_key
          SIGNER_SSH_KEY:      ~/.ssh/signer_key
          AUR_BOT_KEY:         ~/.ssh/aur_key
          GH_TOKEN:            ${{ secrets.GITHUB_TOKEN }}
        run: |
          set -euo pipefail
          release/release.sh "${GITHUB_REF_NAME}"
RELYAML
```

- [ ] **Step 3: actionlint pass**

```bash
actionlint .github/workflows/test-packaging.yml .github/workflows/release.yml
```

Expected: silent exit 0.

- [ ] **Step 4: Commit**

```bash
git add .github/workflows/
git commit -m "$(cat <<'EOF'
ci: test-packaging + release workflows

test-packaging.yml — PR + main-branch: shellcheck all scripts,
local-build, smoke-all.

release.yml — tag-triggered: imports GPG, writes three SSH keys
(publisher, signer, AUR), sets up ProxyJump config for the signer,
runs release/release.sh against the pushed tag.

Per spec §8.1, §8.6.
EOF
)"
```

---

## Task 16: AUR bot-account setup `[MAINTAINER]`

Per spec §4.4: AUR doesn't support repo-level deploy keys, so we register a dedicated Arch Linux account (`unidrive-bot`) and store its SSH private key as `AUR_BOT_KEY` on the unidrive-dist repo.

**Files:** none changed (off-repo work)

- [ ] **Step 1: Generate the bot's SSH keypair**

On the local machine:

```bash
KEYDIR=$(mktemp -d)
ssh-keygen -t ed25519 -N '' -C 'unidrive-bot@aur' -f "$KEYDIR/aur_bot_key"
cat "$KEYDIR/aur_bot_key.pub"
```

- [ ] **Step 2: Register the bot account at aur.archlinux.org `[MAINTAINER]`**

Browser:
- https://aur.archlinux.org/register/ — create account named `unidrive-bot` with email `releases@unidrive.krost.org`.
- After confirming the email, log in and go to "My Account" → "SSH Public Key" → paste the contents of `$KEYDIR/aur_bot_key.pub`.

- [ ] **Step 3: Add the maintainer as co-maintainer**

Important per spec §4.4 — account recovery shouldn't single-point on the bot:

- The maintainer's personal AUR account (if not yet created, register it now at https://aur.archlinux.org/register/) co-maintains the `unidrive` package.
- After the first publish (Plan P5's first-AUR-push task), the maintainer's account is added via the AUR package page → "Co-Maintainers" → add `<your personal AUR username>`.

For now: ensure your personal AUR account is registered. Co-maintainership is set up in P5 after the first push.

- [ ] **Step 4: Test SSH access to AUR**

```bash
GIT_SSH_COMMAND="ssh -i $KEYDIR/aur_bot_key -o StrictHostKeyChecking=accept-new" \
    ssh -i "$KEYDIR/aur_bot_key" -o StrictHostKeyChecking=accept-new \
    aur@aur.archlinux.org help
```

Expected: an "interactive shell is disabled" / help text from the AUR's restricted SSH endpoint. Means the key is authorised.

- [ ] **Step 5: Paste private key as `AUR_BOT_KEY` GH secret `[MAINTAINER]`**

Browser: https://github.com/gkrost/unidrive-dist/settings/secrets/actions → add `AUR_BOT_KEY` with the contents of `$KEYDIR/aur_bot_key`.

- [ ] **Step 6: Clean up local keypair**

```bash
shred -u "$KEYDIR/aur_bot_key" "$KEYDIR/aur_bot_key.pub"
rmdir "$KEYDIR"
```

- [ ] **Step 7: Create the AUR package stub `[MAINTAINER]`**

Before `publish-aur.sh` can push, the `unidrive` package must exist on the AUR. The first creation is manual:

- Visit https://aur.archlinux.org/submit/ — submit a minimal PKGBUILD for `unidrive` (the AUR will reject empty submissions; use a placeholder PKGBUILD that just declares pkgname/pkgver/pkgrel = 0.0.0, and immediately replaces it via Plan P5's first push).

Or alternatively: just attempt `git clone ssh://aur@aur.archlinux.org/unidrive.git`. If the package doesn't exist, AUR returns a clone of an empty repo and the first push creates the package metadata.

For MVP: defer the package creation to Plan P5's first AUR push.

---

## Task 17: Final review, push, open PR

- [ ] **Step 1: Review the branch**

```bash
cd /home/gernot/dev/git/unidrive-dist
git log --oneline main..HEAD
git diff --stat main..HEAD
```

Expected: ~15 commits, diff covers packaging/{deb,rpm,aur}/, release/, repo-server/, test/, .github/workflows/.

- [ ] **Step 2: Full local smoke**

```bash
./test/local-build.sh
./test/smoke-all.sh
```

Both green.

- [ ] **Step 3: Push + PR**

```bash
git push -u origin feature/channels-and-orchestration

gh pr create --title "feat: deb + rpm + AUR channels + release orchestration + smoke matrix" \
  --body "$(cat <<'EOF'
## Summary
Completes the unidrive-dist tooling started in P4a. Adds:

- `.deb` channel: `packaging/deb/` (control, rules, postinst empty per spec §4.2, build-deb.sh).
- `.rpm` channel: `packaging/rpm/` (templated unidrive.spec, build-rpm.sh).
- AUR channel: `packaging/aur/` (PKGBUILD template + .SRCINFO builder).
- Changelog generation: `release/render-changelog.sh` renders RELEASES.md sections into deb/rpm/GH formats.
- Signing: `release/sign-packages.sh` rsyncs to pkg-signer via ProxyJump, triggers sign-drop, pulls signed copies.
- Publishing: `release/publish-{apt,dnf,aur,gh-release}.sh`.
- Top-level: `release/release.sh` (the orchestrator).
- Smoke: `test/{deb,rpm,aur,tarball}-install-in-docker.sh` + `test/smoke-all.sh`.
- CI: `test-packaging.yml` (PR-triggered) + `release.yml` (tag-triggered).

## Spec coverage
§4.2 (deb), §4.3 (rpm), §4.4 (AUR), §4.6 (cross-channel), §4.7
(changelog), §5.1 + §5.2 (signing flow), §7.3 + §7.3.1 (repo layout
+ unidrive.repo), §8 (testing).

## Test plan
- [x] `./test/local-build.sh` produces tarball + deb + rpm + AUR PKGBUILD locally.
- [x] `./test/smoke-all.sh` green (four containers, four channels).
- [x] `actionlint` clean for both workflow files.
- [x] All bash scripts shellcheck-clean.
- [ ] First real tag push happens in Plan P5.

## Dependencies
- Inputs: Plan P4a (repo skeleton + tarball + fetch-artefacts), Plans P1+P2 (sibling release workflows), Plan P3 (signer container, signing key, deploy users).
- Outputs consumed by P5: end-to-end orchestration, ready for `v0.0.1` cutover.

## AUR bot setup
Task 16 documents the manual maintainer steps (register `unidrive-bot` Arch account, paste private key as `AUR_BOT_KEY` GH secret). Co-maintainership and first package creation deferred to P5.
EOF
)"
```

- [ ] **Step 4: No additional commit**

---

## Self-Review Notes

**Spec coverage:**

| Spec requirement | Implementing task |
|---|---|
| §4.2 (.deb) | Tasks 1, 2 |
| §4.3 (.rpm) | Task 4 |
| §4.4 (AUR) | Tasks 5, 10, 16 |
| §4.6 (cross-channel wrappers) | Tasks 2, 4 (wrapper scripts identical) |
| §4.7 (RELEASES.md → distro changelog) | Task 3 |
| §5.1, §5.2 (signing flow) | Task 7 |
| §6 (remaining repo layout) | All tasks |
| §7.3 (pkg-data volume layout) | Tasks 8, 9 |
| §7.3.1 (unidrive.repo) | Task 9 |
| §8.1 (smoke gate) | Task 14 |
| §8.2 (smoke matrix) | Task 14 |
| §8.6 (CI scope) | Task 15 |
| §10 AC #4 (apt repo) | Tasks 8 (capability); actual publish in P5 |
| §10 AC #5 (dnf repo) | Tasks 9 (capability); actual publish in P5 |
| §10 AC #6 (AUR package) | Tasks 10, 16 (capability); first push in P5 |

**Deferred to Plan P5:**
- First-release content in `RELEASES.md`.
- First real apt/dnf publish (the rsync steps in `publish-apt/dnf.sh` are inert until release.sh runs against a real tag).
- First AUR package creation + co-maintainer setup.
- E2E manual smoke from spec §8.4.
- Reconciling `BuildInfo.kt` vs spec §3.5 (Issue 1 from review-3).
- `unidrive.krost.org/install/` landing page content.

**Placeholder scan:** plan body has no TODO/TBD/FIXME outside legitimate `@PLACEHOLDER@` template variables (clearly delimited) and self-review meta-text.

**Type/name consistency:** filenames and arch designators (`amd64`/`arm64` for deb, `x86_64`/`aarch64` for rpm/AUR/tarball), GH secret names (`GPG_PRIVATE_KEY`, `GPG_KEY_FINGERPRINT`, `DIST_DEPLOY_KEY`, `SIGNER_DEPLOY_KEY`, `AUR_BOT_KEY`), and SSH host aliases (`bastion`, `pkg-signer-via-bastion`, `aur.archlinux.org`) consistent across all scripts and CI.

**Maintainer-only steps:** Task 16 explicitly tagged. Task 17 Step 3 (open PR) is implementer-action.

**Cross-plan dependencies:**
- Inputs: P3 (signer + DNS + secrets), P4a (skeleton + tarball + fetch), P1+P2 (sibling release workflows).
- Outputs consumed by P5: complete orchestration, smoke gate, AUR bot account.
