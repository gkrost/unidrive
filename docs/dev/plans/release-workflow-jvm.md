# Plan P1: JVM Release Workflow (unidrive)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a tag-triggered GitHub Actions workflow to the `unidrive` repo that builds the CLI shadowJar on `v*` tags, computes its SHA256, signs both the JAR and the SHA256 file with the `unidrive-releases` GPG key, and attaches all four artefacts to a GitHub Release.

**Architecture:** A single new workflow file `.github/workflows/release.yml` that fires on `push` of tags matching `v*`. The existing `build.yml` (running `./gradlew check` on PR + main) stays untouched. The release job runs `./gradlew :app:cli:shadowJar`, renames/copies the resulting JAR into a staging directory, signs with `gpg --detach-sign --armor` using a passphrase-less key imported from a GH Actions secret, and uploads via `softprops/action-gh-release`. No source-code changes; no Gradle changes; existing build infrastructure is consumed unchanged.

**Tech Stack:** GitHub Actions, Gradle (existing), `gpg` (apt-installed in the runner), `sha256sum` (apt-installed), `softprops/action-gh-release@v2` (community action; pin to a SHA in the implementation).

**Spec reference:** `docs/dev/specs/unidrive-distribution-design.md` §3.3 (tagging protocol step 1), §4.1 (tarball contents — JAR + sig), §5 (GPG signing identity), §10 AC #1 (acceptance criterion: GH Release contains `unidrive-0.0.1.jar` + `.sha256` + `.asc`).

**Scope explicitly NOT in this plan (deferred to other plans):**

- No changes to the `unidrive-mount-linux` Rust binary (Plan P2).
- No distro packaging (Plan P4).
- No apt/dnf repo publishing (Plans P3 + P4 + P5).
- No `dist/install.sh` changes (Plan P4 moves the canonical installer to `unidrive-dist`).
- **No `BuildInfo.kt` changes here.** The existing `--version` enrichment with commit-SHA is pre-existing; P1 does not touch it. **However, this leaves an unresolved tension with spec §3.5** — the spec says `unidrive --version` prints exactly the user-visible version (e.g. `0.0.1`, no commit suffix), but the current `BuildInfo.kt` generator embeds the SHA on every build, producing strings like `0.0.1 (abc1234)`. **Reconciling this is Plan P5's responsibility**, not P1's. P5's pre-flight checklist includes a task to either (a) suppress the `(commit)` suffix when building from a tag with no dirty tree, or (b) accept the enriched form for the first release and treat the spec §3.5 wording as descriptive-of-the-coordinated-version rather than literal-CLI-output. The choice is deferred so P1 doesn't grow a scope it doesn't need; the tension is documented so the first real tag push doesn't accidentally violate the spec.

**Pre-flight assumptions to verify in Task 0:**

- Repo is on branch off `main` (a working branch — name your choice, e.g. `release/jvm-workflow`).
- Project version in `core/build.gradle.kts` is already `0.0.1` (it is, confirmed at spec time).
- GH Actions secrets `GPG_PRIVATE_KEY`, `GPG_KEY_FINGERPRINT` exist on the unidrive repo. These are provisioned in Plan P3 step on the maintainer side; **this plan does not provision them**, only consumes them. Plan P3's "krost-infra signing setup" task generates the key and exports the public part; the maintainer manually pastes the private key into the GH secret. If the secret doesn't exist yet, the release workflow will fail at sign time — that's a feature, not a bug.

---

## File Structure

This plan creates exactly one file. No source-code changes.

| Path | Purpose |
|------|---------|
| `.github/workflows/release.yml` | Tag-triggered release workflow. Builds shadowJar, computes SHA256, signs with GPG, attaches to GH Release. |

Test-vehicle files (created and deleted during the plan; not committed):

| Path | Purpose | Lifecycle |
|------|---------|-----------|
| `/tmp/unidrive-release-smoke/` | Scratch directory for local dry-run of the workflow's bash steps | Created in Task 4; deleted at end of Task 4 |

---

## Task 0: Branch + prerequisites check

**Files:** none changed

- [ ] **Step 1: Create a working branch off main**

Run:

```bash
cd /home/gernot/dev/git/unidrive
git fetch origin
git checkout -b release/jvm-workflow origin/main
```

Expected: `Switched to a new branch 'release/jvm-workflow'`. If main has moved past where you started, that's fine — this plan is self-contained against any recent main.

- [ ] **Step 2: Verify project version is 0.0.1**

Run:

```bash
grep -E '^\s*version = ' core/build.gradle.kts
```

Expected output: `    version = "0.0.1"` (with whatever leading whitespace).

If anything other than `0.0.1`: **stop**. The spec acceptance criteria (§10 AC #1) require the first release to be `0.0.1`. Reconcile with the user before proceeding.

- [ ] **Step 3: Verify the shadowJar task produces the expected filename locally**

Run:

```bash
cd core && ./gradlew :app:cli:shadowJar -q && ls -1 app/cli/build/libs/
```

Expected: a single file `unidrive-0.0.1.jar` (the `archiveBaseName="unidrive"` + `archiveClassifier=""` config in `core/app/cli/build.gradle.kts` ensures no `-all` or other suffix).

If the filename has a classifier or different prefix: stop and reconcile.

- [ ] **Step 4: No commit yet**

Nothing to commit — these are read-only checks.

---

## Task 1: Write a smoke-test stand-in for the workflow's signing step (locally)

The release workflow's most fragile step is the GPG signing block. We exercise the exact bash sequence locally first, against a throw-away test key, to make sure the sequence works before committing it to a YAML file that only runs on a tag push (which would be expensive to iterate on).

**Files:** none changed (scratch directory only)

- [ ] **Step 1: Create the scratch directory and generate a throw-away test key**

Run:

```bash
mkdir -p /tmp/unidrive-release-smoke
cd /tmp/unidrive-release-smoke
export GNUPGHOME="$(pwd)/gnupg-test"
mkdir -p "$GNUPGHOME"
chmod 700 "$GNUPGHOME"
cat > key-spec <<'EOF'
%no-protection
Key-Type: EdDSA
Key-Curve: ed25519
Key-Usage: sign
Name-Real: Release Smoke Test
Name-Email: smoke-test@invalid.local
Expire-Date: 1d
%commit
EOF
gpg --batch --gen-key key-spec
gpg --list-secret-keys --keyid-format LONG
```

Expected: a key listing showing one secret key for `smoke-test@invalid.local`. Note its fingerprint (40-hex-char string under `sec`).

- [ ] **Step 2: Export the test private key as armored**

Run:

```bash
FPR=$(gpg --list-secret-keys --with-colons | awk -F: '/^fpr/{print $10; exit}')
echo "Fingerprint: $FPR"
gpg --batch --yes --armor --export-secret-keys "$FPR" > test-private-key.asc
wc -l test-private-key.asc
```

Expected: `test-private-key.asc` exists, several hundred lines of armored ASCII.

- [ ] **Step 3: Simulate the workflow's import + sign sequence**

This mirrors exactly what the workflow's bash block will do, using the test key. Run:

```bash
# Simulate the JAR coming out of Gradle.
echo "pretend this is a JAR" > unidrive-0.0.1.jar

# 1. Compute checksum.
sha256sum unidrive-0.0.1.jar > unidrive-0.0.1.jar.sha256
cat unidrive-0.0.1.jar.sha256

# 2. Detach-sign the JAR.
gpg --batch --yes --pinentry-mode loopback \
    --local-user "$FPR" \
    --armor --detach-sign --output unidrive-0.0.1.jar.asc \
    unidrive-0.0.1.jar

# 3. Detach-sign the checksum file (so a verifier can trust the checksum
#    without trusting the JAR).
gpg --batch --yes --pinentry-mode loopback \
    --local-user "$FPR" \
    --armor --detach-sign --output unidrive-0.0.1.jar.sha256.asc \
    unidrive-0.0.1.jar.sha256

ls -1
```

Expected: four files exist — `unidrive-0.0.1.jar`, `unidrive-0.0.1.jar.sha256`, `unidrive-0.0.1.jar.asc`, `unidrive-0.0.1.jar.sha256.asc`.

- [ ] **Step 4: Verify the signatures round-trip**

Run:

```bash
gpg --verify unidrive-0.0.1.jar.asc unidrive-0.0.1.jar
gpg --verify unidrive-0.0.1.jar.sha256.asc unidrive-0.0.1.jar.sha256
```

Expected: each command prints `Good signature from "Release Smoke Test <smoke-test@invalid.local>"` (and a warning about untrusted key, which is fine for the test).

- [ ] **Step 5: Tear down the scratch dir**

Run:

```bash
cd /home/gernot/dev/git/unidrive
unset GNUPGHOME
rm -rf /tmp/unidrive-release-smoke
```

- [ ] **Step 6: No commit**

This task produced no repo changes; it validated the bash sequence that goes into the YAML in Task 2.

---

## Task 2: Add the release workflow file

**Files:**
- Create: `.github/workflows/release.yml`

- [ ] **Step 1: Write the workflow file**

Create `.github/workflows/release.yml` with this exact content:

```yaml
name: release

# Fires on annotated or lightweight tag push matching v* (e.g. v0.0.1).
# Per the spec, only the unidrive-dist repo enforces the strict tag regex
# `^v(\d+\.\d+\.\d+)(-pkg\d+)?$`. On this repo we accept anything starting
# with `v` so a future pre-release scheme can be opted into without
# editing CI.
on:
  push:
    tags:
      - 'v*'

permissions:
  contents: write  # required to create the GH Release

concurrency:
  group: release-${{ github.ref }}
  cancel-in-progress: false  # never cancel an in-flight release

jobs:
  release:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v6
        with:
          # Full history so the existing BuildInfo.kt generator (which calls
          # `git rev-parse --short HEAD` and `git diff HEAD --numstat`) finds
          # something to work against.
          fetch-depth: 0

      - name: Set up JDK 21
        uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: '21'

      - name: Build shadowJar
        working-directory: core
        run: ./gradlew :app:cli:shadowJar --no-daemon -q

      - name: Verify expected JAR filename
        run: |
          set -euo pipefail
          jar="core/app/cli/build/libs/unidrive-${GITHUB_REF_NAME#v}.jar"
          test -f "$jar" || {
            echo "ERROR: expected $jar to exist after shadowJar build" >&2
            ls -la core/app/cli/build/libs/ >&2
            exit 1
          }
          echo "JAR present: $jar"

      - name: Stage release artefacts
        run: |
          set -euo pipefail
          mkdir -p release-staging
          ver="${GITHUB_REF_NAME#v}"
          cp "core/app/cli/build/libs/unidrive-${ver}.jar" release-staging/
          cd release-staging
          sha256sum "unidrive-${ver}.jar" > "unidrive-${ver}.jar.sha256"
          cat "unidrive-${ver}.jar.sha256"

      - name: Import signing key
        env:
          GPG_PRIVATE_KEY: ${{ secrets.GPG_PRIVATE_KEY }}
        run: |
          set -euo pipefail
          if [ -z "${GPG_PRIVATE_KEY:-}" ]; then
            echo "ERROR: GPG_PRIVATE_KEY secret is not set on this repo." >&2
            echo "  This key is provisioned by Plan P3 (krost-infra signing setup)." >&2
            echo "  Add it under Settings → Secrets and variables → Actions before retrying." >&2
            exit 1
          fi
          echo "$GPG_PRIVATE_KEY" | gpg --batch --import
          gpg --list-secret-keys --keyid-format LONG

      - name: Sign artefacts
        env:
          GPG_KEY_FINGERPRINT: ${{ secrets.GPG_KEY_FINGERPRINT }}
        run: |
          set -euo pipefail
          if [ -z "${GPG_KEY_FINGERPRINT:-}" ]; then
            echo "ERROR: GPG_KEY_FINGERPRINT secret is not set on this repo." >&2
            exit 1
          fi
          ver="${GITHUB_REF_NAME#v}"
          cd release-staging
          gpg --batch --yes --pinentry-mode loopback \
              --local-user "$GPG_KEY_FINGERPRINT" \
              --armor --detach-sign \
              --output "unidrive-${ver}.jar.asc" \
              "unidrive-${ver}.jar"
          gpg --batch --yes --pinentry-mode loopback \
              --local-user "$GPG_KEY_FINGERPRINT" \
              --armor --detach-sign \
              --output "unidrive-${ver}.jar.sha256.asc" \
              "unidrive-${ver}.jar.sha256"
          ls -1

      - name: Verify signatures
        run: |
          set -euo pipefail
          ver="${GITHUB_REF_NAME#v}"
          cd release-staging
          gpg --verify "unidrive-${ver}.jar.asc" "unidrive-${ver}.jar"
          gpg --verify "unidrive-${ver}.jar.sha256.asc" "unidrive-${ver}.jar.sha256"

      - name: Create GitHub Release
        # Pin to a SHA — `v2` is a moving tag. Update the SHA when bumping
        # the action. As of writing, v2.0.8 = 01570a1f39cb168c169c802c3bceb9e93fb10974
        uses: softprops/action-gh-release@01570a1f39cb168c169c802c3bceb9e93fb10974
        with:
          tag_name: ${{ github.ref_name }}
          name: ${{ github.ref_name }}
          # Release notes are intentionally minimal here. The full RELEASES.md
          # narrative is published by unidrive-dist's release workflow (Plan P4)
          # which pulls this JAR + the Rust binary tarballs.
          body: |
            UniDrive CLI ${{ github.ref_name }}

            JVM artefact for the coordinated UniDrive release. Distro packages
            and the canonical end-user tarball are produced by unidrive-dist
            and published at https://unidrive.krost.org/install/.

            Verify with:
              gpg --verify unidrive-*.jar.asc unidrive-*.jar
              sha256sum -c unidrive-*.jar.sha256
          files: |
            release-staging/unidrive-*.jar
            release-staging/unidrive-*.jar.sha256
            release-staging/unidrive-*.jar.asc
            release-staging/unidrive-*.jar.sha256.asc
          fail_on_unmatched_files: true
          draft: false
          prerelease: false
```

- [ ] **Step 2: Verify the YAML parses**

Run:

```bash
python3 -c "import yaml; yaml.safe_load(open('.github/workflows/release.yml'))" && echo "YAML OK"
```

Expected: `YAML OK`. If a parse error, fix and re-run.

- [ ] **Step 3: Verify the file is shellcheck-clean for its bash steps**

The workflow contains multiple `run: |` bash blocks. Extract and shellcheck each. Run:

```bash
# Quick visual inspection — there's no clean tool to extract `run: |` blocks
# from a workflow YAML. We rely on the AGENTS.md shell rules being followed
# in the YAML directly: `set -euo pipefail`, double-quoted variables, no eval.
# Verify the four `run: |` blocks each start with `set -euo pipefail`:
grep -B1 'set -euo pipefail' .github/workflows/release.yml | grep -c 'set -euo pipefail'
```

Expected: `5` or more (each multi-line `run` block should have one; the simple one-liner `./gradlew ...` step doesn't need it).

If fewer: add `set -euo pipefail` to any multi-line bash block that lacks it.

- [ ] **Step 4: Commit**

Run:

```bash
git add .github/workflows/release.yml
git commit -m "$(cat <<'EOF'
ci(release): tag-triggered GH Actions workflow to publish signed JAR

Fires on v* tag push. Builds the CLI shadowJar, computes SHA256, signs
JAR + checksum with the unidrive-releases GPG key (from secrets), and
attaches all four files to a GitHub Release.

Per docs/dev/specs/unidrive-distribution-design.md §3.3 step 1 and §10
AC #1.
EOF
)"
```

Expected: one new file, one commit.

---

## Task 3: Add documentation pointer to dist/README.md

The spec says (§4.1) that the canonical installer migrates to `unidrive-dist` and `unidrive/dist/` becomes a thin pointer. **That migration happens in Plan P4**, not here. This plan only documents the *future* GH Release as a fact in this repo's README so a contributor browsing now isn't surprised by tag-triggered CI.

**Files:**
- Modify: `dist/README.md`

- [ ] **Step 1: Read the current `dist/README.md`**

Run:

```bash
cat dist/README.md
```

Expected output: short README describing `install.sh` and `uninstall.sh`.

- [ ] **Step 2: Append a "Releases" section**

Open `dist/README.md` and append (do not replace the existing content; append at the end):

```markdown

## Releases

On any `v*` tag push, `.github/workflows/release.yml` builds the CLI fat
JAR (`unidrive-<version>.jar`) and publishes it as a GitHub Release with
detached GPG signatures and SHA256 checksums.

End users don't consume this artefact directly — it's the upstream input
to `unidrive-dist`, which packages it as `.deb`/`.rpm`/AUR/tarball and
publishes via the channels at https://unidrive.krost.org/install/.

For the local-development install path used today (build + drop under
`~/.local/`), see `install.sh` and `core/app/cli/build.gradle.kts`'s
`deploy` task.
```

- [ ] **Step 3: Verify the README still renders cleanly**

Run:

```bash
wc -l dist/README.md
```

Expected: line count increased by ~13 (the appended section).

- [ ] **Step 4: Commit**

Run:

```bash
git add dist/README.md
git commit -m "docs(dist): note the new tag-triggered release workflow"
```

---

## Task 4: Local dry-run of the workflow's bash logic (no real tag push)

We can't actually fire the workflow without pushing a tag. But we can run its bash blocks locally with substituted variables to confirm they work end-to-end against this repo's actual `gradlew` build. This is the "smoke test before tag" gate.

**Files:** none changed (scratch directory only; cleaned up at end)

- [ ] **Step 1: Build the shadowJar fresh**

Run:

```bash
cd /home/gernot/dev/git/unidrive/core
./gradlew :app:cli:shadowJar --no-daemon -q
ls -la app/cli/build/libs/
```

Expected: `unidrive-0.0.1.jar` present, several tens of MB.

- [ ] **Step 2: Re-create the throw-away test key from Task 1**

Run:

```bash
cd /home/gernot/dev/git/unidrive
mkdir -p /tmp/unidrive-release-dryrun
cd /tmp/unidrive-release-dryrun
export GNUPGHOME="$(pwd)/gnupg-test"
mkdir -p "$GNUPGHOME"
chmod 700 "$GNUPGHOME"
cat > key-spec <<'EOF'
%no-protection
Key-Type: EdDSA
Key-Curve: ed25519
Key-Usage: sign
Name-Real: Release Dry Run
Name-Email: dryrun@invalid.local
Expire-Date: 1d
%commit
EOF
gpg --batch --gen-key key-spec
FPR=$(gpg --list-secret-keys --with-colons | awk -F: '/^fpr/{print $10; exit}')
echo "Fingerprint: $FPR"
```

Expected: a 40-char hex fingerprint is printed.

- [ ] **Step 3: Run the workflow's bash logic against the real JAR**

Run (still in `/tmp/unidrive-release-dryrun`, env vars from previous step still set):

```bash
export GITHUB_REF_NAME=v0.0.1
export GPG_KEY_FINGERPRINT="$FPR"

# Replay the "Verify expected JAR filename" + "Stage release artefacts"
# steps:
jar="/home/gernot/dev/git/unidrive/core/app/cli/build/libs/unidrive-${GITHUB_REF_NAME#v}.jar"
test -f "$jar" && echo "JAR present: $jar"

mkdir -p release-staging
ver="${GITHUB_REF_NAME#v}"
cp "$jar" release-staging/
cd release-staging
sha256sum "unidrive-${ver}.jar" > "unidrive-${ver}.jar.sha256"
cat "unidrive-${ver}.jar.sha256"

# Replay the "Sign artefacts" step:
gpg --batch --yes --pinentry-mode loopback \
    --local-user "$GPG_KEY_FINGERPRINT" \
    --armor --detach-sign \
    --output "unidrive-${ver}.jar.asc" \
    "unidrive-${ver}.jar"
gpg --batch --yes --pinentry-mode loopback \
    --local-user "$GPG_KEY_FINGERPRINT" \
    --armor --detach-sign \
    --output "unidrive-${ver}.jar.sha256.asc" \
    "unidrive-${ver}.jar.sha256"

# Replay the "Verify signatures" step:
gpg --verify "unidrive-${ver}.jar.asc" "unidrive-${ver}.jar"
gpg --verify "unidrive-${ver}.jar.sha256.asc" "unidrive-${ver}.jar.sha256"

# Inventory what would be uploaded:
ls -1
```

Expected: four files (`unidrive-0.0.1.jar`, `unidrive-0.0.1.jar.sha256`, `unidrive-0.0.1.jar.asc`, `unidrive-0.0.1.jar.sha256.asc`), both `gpg --verify` invocations report `Good signature`.

If any step fails: do not proceed to Task 5. Reconcile with the actual workflow YAML (Task 2, Step 1) — the YAML and the bash logic must agree exactly.

- [ ] **Step 4: Tear down**

Run:

```bash
cd /home/gernot/dev/git/unidrive
unset GNUPGHOME GITHUB_REF_NAME GPG_KEY_FINGERPRINT
rm -rf /tmp/unidrive-release-dryrun
```

- [ ] **Step 5: No commit**

Dry-run produced no repo changes.

---

## Task 5: Verify the workflow file syntactically against GitHub's schema

GitHub Actions has its own workflow schema. A `python3 -c "yaml.safe_load(...)"` parse only catches YAML-level errors, not action-input-name typos. Use `actionlint` (a static checker for GH Actions workflows) if available.

**Files:** none changed

- [ ] **Step 1: Install `actionlint` if not present**

Run:

```bash
if ! command -v actionlint >/dev/null 2>&1; then
    # Install via Go if Go is available, otherwise download a binary.
    if command -v go >/dev/null 2>&1; then
        go install github.com/rhysd/actionlint/cmd/actionlint@latest
        # Add ~/go/bin to PATH if needed.
        export PATH="$HOME/go/bin:$PATH"
    else
        # Use the upstream installer (downloads a single static binary into ./)
        bash <(curl -sSL https://raw.githubusercontent.com/rhysd/actionlint/main/scripts/download-actionlint.bash)
        export PATH="$(pwd):$PATH"
    fi
fi
actionlint --version
```

Expected: a version string is printed.

- [ ] **Step 2: Lint the new workflow**

Run:

```bash
cd /home/gernot/dev/git/unidrive
actionlint .github/workflows/release.yml
```

Expected: no output (exit 0). actionlint is verbose about errors when it finds them; silence = success.

If errors: fix in `release.yml` (re-edit per Task 2 Step 1), then re-run actionlint and re-commit.

- [ ] **Step 3: If actionlint flagged shellcheck issues inside the `run:` blocks, fix them**

actionlint runs shellcheck on each `run: |` block by default. Common findings to expect (and how to fix):

- `SC2086: Double quote to prevent globbing` — quote `"${GITHUB_REF_NAME}"`, `"${ver}"`, etc.
- `SC2155: Declare and assign separately` — split `local x="$(cmd)"` into `local x; x="$(cmd)"` (none in this plan but watch for it).

If any fix is needed, edit and commit:

```bash
git add .github/workflows/release.yml
git commit -m "ci(release): address actionlint findings"
```

If no fixes needed: no commit.

- [ ] **Step 4: No commit if step 3 was a no-op**

---

## Task 6: Final review of the branch + push for PR

**Files:** none changed beyond Tasks 2 and 3

- [ ] **Step 1: Review the branch's diff against main**

Run:

```bash
cd /home/gernot/dev/git/unidrive
git log --oneline origin/main..HEAD
git diff --stat origin/main..HEAD
```

Expected: two (or three, if actionlint produced a fix) commits; the diff stat shows one new file (`.github/workflows/release.yml`) and one modified file (`dist/README.md`).

- [ ] **Step 2: Run the existing `build.yml` gate locally to make sure unrelated regressions weren't introduced**

The unidrive AGENTS.md says `./gradlew check` is the gate.

Run:

```bash
cd core
./gradlew check --no-daemon > /tmp/unidrive-check.log 2>&1 \
    || grep -E "FAILED|ERROR|Exception" -C 5 /tmp/unidrive-check.log
```

Expected: exit 0 (no grep output and no FAILED log lines visible).

If failures: this plan changes only YAML and a Markdown file — failures here are pre-existing and unrelated. Document them and stop; don't try to fix them in this plan.

- [ ] **Step 3: Push the branch**

Run:

```bash
git push -u origin release/jvm-workflow
```

Expected: branch pushed to origin.

- [ ] **Step 4: Open a PR**

Run:

```bash
gh pr create --title "ci(release): tag-triggered JVM release workflow" \
  --body "$(cat <<'EOF'
## Summary
- Adds `.github/workflows/release.yml` that fires on `v*` tag push.
- Builds the CLI fat JAR, computes SHA256, GPG-signs both files, attaches all four artefacts to a GH Release.
- Documents the workflow in `dist/README.md`.

## Spec reference
`docs/dev/specs/unidrive-distribution-design.md` §3.3 step 1, §4.1, §5, §10 AC #1.

## Plan reference
`docs/dev/plans/release-workflow-jvm.md` (this plan, will land on the spec/distribution branch).

## Test plan
- [x] Local dry-run of the workflow's bash blocks against a throw-away test key (Task 1, Task 4).
- [x] `actionlint` clean (Task 5).
- [x] `./gradlew check` still green (Task 6 step 2).
- [ ] First real tag push happens in Plan P5 (the first-release cutover); this PR ships the *capability*, not the first release.

## Dependencies
- Consumer: Plan P4 (unidrive-dist) reads from the GH Releases this workflow publishes.
- Provider: Plan P3 (krost-infra) provisions the `GPG_PRIVATE_KEY` and `GPG_KEY_FINGERPRINT` GH secrets. Without those, the workflow's "Import signing key" step fails fast with a clear error message — this is intentional, not a bug.
EOF
)"
```

Expected: PR opened, URL printed.

- [ ] **Step 5: No additional commit**

The PR is open. Hand off to review.

---

## Self-Review Notes

**Spec coverage check** — every requirement from §3.3 step 1, §4.1 (JAR + sig contents), §5 (GPG signing identity), and §10 AC #1 maps to a task above:

| Spec requirement | Implementing task |
|---|---|
| §3.3 step 1: `git tag vX.Y.Z → push`, GH Action builds shadowJar, signs, attaches | Task 2 (workflow file), Task 4 (local dry-run) |
| §4.1: artefacts are `unidrive-X.Y.Z.jar`, with SHA256 + signature | Task 2 "Stage release artefacts" + "Sign artefacts" steps |
| §5: signing key is the project `unidrive-releases` key, not a personal key | Task 2 consumes `GPG_PRIVATE_KEY` + `GPG_KEY_FINGERPRINT` secrets which Plan P3 provisions from the project key |
| §10 AC #1: GH Release contains `unidrive-0.0.1.jar` + `.sha256` + `.asc` | Task 2 "Create GitHub Release" step uploads exactly that set |

**Out-of-scope items** are documented at the top of this plan and in the PR body.

**Placeholder scan:** no TODO/TBD/FIXME in the plan body. The action-SHA pin in Task 2 (`01570a1f39cb168c169c802c3bceb9e93fb10974`) is a concrete value at writing time and includes a note instructing the implementer to verify it's still v2.0.8-ish at execution time — that's hygiene, not a placeholder.

**Type/name consistency:** the JAR filename is consistently `unidrive-<version>.jar`. The four output files are consistently named across Task 1, Task 2, Task 4. The two GH secrets (`GPG_PRIVATE_KEY`, `GPG_KEY_FINGERPRINT`) are referenced in only one task (Task 2) and not redefined elsewhere.
