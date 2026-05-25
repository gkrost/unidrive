# Plan P2: Rust Release Workflow (unidrive-mount-linux)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Cross-repo note:** This plan describes work in the **sibling repo** `unidrive-mount-linux` at `/home/gernot/dev/git/unidrive-mount-linux/`. The plan file itself lives in `unidrive/docs/dev/plans/` because:

1. The unidrive repo's AGENTS.md explicitly sanctions `docs/dev/plans/` for design plans.
2. The unidrive-mount-linux AGENTS.md has a "Doc surface is bounded" rule that does not list `docs/plans/` (only `README.md`, `BACKLOG.md`, `CLOSED.md`, plus optional `docs/adr/`).
3. Co-locating the distribution-spec plans next to the distribution spec keeps cross-references trivial.

The implementer reads this plan from `unidrive/docs/dev/plans/` but **commits the implementation to `unidrive-mount-linux/`**.

**Goal:** Add a tag-triggered GitHub Actions workflow to the `unidrive-mount-linux` repo that cross-compiles the `unidrive-mount` binary for `x86_64-unknown-linux-gnu` and `aarch64-unknown-linux-gnu` on `v*` tags, packages each into a `.tar.gz` containing the binary + LICENSE + NOTICE, computes SHA256, signs both the tarball and the checksum with the `unidrive-releases` GPG key, and attaches all per-arch files to a GitHub Release.

**Architecture:** A single new workflow file `.github/workflows/release.yml` that fires on `push` of tags matching `v*`. The existing `ci.yml` (running `cargo test` on PR + main) stays untouched. The release job uses a matrix on `target` (x86_64 and aarch64), invokes `cross` (cross-rs/cross) for the aarch64 build, stages the binary + license files into a `.tar.gz`, signs the tarball + checksum, and uploads via `softprops/action-gh-release`. Matrix jobs all upload to the same GH Release, accumulating files.

**Tech Stack:** GitHub Actions, Cargo + `cross` (cross-rs/cross for aarch64), `gpg`, `sha256sum`, `tar`, `softprops/action-gh-release@v2`.

**Spec reference:** `unidrive/docs/dev/specs/unidrive-distribution-design.md` §3.3 step 2, §4.1 (per-arch tarballs as inputs to the dist channel), §5 (GPG signing identity), §10 AC #2 (acceptance criterion: GH Release contains `unidrive-mount-0.0.1-{x86_64,aarch64}.tar.gz` + `.sha256` + `.asc` per arch).

**Scope explicitly NOT in this plan (deferred to other plans):**

- No changes to the JVM repo (Plan P1).
- No distro packaging (Plan P4).
- No apt/dnf repo publishing (Plans P3 + P4 + P5).
- No FUSE-runtime changes; no test changes. The release workflow consumes whatever `cargo build --release` produces.

**Pre-flight assumptions to verify in Task 0:**

- `unidrive-mount-linux` is checked out at `/home/gernot/dev/git/unidrive-mount-linux/`.
- The `mount` crate's `Cargo.toml` declares some version. The spec requires `v0.0.1` for the first MVP release across all three repos; this plan **bumps the crate to `0.0.1`** because the current `0.1.0` would otherwise mismatch the spec's user-visible coordinated SemVer.
- GH Actions secrets `GPG_PRIVATE_KEY` and `GPG_KEY_FINGERPRINT` exist on the unidrive-mount-linux repo. These are provisioned by Plan P3 on the maintainer side; **this plan does not provision them**, only consumes them.

**Cross-repo coordination notes:**

- The spec lives in the sibling `unidrive` repo. The unidrive-mount-linux AGENTS.md "Don't reach into the sibling unidrive repo and modify it" rule applies: the implementer only **reads** the spec (and this plan), never edits the sibling.
- The same GPG key (`unidrive-releases`) is shared across both sibling repos. Each repo gets its own copy of the private key as a GH secret (this is unavoidable — GH secrets don't span repos). Rotation procedure in the spec §3.7 covers updating both at once.

---

## File Structure

This plan creates one workflow file and modifies one manifest file, **both in `unidrive-mount-linux`**.

| Path (in unidrive-mount-linux) | Purpose | Change type |
|------|---------|-------------|
| `.github/workflows/release.yml` | Tag-triggered release workflow. Cross-builds, signs, uploads. | Create |
| `mount/Cargo.toml` | Crate manifest. Bump `version` from current value to `0.0.1`. | Modify (one line) |

Test-vehicle files (created and removed during the plan; not committed):

| Path | Purpose | Lifecycle |
|------|---------|-----------|
| `/tmp/unidrive-mount-release-smoke/` | Local-dry-run scratch directory | Created in Task 1; removed at end of Task 1 |
| `/tmp/unidrive-mount-release-dryrun/` | Pre-tag smoke against the real cross build | Created in Task 4; removed at end of Task 4 |

---

## Task 0: Branch + prerequisites check

**Files:** none changed

- [ ] **Step 1: Create a working branch off main in unidrive-mount-linux**

Run:

```bash
cd /home/gernot/dev/git/unidrive-mount-linux
git fetch origin
git checkout -b release/rust-workflow origin/main
```

Expected: `Switched to a new branch 'release/rust-workflow'`.

- [ ] **Step 2: Read the current crate version**

Run:

```bash
grep -E '^version = ' mount/Cargo.toml
```

Expected: a line like `version = "0.1.0"` or similar.

Note the current value for Task 2's commit message.

- [ ] **Step 3: Verify a release build works locally**

Run:

```bash
cargo build --release --bin unidrive-mount
ls -la target/release/unidrive-mount
```

Expected: binary exists, several MB. If it fails, stop and reconcile — this plan ships release engineering only.

- [ ] **Step 4: No commit yet**

---

## Task 1: Local smoke-test of the tarball + sign sequence

Same posture as Plan P1 Task 1: exercise the bash that will go into the workflow YAML against a throw-away test key, locally, before committing it.

**Files:** none changed (scratch directory only)

- [ ] **Step 1: Create the scratch directory and a throw-away test key**

Run:

```bash
mkdir -p /tmp/unidrive-mount-release-smoke
cd /tmp/unidrive-mount-release-smoke
export GNUPGHOME="$(pwd)/gnupg-test"
mkdir -p "$GNUPGHOME"
chmod 700 "$GNUPGHOME"
cat > key-spec <<'EOF'
%no-protection
Key-Type: EdDSA
Key-Curve: ed25519
Key-Usage: sign
Name-Real: Mount Release Smoke
Name-Email: mount-smoke@invalid.local
Expire-Date: 1d
%commit
EOF
gpg --batch --gen-key key-spec
FPR=$(gpg --list-secret-keys --with-colons | awk -F: '/^fpr/{print $10; exit}')
echo "Fingerprint: $FPR"
```

Expected: 40-char hex fingerprint printed.

- [ ] **Step 2: Simulate the per-arch staging + tar + sign sequence**

Run (still in `/tmp/unidrive-mount-release-smoke`):

```bash
mkdir -p staging/unidrive-mount-0.0.1
echo "pretend this is the unidrive-mount binary" > staging/unidrive-mount-0.0.1/unidrive-mount
chmod +x staging/unidrive-mount-0.0.1/unidrive-mount
cp /home/gernot/dev/git/unidrive-mount-linux/LICENSE staging/unidrive-mount-0.0.1/
cp /home/gernot/dev/git/unidrive-mount-linux/NOTICE staging/unidrive-mount-0.0.1/

cd staging
tar -czf ../unidrive-mount-0.0.1-x86_64.tar.gz unidrive-mount-0.0.1/
cd ..

sha256sum unidrive-mount-0.0.1-x86_64.tar.gz > unidrive-mount-0.0.1-x86_64.tar.gz.sha256
cat unidrive-mount-0.0.1-x86_64.tar.gz.sha256

gpg --batch --yes --pinentry-mode loopback \
    --local-user "$FPR" \
    --armor --detach-sign \
    --output unidrive-mount-0.0.1-x86_64.tar.gz.asc \
    unidrive-mount-0.0.1-x86_64.tar.gz
gpg --batch --yes --pinentry-mode loopback \
    --local-user "$FPR" \
    --armor --detach-sign \
    --output unidrive-mount-0.0.1-x86_64.tar.gz.sha256.asc \
    unidrive-mount-0.0.1-x86_64.tar.gz.sha256

ls -1 unidrive-mount-0.0.1-x86_64.*
```

Expected: four files — the tarball, `.sha256`, `.asc`, `.sha256.asc`.

- [ ] **Step 3: Verify signatures and tarball contents**

Run:

```bash
gpg --verify unidrive-mount-0.0.1-x86_64.tar.gz.asc \
            unidrive-mount-0.0.1-x86_64.tar.gz
gpg --verify unidrive-mount-0.0.1-x86_64.tar.gz.sha256.asc \
            unidrive-mount-0.0.1-x86_64.tar.gz.sha256
tar -tzf unidrive-mount-0.0.1-x86_64.tar.gz
```

Expected: both `gpg --verify` print `Good signature`. Tar listing:

```
unidrive-mount-0.0.1/
unidrive-mount-0.0.1/unidrive-mount
unidrive-mount-0.0.1/LICENSE
unidrive-mount-0.0.1/NOTICE
```

- [ ] **Step 4: Tear down**

Run:

```bash
cd /home/gernot/dev/git/unidrive-mount-linux
unset GNUPGHOME
rm -rf /tmp/unidrive-mount-release-smoke
```

- [ ] **Step 5: No commit**

---

## Task 2: Bump the crate version to 0.0.1

**Files:**
- Modify: `unidrive-mount-linux/mount/Cargo.toml` (one-line change)

- [ ] **Step 1: Edit `mount/Cargo.toml`**

In `/home/gernot/dev/git/unidrive-mount-linux/mount/Cargo.toml`, locate the `version = "..."` line in `[package]`. Change it to:

```toml
version = "0.0.1"
```

- [ ] **Step 2: Verify the change**

Run:

```bash
cd /home/gernot/dev/git/unidrive-mount-linux
grep -E '^version = ' mount/Cargo.toml
```

Expected: `version = "0.0.1"`.

- [ ] **Step 3: Verify the workspace still builds**

Run:

```bash
cargo build --release --bin unidrive-mount 2>&1 | tail -5
```

Expected: `Compiling unidrive-mount v0.0.1 ...` and a successful build line.

- [ ] **Step 4: Run the existing test gate to confirm no regression**

Run:

```bash
cargo test --all-targets 2>&1 | tail -20
```

Expected: tests pass (or some pass and the rest are FUSE-environment-conditional and skip).

- [ ] **Step 5: Commit**

Run:

```bash
git add mount/Cargo.toml Cargo.lock 2>/dev/null || git add mount/Cargo.toml
git commit -m "$(cat <<'EOF'
chore: align crate version to coordinated MVP release 0.0.1

The distribution spec
(sibling unidrive repo, docs/dev/specs/unidrive-distribution-design.md
§10 AC #2) requires the first MVP release to be v0.0.1 across all
three repos (unidrive, unidrive-mount-linux, unidrive-dist). The
unidrive JVM repo is already at 0.0.1; this aligns the Rust crate.

User-visible version reported by `unidrive-mount --version` follows
suit. No source-code or behaviour changes.
EOF
)"
```

Cargo.lock is gitignored on this repo (per the existing ci.yml comment); the `git add Cargo.lock` falls through harmlessly.

---

## Task 3: Add the release workflow file

**Files:**
- Create: `unidrive-mount-linux/.github/workflows/release.yml`

- [ ] **Step 1: Write the workflow file**

Create `/home/gernot/dev/git/unidrive-mount-linux/.github/workflows/release.yml` with this exact content:

```yaml
name: release

# Fires on tag push matching v* (e.g. v0.0.1). The strict tag-format regex
# `^v(\d+\.\d+\.\d+)(-pkg\d+)?$` is enforced on the unidrive-dist side; here
# we accept anything starting with `v` so the workflow stays simple.
on:
  push:
    tags:
      - 'v*'

permissions:
  contents: write  # required to create the GH Release

concurrency:
  # Per-tag concurrency: a re-run of the same tag won't race itself, but
  # different tags don't block each other.
  group: release-${{ github.ref }}
  cancel-in-progress: false

jobs:
  build:
    name: build (${{ matrix.target }})
    runs-on: ubuntu-latest
    strategy:
      fail-fast: false
      matrix:
        include:
          - target: x86_64-unknown-linux-gnu
            arch: x86_64
            use_cross: false
          - target: aarch64-unknown-linux-gnu
            arch: aarch64
            use_cross: true
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Install stable Rust toolchain
        # edition = "2024" in Cargo.toml requires Rust >= 1.85.
        uses: dtolnay/rust-toolchain@stable
        with:
          targets: ${{ matrix.target }}

      - name: Install fuse3 dev headers (native target only)
        if: ${{ matrix.use_cross == false }}
        run: |
          set -euo pipefail
          sudo apt-get update
          sudo apt-get install -y --no-install-recommends \
            libfuse3-dev fuse3

      - name: Install cross (cross-arch target only)
        if: ${{ matrix.use_cross == true }}
        # cross-rs/cross runs the build in a Docker container so the host
        # doesn't need an aarch64 sysroot. Pin a version for reproducibility.
        run: |
          set -euo pipefail
          cargo install --locked --version 0.2.5 cross

      - name: Build release binary
        run: |
          set -euo pipefail
          if [ "${{ matrix.use_cross }}" = "true" ]; then
            cross build --release --target "${{ matrix.target }}" --bin unidrive-mount
          else
            cargo build --release --target "${{ matrix.target }}" --bin unidrive-mount
          fi

      - name: Verify expected binary location
        run: |
          set -euo pipefail
          bin="target/${{ matrix.target }}/release/unidrive-mount"
          test -f "$bin" || {
            echo "ERROR: expected $bin to exist after build" >&2
            find target -name unidrive-mount -type f >&2 || true
            exit 1
          }
          file "$bin"

      - name: Stage tarball
        run: |
          set -euo pipefail
          ver="${GITHUB_REF_NAME#v}"
          stage="staging/unidrive-mount-${ver}"
          mkdir -p "$stage"
          cp "target/${{ matrix.target }}/release/unidrive-mount" "$stage/"
          cp LICENSE NOTICE "$stage/"
          tarball="unidrive-mount-${ver}-${{ matrix.arch }}.tar.gz"
          tar -C staging -czf "$tarball" "unidrive-mount-${ver}"
          sha256sum "$tarball" > "${tarball}.sha256"
          cat "${tarball}.sha256"
          tar -tzf "$tarball"

      - name: Import signing key
        env:
          GPG_PRIVATE_KEY: ${{ secrets.GPG_PRIVATE_KEY }}
        run: |
          set -euo pipefail
          if [ -z "${GPG_PRIVATE_KEY:-}" ]; then
            echo "ERROR: GPG_PRIVATE_KEY secret is not set on this repo." >&2
            echo "  Provisioned by Plan P3. Add it under Settings →" >&2
            echo "  Secrets and variables → Actions before retrying." >&2
            exit 1
          fi
          echo "$GPG_PRIVATE_KEY" | gpg --batch --import
          gpg --list-secret-keys --keyid-format LONG

      - name: Sign tarball + checksum
        env:
          GPG_KEY_FINGERPRINT: ${{ secrets.GPG_KEY_FINGERPRINT }}
        run: |
          set -euo pipefail
          if [ -z "${GPG_KEY_FINGERPRINT:-}" ]; then
            echo "ERROR: GPG_KEY_FINGERPRINT secret is not set on this repo." >&2
            exit 1
          fi
          ver="${GITHUB_REF_NAME#v}"
          tarball="unidrive-mount-${ver}-${{ matrix.arch }}.tar.gz"
          gpg --batch --yes --pinentry-mode loopback \
              --local-user "$GPG_KEY_FINGERPRINT" \
              --armor --detach-sign \
              --output "${tarball}.asc" \
              "$tarball"
          gpg --batch --yes --pinentry-mode loopback \
              --local-user "$GPG_KEY_FINGERPRINT" \
              --armor --detach-sign \
              --output "${tarball}.sha256.asc" \
              "${tarball}.sha256"
          ls -1 "${tarball}"*

      - name: Verify signatures
        run: |
          set -euo pipefail
          ver="${GITHUB_REF_NAME#v}"
          tarball="unidrive-mount-${ver}-${{ matrix.arch }}.tar.gz"
          gpg --verify "${tarball}.asc" "$tarball"
          gpg --verify "${tarball}.sha256.asc" "${tarball}.sha256"

      - name: Attach to GitHub Release
        # Pin to a SHA — v2 is a moving tag. As of writing, v2.0.8 =
        # 01570a1f39cb168c169c802c3bceb9e93fb10974. Bump deliberately.
        uses: softprops/action-gh-release@01570a1f39cb168c169c802c3bceb9e93fb10974
        with:
          tag_name: ${{ github.ref_name }}
          name: ${{ github.ref_name }}
          # The release is created by whichever matrix job runs first;
          # the second job appends its arch's files. The action is
          # idempotent.
          body: |
            UniDrive FUSE co-daemon ${{ github.ref_name }}

            Per-arch tarballs for the coordinated UniDrive release. Distro
            packages and the canonical end-user tarball are produced by
            unidrive-dist and published at https://unidrive.krost.org/install/.

            Verify with:
              gpg --verify unidrive-mount-*.tar.gz.asc unidrive-mount-*.tar.gz
              sha256sum -c unidrive-mount-*.tar.gz.sha256
          files: |
            unidrive-mount-*-${{ matrix.arch }}.tar.gz
            unidrive-mount-*-${{ matrix.arch }}.tar.gz.sha256
            unidrive-mount-*-${{ matrix.arch }}.tar.gz.asc
            unidrive-mount-*-${{ matrix.arch }}.tar.gz.sha256.asc
          fail_on_unmatched_files: true
          draft: false
          prerelease: false
```

- [ ] **Step 2: Verify YAML parses**

Run:

```bash
cd /home/gernot/dev/git/unidrive-mount-linux
python3 -c "import yaml; yaml.safe_load(open('.github/workflows/release.yml'))" && echo "YAML OK"
```

Expected: `YAML OK`.

- [ ] **Step 3: Verify bash blocks use `set -euo pipefail`**

Run:

```bash
grep -c 'set -euo pipefail' .github/workflows/release.yml
```

Expected: `7` or more.

- [ ] **Step 4: Commit**

Run:

```bash
git add .github/workflows/release.yml
git commit -m "$(cat <<'EOF'
ci(release): tag-triggered GH Actions workflow to publish signed Rust tarballs

Fires on v* tag push. Cross-builds unidrive-mount for x86_64 and
aarch64 (latter via cross-rs/cross), packages each as
unidrive-mount-<ver>-<arch>.tar.gz with LICENSE+NOTICE, signs both
the tarball and the SHA256 file with the unidrive-releases GPG key,
and attaches the per-arch artefact set to a GitHub Release.

Per sibling unidrive repo
docs/dev/specs/unidrive-distribution-design.md §3.3 step 2 and
§10 AC #2.
EOF
)"
```

---

## Task 4: Local dry-run of the workflow's bash logic

Mirror Plan P1's Task 4 against a real `cargo build --release` output before tagging.

**Files:** none changed (scratch directory only)

- [ ] **Step 1: Build the binary fresh**

Run:

```bash
cd /home/gernot/dev/git/unidrive-mount-linux
cargo build --release --bin unidrive-mount
test -f target/release/unidrive-mount
file target/release/unidrive-mount
```

Expected: binary exists, `file` reports `ELF 64-bit LSB pie executable, x86-64`.

(The dry-run skips aarch64 cross-build — that needs `cross` + Docker. Bash logic is identical apart from the `cross` vs `cargo` command swap.)

- [ ] **Step 2: Re-create the throw-away test key**

Run:

```bash
mkdir -p /tmp/unidrive-mount-release-dryrun
cd /tmp/unidrive-mount-release-dryrun
export GNUPGHOME="$(pwd)/gnupg-test"
mkdir -p "$GNUPGHOME"
chmod 700 "$GNUPGHOME"
cat > key-spec <<'EOF'
%no-protection
Key-Type: EdDSA
Key-Curve: ed25519
Key-Usage: sign
Name-Real: Mount Release DryRun
Name-Email: mount-dryrun@invalid.local
Expire-Date: 1d
%commit
EOF
gpg --batch --gen-key key-spec
FPR=$(gpg --list-secret-keys --with-colons | awk -F: '/^fpr/{print $10; exit}')
```

- [ ] **Step 3: Replay the workflow bash against the real binary**

Run (still in `/tmp/unidrive-mount-release-dryrun`):

```bash
export GITHUB_REF_NAME=v0.0.1
export GPG_KEY_FINGERPRINT="$FPR"
arch=x86_64

ver="${GITHUB_REF_NAME#v}"
stage="staging/unidrive-mount-${ver}"
mkdir -p "$stage"
cp /home/gernot/dev/git/unidrive-mount-linux/target/release/unidrive-mount "$stage/"
cp /home/gernot/dev/git/unidrive-mount-linux/LICENSE "$stage/"
cp /home/gernot/dev/git/unidrive-mount-linux/NOTICE "$stage/"

tarball="unidrive-mount-${ver}-${arch}.tar.gz"
tar -C staging -czf "$tarball" "unidrive-mount-${ver}"
sha256sum "$tarball" > "${tarball}.sha256"
cat "${tarball}.sha256"
tar -tzf "$tarball"

gpg --batch --yes --pinentry-mode loopback \
    --local-user "$GPG_KEY_FINGERPRINT" \
    --armor --detach-sign \
    --output "${tarball}.asc" \
    "$tarball"
gpg --batch --yes --pinentry-mode loopback \
    --local-user "$GPG_KEY_FINGERPRINT" \
    --armor --detach-sign \
    --output "${tarball}.sha256.asc" \
    "${tarball}.sha256"

gpg --verify "${tarball}.asc" "$tarball"
gpg --verify "${tarball}.sha256.asc" "${tarball}.sha256"

ls -1 "${tarball}"*
```

Expected:
- Tarball lists only `unidrive-mount-0.0.1/{unidrive-mount,LICENSE,NOTICE}`.
- Both `gpg --verify` print `Good signature`.
- Four files present.

If anything fails, do not proceed — reconcile against `release.yml`.

- [ ] **Step 4: Tear down**

Run:

```bash
cd /home/gernot/dev/git/unidrive-mount-linux
unset GNUPGHOME GITHUB_REF_NAME GPG_KEY_FINGERPRINT
rm -rf /tmp/unidrive-mount-release-dryrun
```

- [ ] **Step 5: No commit**

---

## Task 5: actionlint pass

**Files:** none changed (unless actionlint flags issues)

- [ ] **Step 1: Ensure actionlint is on PATH**

If installed during Plan P1, it's already available. Otherwise:

```bash
if ! command -v actionlint >/dev/null 2>&1; then
    if command -v go >/dev/null 2>&1; then
        go install github.com/rhysd/actionlint/cmd/actionlint@latest
        export PATH="$HOME/go/bin:$PATH"
    else
        bash <(curl -sSL https://raw.githubusercontent.com/rhysd/actionlint/main/scripts/download-actionlint.bash)
        export PATH="$(pwd):$PATH"
    fi
fi
actionlint --version
```

- [ ] **Step 2: Lint**

Run:

```bash
cd /home/gernot/dev/git/unidrive-mount-linux
actionlint .github/workflows/release.yml
```

Expected: silent exit 0.

- [ ] **Step 3: If fixes were needed, commit**

```bash
git add .github/workflows/release.yml
git commit -m "ci(release): address actionlint findings"
```

Otherwise no commit.

---

## Task 6: Final review, run existing CI gate locally, push, open PR

**Files:** none changed

- [ ] **Step 1: Review the branch's diff**

Run:

```bash
cd /home/gernot/dev/git/unidrive-mount-linux
git log --oneline origin/main..HEAD
git diff --stat origin/main..HEAD
```

Expected: 2–3 commits. Diff stat shows `mount/Cargo.toml` modified and `.github/workflows/release.yml` created.

- [ ] **Step 2: Run the existing CI gate locally**

The unidrive-mount-linux AGENTS.md says `cargo test` is the gate.

Run:

```bash
cargo test --all-targets 2>&1 | tail -20
```

Expected: tests pass (FUSE-conditional tests may skip in environments without the kernel module — that's fine).

- [ ] **Step 3: Push**

Run:

```bash
git push -u origin release/rust-workflow
```

- [ ] **Step 4: Open PR**

Run:

```bash
gh pr create --title "ci(release): tag-triggered Rust release workflow + 0.0.1 version bump" \
  --body "$(cat <<'EOF'
## Summary
- Bumps `mount/Cargo.toml` `version` to `0.0.1` to align with the coordinated MVP release version (spec §10 AC #2).
- Adds `.github/workflows/release.yml` that cross-builds `unidrive-mount` for x86_64 and aarch64 on `v*` tag push, packages each as a tarball with LICENSE+NOTICE, signs both tarball and SHA256 with the `unidrive-releases` GPG key, attaches all per-arch files to a GH Release.

## Spec reference
Sibling `unidrive` repo: `docs/dev/specs/unidrive-distribution-design.md` §3.3 step 2, §4.1, §5, §10 AC #2.

## Plan reference
Sibling `unidrive` repo: `docs/dev/plans/mount-linux-release-workflow-rust.md` on branch `spec/distribution`.

## Test plan
- [x] Local dry-run of the workflow's bash blocks against a throw-away test key (Task 1, Task 4).
- [x] `actionlint` clean (Task 5).
- [x] `cargo test --all-targets` still green (Task 6 step 2).
- [ ] First real tag push happens in Plan P5 (the first-release cutover).

## Cross-build smoke
The aarch64 path uses `cross-rs/cross v0.2.5` inside CI. Not exercised in Task 4 (which only does the x86_64 native path); the cross-build first runs for real on the tag push in Plan P5.

## Dependencies
- Consumer: Plan P4 (unidrive-dist) reads from the GH Releases this workflow publishes.
- Provider: Plan P3 (krost-infra) provisions the `GPG_PRIVATE_KEY` and `GPG_KEY_FINGERPRINT` GH secrets on this repo. Without them, the workflow's "Import signing key" step fails fast.
EOF
)"
```

Expected: PR opened, URL printed.

- [ ] **Step 5: No additional commit**

---

## Self-Review Notes

**Spec coverage:**

| Spec requirement | Implementing task |
|---|---|
| §3.3 step 2: tag → GH Action cross-compiles, signs, attaches | Task 3 (workflow), Task 4 (x86_64 dry-run) |
| §4.1: per-arch tarballs (`unidrive-mount-X.Y.Z-{x86_64,aarch64}.tar.gz`) | Task 3 matrix |
| §5: signing identity is the project key | Task 3 uses `GPG_PRIVATE_KEY` + `GPG_KEY_FINGERPRINT` secrets provisioned by P3 |
| §10 AC #2: GH Release contains per-arch tarball + `.sha256` + `.asc` | Task 3 "Attach to GitHub Release" step |
| MVP version is `0.0.1` | Task 2 bumps `mount/Cargo.toml` |

**Cross-repo coordination:** the spec lives in the sibling `unidrive` repo. The unidrive-mount-linux AGENTS.md "Don't reach into the sibling unidrive repo and modify it" rule is honoured — this plan only reads the spec, and its file lives in unidrive (where docs are sanctioned).

**Placeholder scan:** no TODO/TBD/FIXME. The action-SHA pin is concrete with a hygiene note.

**Type/name consistency:** tarball filename pattern is consistently `unidrive-mount-<version>-<arch>.tar.gz`. The four-file set per arch (`tarball`, `.sha256`, `.asc`, `.sha256.asc`) is consistently named. GH secrets are referenced only in Task 3.
