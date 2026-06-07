# Plan P5: First-Release Cutover — `v0.0.1` Across the Triplet

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Cross-repo note:** This is the final plan and touches all three working repos plus krost-infra. Plan file lives in `unidrive/docs/dev/plans/` per the convention.

**Goal:** Cut the first real `v0.0.1` UniDrive release end-to-end. Reconcile the spec §3.5 vs `BuildInfo.kt` tension (review-3 Issue 1). Write the first `RELEASES.md` entry. Publish to `apt.unidrive.krost.org`, `dnf.unidrive.krost.org`, AUR, and the unidrive-dist GH Release. Add the install-instructions landing page at `unidrive.krost.org/install/`. Verify all eight acceptance criteria from spec §10.

**Architecture:** This plan is primarily an operator runbook. Code changes are minimal:

1. One JVM-side change in `unidrive` to reconcile `--version` output with spec §3.5.
2. One new landing page in `krost-infra` (`sites/unidrive/install/index.html`).
3. The first real `RELEASES.md` entry in `unidrive-dist`.

Everything else is sequencing: tag in the right order, watch the workflows, verify the outputs match the eight acceptance criteria, run the §8.4 end-to-end smoke on a fresh VM.

**Tech Stack:** `git`, `gh`, `gradle`, the existing release tooling from P1-P4b. One small HTML page. No new dependencies.

**Spec reference:** §3.3 (tagging protocol), §3.5 (`--version` output), §10 (all eight ACs), §8.4 (E2E manual smoke).

**Pre-flight assumptions (verified in Task 0):**

- Plans P1, P2, P3, P4a, P4b are all merged.
- The signing key exists in `pkg-signer`; all GH secrets exist on all three repos.
- DNS for `apt.unidrive.krost.org` and `dnf.unidrive.krost.org` resolves.
- The `unidrive-bot` Arch account exists and `AUR_BOT_KEY` is set on unidrive-dist.
- No prior `v0.0.1` tag exists on any of the three repos.

---

## File Structure

| Path | Repo | Purpose | Change type |
|---|---|---|---|
| `core/app/cli/build.gradle.kts` | unidrive | Reconcile `BuildInfo.versionString()` with spec §3.5 (suppress commit suffix on clean tag builds) | Modify |
| `RELEASES.md` | unidrive-dist | First release entry | Modify |
| `sites/unidrive/install/index.html` | krost-infra | Install instructions landing page | Create |
| `docs/release-process.md` | unidrive-dist | Fill in operator runbook (P4a skeletoned it) | Modify |
| `maintenance/html/apt.unidrive.krost.org/`, `.../dnf...` | krost-infra | Already created in P3 — no change | — |

No new repos. No new infrastructure containers. No new GH workflows.

---

## Task 0: Pre-flight verification

**Files:** none changed

- [ ] **Step 1: Verify all four prior plans are merged on main**

```bash
for repo in /home/gernot/dev/git/unidrive \
            /home/gernot/dev/git/unidrive-mount-linux \
            /home/gernot/dev/git/krost-infra \
            /home/gernot/dev/git/unidrive-dist; do
    if [[ -d $repo ]]; then
        echo "=== $repo ==="
        ( cd "$repo" && git checkout main && git pull --ff-only && \
          git log --oneline -3 )
    fi
done
```

Expected: each repo on main, no uncommitted changes. The `unidrive-dist` repo exists (P4a created it; P4b's PR is merged).

If any repo lags: stop and complete its plan first.

- [ ] **Step 2: Verify GH secrets**

In the browser, for each of `gkrost/unidrive`, `gkrost/unidrive-mount-linux`, `gkrost/unidrive-dist`:

- `GPG_PRIVATE_KEY` ✓
- `GPG_KEY_FINGERPRINT` ✓

For `gkrost/unidrive-dist` only:

- `DIST_DEPLOY_KEY` ✓
- `SIGNER_DEPLOY_KEY` ✓
- `AUR_BOT_KEY` ✓

If any missing, walk back to the relevant earlier-plan task that provisioned it.

- [ ] **Step 3: Verify VPS state**

```bash
ssh -p 22222 gernot@87.106.246.31 'cd ~/docker && docker compose ps pkg-server pkg-signer'
```

Expected: both `(healthy)`.

```bash
ssh -p 22222 gernot@87.106.246.31 \
    'docker exec pkg-signer bash -c "GNUPGHOME=/var/lib/signer/gnupg gpg --list-secret-keys --batch | head -10"'
```

Expected: secret-key listing for `releases@unidrive.krost.org`.

- [ ] **Step 4: Verify DNS**

```bash
dig +short apt.unidrive.krost.org
dig +short dnf.unidrive.krost.org
```

Expected: both resolve to `87.106.246.31`.

- [ ] **Step 5: Verify no existing v0.0.1 tags**

```bash
for repo in gkrost/unidrive gkrost/unidrive-mount-linux gkrost/unidrive-dist; do
    echo "=== $repo ==="
    gh release list --repo "$repo" --limit 5
done
```

Expected: no `v0.0.1` listed anywhere. If one already exists (e.g. from a prior aborted release attempt), reconcile with the maintainer before proceeding — pre-existing tags will collide.

- [ ] **Step 6: No commit**

---

## Task 1: Reconcile `BuildInfo` with spec §3.5 (review-3 Issue 1)

The spec says `unidrive --version` prints exactly the user-visible canonical version (e.g. `0.0.1`). The current `BuildInfo.kt` generator embeds the commit hash on every clean build: `0.0.1 (abc1234)`. P1's deferral note flagged this for resolution here.

**Decision: option (a) from P1's deferred-tension block** — suppress the commit suffix when building from a clean tagged commit. Rationale: the spec wording is intentional ("no commit SHA, no build date"), the current behaviour was a holdover from when the project hadn't decided how releases would look, and the suppression is a small mechanical change that preserves the existing dirty/dev experience for non-tag builds.

**Files:**
- Modify: `core/app/cli/build.gradle.kts` (small change to the `generateBuildInfo` task)

- [ ] **Step 1: Working branch on unidrive**

```bash
cd /home/gernot/dev/git/unidrive
git checkout main && git pull
git checkout -b fix/version-output-spec-3-5
```

- [ ] **Step 2: Read the current `generateBuildInfo` task**

```bash
sed -n '46,135p' core/app/cli/build.gradle.kts
```

Note the structure: `versionString` is set to `"$version ($commit-dirty)"` (when dirty) or `"$version ($commit)"` (when clean). Spec §3.5 wants exactly `$version` when building from a tagged release; the dirty case is fine to keep as-is for non-release builds.

- [ ] **Step 3: Capture "is this a tagged release build" at configuration time**

The unidrive AGENTS.md "BuildInfo and uncommitted-build warnings" rationale (in the file we just read) is load-bearing. The change should preserve:

- For tag builds (running on a clean tree at a commit that's pointed at by a tag): `versionString = "$version"`
- For dev builds (no tag, or dirty tree): `versionString` keeps its existing `($commit)` / `($commit-dirty)` form.

Edit `core/app/cli/build.gradle.kts`. After the existing `val gitUntracked = providers.exec { ... }` block (around line 87), add a third capture that detects whether HEAD is exactly at a tag:

```kotlin
        // Per docs/dev/specs/unidrive-distribution-design.md §3.5,
        // tagged-release builds print the bare semver (e.g. "0.0.1")
        // with no commit suffix. Non-tag (dev) builds keep the existing
        // "(commit)" / "(commit-dirty)" enrichment for bug-report
        // tractability.
        val gitTagAtHead =
            providers
                .exec {
                    commandLine("git", "tag", "--points-at", "HEAD")
                }.standardOutput.asText
                .map { it.trim() }
```

Then change the `versionString` computation inside `doLast { ... }` from:

```kotlin
            val versionString =
                if (dirty) "$version ($commit-dirty)" else "$version ($commit)"
```

to:

```kotlin
            val taggedRelease =
                try {
                    !dirty && gitTagAtHead.get().lines().any { it.matches(Regex("^v?$version(-pkg\\d+)?$")) }
                } catch (_: Exception) {
                    false
                }
            val versionString =
                when {
                    taggedRelease -> version
                    dirty         -> "$version ($commit-dirty)"
                    else          -> "$version ($commit)"
                }
```

The regex matches `0.0.1`, `v0.0.1`, `0.0.1-pkg2`, `v0.0.1-pkg2` against the configured project version — both bare and `v`-prefixed tags count, and packaging-respin suffixes are accepted (spec §3.4).

- [ ] **Step 4: Verify the change compiles**

```bash
cd core
./gradlew :app:cli:shadowJar --no-daemon -q
```

Expected: clean build.

- [ ] **Step 5: Verify behaviour on a clean non-tag build**

```bash
java --enable-native-access=ALL-UNNAMED -jar app/cli/build/libs/unidrive-*.jar --version
```

Expected (on a clean working tree, on main without a tag): `0.0.1 (abc1234)` — the existing dev-build form. The new logic only activates when a tag points at HEAD.

- [ ] **Step 6: Verify behaviour on a simulated tag build**

```bash
git tag v0.0.1-test-buildinfo
./gradlew :app:cli:shadowJar --no-daemon -q
java --enable-native-access=ALL-UNNAMED -jar app/cli/build/libs/unidrive-*.jar --version
git tag -d v0.0.1-test-buildinfo
```

Expected: `0.0.1` only. If the suffix still shows, the regex match in Step 3 is wrong — check that `gitTagAtHead.get()` returns the tag.

- [ ] **Step 7: Run the existing check gate**

```bash
./gradlew check --no-daemon > /tmp/check.log 2>&1 \
    || grep -E "FAILED|ERROR|Exception" -C 5 /tmp/check.log
```

Expected: green.

- [ ] **Step 8: Commit + push + PR**

```bash
cd /home/gernot/dev/git/unidrive
git add core/app/cli/build.gradle.kts
git commit -m "$(cat <<'EOF'
fix(cli): print bare semver on tagged-release builds (spec §3.5)

The distribution spec requires `unidrive --version` to print the
user-visible canonical version (e.g. "0.0.1") on releases, with no
commit suffix. The previous BuildInfo generator always embedded the
commit hash on clean builds.

Adds a third gitTagAtHead capture; when HEAD is pointed at by a tag
matching ^v?<projectVersion>(-pkg\d+)?$ on a clean tree, the
versionString becomes the bare project version. Dev builds keep the
existing (commit) / (commit-dirty) suffix for bug-report tractability.

Per docs/dev/specs/unidrive-distribution-design.md §3.5; resolves
review-3 Issue 1 deferred from
docs/dev/plans/release-workflow-jvm.md.
EOF
)"

git push -u origin fix/version-output-spec-3-5

gh pr create --title "fix(cli): print bare semver on tagged-release builds (spec §3.5)" \
  --body "Resolves review-3 Issue 1 deferred from the JVM release-workflow plan. See commit message for details. Smoke: built locally; bare semver on tagged HEAD, (commit) suffix on untagged HEAD."
```

- [ ] **Step 9: Merge the PR `[MAINTAINER]`**

Review your own PR, merge it via the GitHub UI. Make sure `main` is fast-forward-updated locally:

```bash
cd /home/gernot/dev/git/unidrive
git checkout main && git pull
```

---

## Task 2: Write first RELEASES.md entry

**Files:**
- Modify: `unidrive-dist/RELEASES.md`

- [ ] **Step 1: Working branch on unidrive-dist**

```bash
cd /home/gernot/dev/git/unidrive-dist
git checkout main && git pull
git checkout -b release/v0.0.1
```

- [ ] **Step 2: Gather closed-work bullets from sibling CLOSED.md files**

```bash
echo "=== unidrive CLOSED.md (recent entries) ==="
head -50 /home/gernot/dev/git/unidrive/CLOSED.md

echo "=== unidrive-mount-linux CLOSED.md (recent entries) ==="
head -50 /home/gernot/dev/git/unidrive-mount-linux/CLOSED.md

echo "=== unidrive-dist CLOSED.md ==="
head -50 CLOSED.md
```

Note 3–6 standout items per sibling. These are the bullets for the first release.

- [ ] **Step 3: Edit RELEASES.md**

Insert the new section between the `<!-- Releases below this line. Newest first. -->` marker and any existing content. Curate the bullets — pick the most user-visible items, not internal refactors. Example shape (the actual bullets depend on what's in each CLOSED.md):

```markdown
## 0.0.1 — first MVP release

This is the first publishable release of UniDrive. The CLI syncs
between Internxt and OneDrive; the FUSE co-daemon mounts the
synchronised tree with sparse hydration on Linux kernel ≥ 6.9.

### unidrive (JVM CLI)
- <pick the 3–6 most user-visible bullets from unidrive/CLOSED.md>

### unidrive-mount-linux (FUSE co-daemon)
- <pick the 3–6 most user-visible bullets from unidrive-mount-linux/CLOSED.md>

### Packaging
- First .deb packages for Ubuntu 24.04 LTS + Debian 13 (amd64, arm64)
- First .rpm packages for Fedora 40 + RHEL 10 (x86_64, aarch64)
- First AUR `unidrive` package
- First signed end-user tarball bundles
- apt.unidrive.krost.org and dnf.unidrive.krost.org go live
```

- [ ] **Step 4: Sanity-check via render-changelog**

```bash
./release/render-changelog.sh deb 0.0.1 1
echo --- RPM ---
./release/render-changelog.sh rpm 0.0.1 1
echo --- GH ---
./release/render-changelog.sh gh 0.0.1
```

Expected: each format renders the new section cleanly. If render-changelog errors with "no section for version 0.0.1", the section header is wrong — check for typos (the regex expects `^## 0.0.1` followed by space or end of line).

- [ ] **Step 5: Commit RELEASES.md (+ matching CLOSED.md move)**

```bash
git add RELEASES.md
# Move "first release" item from BACKLOG.md to CLOSED.md if you've
# tracked it as a work item.
git commit -m "$(cat <<'EOF'
docs: RELEASES.md — 0.0.1 first MVP entry

Curated bullets sourced from sibling CLOSED.md files plus this
repo's CLOSED.md. Verified via render-changelog.sh in deb / rpm / gh
forms.

Per spec §4.7.
EOF
)"
```

- [ ] **Step 6: Push + don't merge yet**

Hold the branch — the release.sh CI will be triggered by the tag, not by main. We tag `release/v0.0.1` itself after the cutover (Task 5 Step 4).

```bash
git push -u origin release/v0.0.1
```

---

## Task 3: Write `unidrive.krost.org/install/` landing page

**Files:**
- Create: `krost-infra/sites/unidrive/install/index.html`
- Already-present: `krost-infra/sites/unidrive/install/unidrive-releases.gpg` (deployed in Plan P3 Task 6)

- [ ] **Step 1: Working branch on krost-infra**

```bash
cd /home/gernot/dev/git/krost-infra
git checkout main && git pull
git checkout -b feat/install-landing-page
```

- [ ] **Step 2: Read the existing site styling**

```bash
head -50 sites/unidrive/index.html
```

Note the CSS variables and class conventions — the new page reuses them.

- [ ] **Step 3: Create the install page**

Create `sites/unidrive/install/index.html`. The content below uses inline styles for simplicity — the existing site's CSS is on the root index. Adapt to a shared stylesheet if you prefer, but this works as-is and the page is short.

```bash
cat > sites/unidrive/install/index.html <<'HTML'
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>UniDrive — Install instructions</title>
<style>
body { font-family: -apple-system, BlinkMacSystemFont, system-ui, sans-serif;
       background:#0a0a0f; color:#e2e8f0; max-width:780px; margin:2em auto;
       padding:0 1em; line-height:1.6; }
h1, h2, h3 { color:#63b3ed; }
a { color:#a78bfa; }
code, pre { font-family: 'JetBrains Mono', 'Fira Code', monospace;
            background:#1e293b; color:#e2e8f0; }
pre { padding:1em; overflow-x:auto; border-radius:6px; }
code { padding:0.1em 0.3em; border-radius:3px; }
.note { background:rgba(99,179,237,0.1); border-left:3px solid #63b3ed;
        padding:0.6em 1em; margin:1em 0; }
hr { border:0; border-top:1px solid #1e293b; margin:2em 0; }
</style>
</head>
<body>
<h1>Install UniDrive</h1>
<p>UniDrive is a zero-telemetry multi-cloud sync daemon for Linux.
Pick the install method that matches your distribution.</p>

<div class="note">
<strong>Verify the signing key.</strong> All UniDrive packages are
signed by the <code>unidrive-releases &lt;releases@unidrive.krost.org&gt;</code>
GPG key. Download the public key:
<pre><a href="/install/unidrive-releases.gpg">https://unidrive.krost.org/install/unidrive-releases.gpg</a></pre>
Verify the fingerprint matches: <code>FINGERPRINT_PLACEHOLDER</code><br>
(Replace <code>FINGERPRINT_PLACEHOLDER</code> with the real
fingerprint before publishing this page; see Task 3 Step 4.)
</div>

<hr>

<h2>Debian / Ubuntu / Kubuntu (apt)</h2>
<p>Supported: Ubuntu 24.04 LTS, Ubuntu 26.04 (once GA), Debian 12, Debian 13.
Architectures: amd64, arm64.</p>
<pre>curl -fsSL https://unidrive.krost.org/install/unidrive-releases.gpg \
  | sudo tee /etc/apt/keyrings/unidrive.gpg &gt; /dev/null

echo "deb [signed-by=/etc/apt/keyrings/unidrive.gpg] \
  https://apt.unidrive.krost.org stable main" \
  | sudo tee /etc/apt/sources.list.d/unidrive.list

sudo apt update
sudo apt install unidrive

# Then start the daemon (per-user):
systemctl --user enable --now unidrive.service
unidrive auth
unidrive --help</pre>

<hr>

<h2>Fedora / RHEL 10 / Alma / Rocky (dnf)</h2>
<p>Supported: Fedora 40+, RHEL 10, AlmaLinux/Rocky 10+. RHEL 9 is
<strong>not</strong> supported (kernel 5.14 is below the 6.9 floor).
Architectures: x86_64, aarch64.</p>
<pre>sudo curl -fsSL -o /etc/yum.repos.d/unidrive.repo \
  https://dnf.unidrive.krost.org/unidrive.repo

sudo dnf install unidrive

systemctl --user enable --now unidrive.service
unidrive auth
unidrive --help</pre>

<hr>

<h2>Arch Linux / Manjaro / EndeavourOS (AUR)</h2>
<p>Available as <a href="https://aur.archlinux.org/packages/unidrive">
<code>unidrive</code></a> on the AUR. Use your favourite AUR helper:</p>
<pre>yay -S unidrive          # or: paru -S unidrive, etc.

systemctl --user enable --now unidrive.service
unidrive auth
unidrive --help</pre>

<hr>

<h2>Any glibc Linux (tarball)</h2>
<p>The canonical install posture for CLI users. Drops everything
under <code>~/.local/</code>; no root needed.</p>
<pre># Download the per-arch tarball + signature.
ver=0.0.1
arch=$(uname -m)  # x86_64 or aarch64
url=https://github.com/gkrost/unidrive-dist/releases/download/v${ver}

curl -fsSL -o unidrive-${ver}-linux-${arch}.tar.gz \
  ${url}/unidrive-${ver}-linux-${arch}.tar.gz
curl -fsSL -o unidrive-${ver}-linux-${arch}.tar.gz.SHA256SUMS.asc \
  ${url}/unidrive-${ver}-linux-${arch}.tar.gz.SHA256SUMS.asc

# Verify (after importing the public key linked above):
gpg --verify unidrive-${ver}-linux-${arch}.tar.gz.SHA256SUMS.asc

# Extract + install.
tar -xzf unidrive-${ver}-linux-${arch}.tar.gz
cd unidrive-${ver}
bash install.sh

systemctl --user enable --now unidrive.service
unidrive auth
unidrive --help</pre>

<hr>

<h2>Requirements</h2>
<ul>
  <li>Linux kernel ≥ 6.9 (for FUSE_PASSTHROUGH). Hard floor.</li>
  <li>libfuse ≥ 3.16.</li>
  <li>Java 21 runtime.</li>
  <li>systemd (user mode).</li>
  <li>Architecture: x86_64 or aarch64.</li>
</ul>
<p>Windows and macOS are not in scope.
See <a href="/">unidrive.krost.org</a> for the project overview.</p>

</body>
</html>
HTML
```

- [ ] **Step 4: Replace `FINGERPRINT_PLACEHOLDER` with the real fingerprint**

```bash
ssh -p 22222 gernot@87.106.246.31 \
  'docker exec pkg-signer bash -c \
     "GNUPGHOME=/var/lib/signer/gnupg gpg --fingerprint --with-colons" \
   | awk -F: "/^fpr/{print \$10; exit}"'
```

Take the printed 40-hex-char fingerprint and substitute it into the HTML:

```bash
FPR=<paste-the-fingerprint-here>
# Format with spaces for readability: groups of 4 hex chars.
PRETTY=$(echo "$FPR" | sed 's/.\{4\}/& /g; s/ $//')
sed -i "s/FINGERPRINT_PLACEHOLDER/$PRETTY/" sites/unidrive/install/index.html
```

Verify:

```bash
grep -i fingerprint sites/unidrive/install/index.html
```

Expected: the line now contains the formatted fingerprint, not the placeholder.

- [ ] **Step 5: Deploy the install page to the VPS**

The `site-unidrive` container reads from `~/docker/sites/unidrive/`. Sync:

```bash
scp -P 22222 -r sites/unidrive/install \
    gernot@87.106.246.31:~/docker/sites/unidrive/
```

(No container restart needed — nginx serves the directory live.)

- [ ] **Step 6: Verify the page loads**

```bash
curl -fsS https://unidrive.krost.org/install/ | head -20
curl -fsSI https://unidrive.krost.org/install/unidrive-releases.gpg | head -3
```

Expected: HTML returns 200; the .gpg returns 200 with `Content-Type: application/pgp-keys`.

- [ ] **Step 7: Commit + push + merge**

```bash
git add sites/unidrive/install/
git commit -m "$(cat <<'EOF'
sites/unidrive: install instructions landing page

Per-channel install snippets for apt, dnf, AUR, and tarball.
Signing-key fingerprint pinned visibly. Linked from
unidrive.krost.org/install/.

Per spec §10 AC #8.
EOF
)"

git push -u origin feat/install-landing-page

gh pr create --title "feat: unidrive.krost.org/install/ landing page" \
  --body "Install instructions for all four MVP channels. Verified live against the deployed site-unidrive container."

# Merge via UI, then update local main.
```

After merge:

```bash
git checkout main && git pull
```

---

## Task 4: Fill in `docs/release-process.md`

The P4a skeleton stubbed this; now we fill it in with concrete operator steps based on this very cutover.

**Files:**
- Modify: `unidrive-dist/docs/release-process.md`

- [ ] **Step 1: Open the file on the release/v0.0.1 branch**

```bash
cd /home/gernot/dev/git/unidrive-dist
git checkout release/v0.0.1
```

- [ ] **Step 2: Replace the placeholder content**

Write `docs/release-process.md` with this content:

```markdown
# Release process

Operator runbook for cutting a UniDrive release.

## Pre-flight

1. All three repos clean on main; latest changes pulled.
2. The `RELEASES.md` for this release exists and is reviewed.
3. `gh auth status` returns logged in.
4. GH secrets present on all three repos (`GPG_PRIVATE_KEY`,
   `GPG_KEY_FINGERPRINT` on all; `DIST_DEPLOY_KEY`, `SIGNER_DEPLOY_KEY`,
   `AUR_BOT_KEY` on unidrive-dist).
5. VPS: `pkg-server` + `pkg-signer` healthy. DNS for `apt.` and `dnf.`
   sub-domains resolves.

## Cutting a release

Per spec §3.3 the sibling-tag-only-when-source-changes convention
applies; not every release requires a tag on every sibling.

### Tags (in order)

1. **If unidrive source changed since last release:**
   ```
   cd unidrive
   git checkout main && git pull
   git tag vX.Y.Z
   git push origin vX.Y.Z
   # Watch the release.yml workflow on github.com/gkrost/unidrive/actions
   # Wait for it to finish; verify the GH Release contains JAR + .sha256
   # + .asc + .sha256.asc.
   ```

2. **If unidrive-mount-linux source changed since last release:**
   ```
   cd unidrive-mount-linux
   git checkout main && git pull
   git tag vX.Y.Z
   git push origin vX.Y.Z
   # Watch release.yml; wait for both arches' tarballs to attach.
   ```

3. **unidrive-dist (always tagged):**
   ```
   cd unidrive-dist
   git checkout main && git pull
   # If this is a packaging-only respin without sibling changes,
   # tag as vX.Y.Z-pkgN per spec §3.4.
   git tag vX.Y.Z
   git push origin vX.Y.Z
   # Watch release.yml on github.com/gkrost/unidrive-dist/actions.
   # The workflow runs release/release.sh — see below for what it does.
   ```

### What `release.sh` does (sequence from §3.3 step 3)

a. `fetch-artefacts.sh` — pulls + verifies sibling artefacts.
b. Builds tarballs, debs, rpms, AUR PKGBUILD.
c. `sign-packages.sh` — sftp-pushes to `pkg-signer` via ProxyJump,
   triggers `sign-drop`, sftp-pulls back.
d. `publish-apt.sh` — rsync to `pkg-publisher@VPS:apt/`.
e. `publish-dnf.sh` — rsync to `pkg-publisher@VPS:dnf/`.
f. `publish-gh-release.sh` — `gh release upload` of tarball bundle.
g. `publish-aur.sh` — git push to AUR via `AUR_BOT_KEY`.

## Rollback playbook

Per spec §3.6:

- **Bad release reached apt:** SSH to VPS, `mv apt apt-bad && mv
  apt-backup-<latest-good-ts> apt`. publish-apt.sh keeps the 5 most
  recent timestamped backups.
- **Bad release reached dnf:** same with `dnf-backup-*`.
- **Bad GH Release:** `gh release delete vX.Y.Z` on `gkrost/unidrive-dist`
  (keeps the tag). Cut a `-pkgN` respin per spec §3.4.
- **Bad AUR PKGBUILD:** push a corrective PKGBUILD bump.

## Key rotation

- **GPG signing key compromise:** see spec §3.7.
- **SSH deploy key compromise:** see spec §7.5.
- Both playbooks are spec-canonical; do not duplicate them here.

## First-release-only verification

Run the end-to-end manual smoke from spec §8.4 on a fresh Ubuntu 24.04
LTS VM. Document anomalies in this file under a "First-release notes"
heading. Re-run on subsequent releases only when a new distro joins
the supported matrix.
```

- [ ] **Step 3: Commit**

```bash
git add docs/release-process.md
git commit -m "$(cat <<'EOF'
docs(release-process): fill in operator runbook for first release

Replaces the P4a skeleton with concrete steps based on the v0.0.1
cutover. Pre-flight, per-tag instructions, what release.sh does,
rollback playbook (cross-referenced to spec §3.6), key rotation
(cross-referenced to spec §3.7 and §7.5).

Per spec §10 (acceptance — operator can repeat the procedure).
EOF
)"
```

(Don't push yet — still on `release/v0.0.1`. The tag push in Task 5 fires CI; the merge to main happens after the release succeeds.)

---

## Task 5: Cut the tags (the real release)

**Files:** none changed in this task (CI does the work)

- [ ] **Step 1: Determine which sibling repos need tags**

Both sibling repos have source changes for the first release (their entire codebases are "new" for distribution purposes). Tag both.

- [ ] **Step 2: Tag `unidrive`**

```bash
cd /home/gernot/dev/git/unidrive
git checkout main && git pull
git tag -a v0.0.1 -m "First MVP release."
git push origin v0.0.1
```

- [ ] **Step 3: Watch the release workflow**

```bash
gh run watch --repo gkrost/unidrive --exit-status
```

Expected: green workflow run. Verify the GH Release:

```bash
gh release view v0.0.1 --repo gkrost/unidrive
```

Expected: four assets — `unidrive-0.0.1.jar`, `unidrive-0.0.1.jar.sha256`, `unidrive-0.0.1.jar.asc`, `unidrive-0.0.1.jar.sha256.asc`.

**(spec §10 AC #1 verified)**

- [ ] **Step 4: Tag `unidrive-mount-linux`**

```bash
cd /home/gernot/dev/git/unidrive-mount-linux
git checkout main && git pull
git tag -a v0.0.1 -m "First MVP release."
git push origin v0.0.1

gh run watch --repo gkrost/unidrive-mount-linux --exit-status
gh release view v0.0.1 --repo gkrost/unidrive-mount-linux
```

Expected: eight assets (per-arch tarball + .sha256 + .asc + .sha256.asc × 2 arches).

**(spec §10 AC #2 verified)**

- [ ] **Step 5: Merge the RELEASES.md PR for unidrive-dist `[MAINTAINER]`**

The Task 2 PR (`release/v0.0.1` branch) holds the first RELEASES.md entry. Merge it before tagging unidrive-dist:

```bash
cd /home/gernot/dev/git/unidrive-dist
gh pr create --title "release: v0.0.1 RELEASES + release-process runbook" \
  --body "Combines Task 2 (first RELEASES.md entry) and Task 4 (filled-in release-process.md runbook). Merge before tagging v0.0.1." \
  --base main --head release/v0.0.1
# Review and merge via UI.
git checkout main && git pull
```

- [ ] **Step 6: Tag `unidrive-dist`**

```bash
cd /home/gernot/dev/git/unidrive-dist
git tag -a v0.0.1 -m "First MVP release."
git push origin v0.0.1

gh run watch --repo gkrost/unidrive-dist --exit-status
```

Expected: green. This is the long workflow — fetch, build, sign, publish all four channels. Watch progress.

If failures: inspect with `gh run view --log-failed`. Common failure modes:

- **fetch-artefacts SHA mismatch:** the GPG key on the runner can't verify the sibling artefacts. Confirm `GPG_PRIVATE_KEY` is the same on all three repos.
- **sign-packages SSH fails:** the SSH config or deploy key is wrong. Confirm `SIGNER_DEPLOY_KEY` matches the public key on the VPS.
- **publish-apt rsync fails:** `DIST_DEPLOY_KEY` mismatch, or `pkg-publisher` user not provisioned (Plan P3 Task 8 didn't run).
- **publish-aur fails with "Permission denied (publickey)":** `AUR_BOT_KEY` mismatch, or the bot account doesn't co-maintain `unidrive` yet (first-time push — see Task 6).
- **cross 0.2.5 edition-2024 incompatibility:** this should have been caught in P2 Task 4a, but if it surfaces here, apply one of the fallback strategies from P2's KNOWN RISK note.

Iterate until green.

- [ ] **Step 7: Verify the GH Release**

```bash
gh release view v0.0.1 --repo gkrost/unidrive-dist
```

Expected: per-arch tarball bundles + `.sha256` + `.SHA256SUMS.asc`.

**(spec §10 AC #3 verified)**

---

## Task 6: First AUR push and co-maintainer setup `[MAINTAINER]`

The release.sh workflow attempted `publish-aur.sh` in Task 5 Step 6. If the `unidrive` AUR package didn't exist before that push, the first commit creates it. Verify and add co-maintainer.

- [ ] **Step 1: Verify the AUR package exists**

Browser: https://aur.archlinux.org/packages/unidrive

Expected: the package page shows `pkgver=0.0.1, pkgrel=1`, last-updated just now, maintainer = `unidrive-bot`.

If the page returns 404, the first push failed silently. Re-trigger:

```bash
cd /home/gernot/dev/git/unidrive-dist
# The release.sh workflow's publish-aur step failed but didn't fail
# the workflow (publish-aur tolerates "no changes"). Re-run it
# manually with the right env.
AUR_BOT_KEY=~/.ssh/aur_bot_key release/publish-aur.sh 0.0.1
```

- [ ] **Step 2: Add yourself as co-maintainer**

Browser: https://aur.archlinux.org/packages/unidrive → "Package Actions" → "Add Co-maintainer" → enter your personal AUR account name.

This is the single-point-of-failure mitigation from spec §4.4.

**(spec §10 AC #6 verified)**

---

## Task 7: Verify apt + dnf repos `[MAINTAINER]`

- [ ] **Step 1: Verify apt repo**

```bash
curl -fsS https://apt.unidrive.krost.org/ | head -20
curl -fsSI https://apt.unidrive.krost.org/dists/stable/Release
curl -fsS https://apt.unidrive.krost.org/dists/stable/Release | head -20
curl -fsSI https://apt.unidrive.krost.org/dists/stable/InRelease
curl -fsSI https://apt.unidrive.krost.org/dists/stable/Release.gpg
curl -fsSI https://apt.unidrive.krost.org/pool/main/u/unidrive/unidrive_0.0.1-1_amd64.deb
```

Expected: each returns 200. The Release file lists architectures `amd64 arm64`, components `main`.

**(spec §10 AC #4 verified)**

- [ ] **Step 2: Verify dnf repo**

```bash
curl -fsS https://dnf.unidrive.krost.org/unidrive.repo
curl -fsSI https://dnf.unidrive.krost.org/repodata/repomd.xml
curl -fsSI https://dnf.unidrive.krost.org/repodata/repomd.xml.asc
curl -fsSI https://dnf.unidrive.krost.org/x86_64/unidrive-0.0.1-1.x86_64.rpm
```

Expected: each returns 200.

**(spec §10 AC #5 verified)**

---

## Task 8: End-to-end manual smoke `[MAINTAINER]`

Per spec §8.4 — fresh Ubuntu 24.04 LTS VM, real OAuth, real FUSE mount.

- [ ] **Step 1: Spin up a fresh VM**

Use whichever provider you prefer (cloud VPS, local QEMU/VirtualBox, throwaway laptop install). Requirements: Ubuntu 24.04 LTS, kernel ≥ 6.9 (24.04 ships HWE kernels that meet this — verify with `uname -r`), network access.

If the default 24.04 kernel is < 6.9, install HWE: `sudo apt install --install-recommends linux-generic-hwe-24.04` and reboot.

- [ ] **Step 2: Add the apt repo and install**

On the VM:

```bash
curl -fsSL https://unidrive.krost.org/install/unidrive-releases.gpg \
  | sudo tee /etc/apt/keyrings/unidrive.gpg > /dev/null
echo "deb [signed-by=/etc/apt/keyrings/unidrive.gpg] \
  https://apt.unidrive.krost.org stable main" \
  | sudo tee /etc/apt/sources.list.d/unidrive.list

sudo apt update
sudo apt install unidrive
```

Expected: clean install. The `apt update` output shows the new repo with a valid signature.

- [ ] **Step 3: Verify install paths and version**

```bash
which unidrive
unidrive --version
test -f /usr/lib/unidrive/unidrive.jar
test -x /usr/lib/unidrive/unidrive-mount
test -f /usr/lib/systemd/user/unidrive.service
```

Expected: `unidrive --version` prints `0.0.1` (bare, no commit suffix — Task 1's fix verified).

- [ ] **Step 4: Run the daemon + real OAuth (interactive)**

```bash
systemctl --user enable --now unidrive.service
unidrive auth
```

Follow the OAuth browser flow for at least one provider (OneDrive or Internxt). On success, `unidrive profile list` shows the authenticated profile.

- [ ] **Step 5: Run a sync + mount**

```bash
unidrive sync ~/cloud --provider onedrive   # or internxt
unidrive mount ~/cloud
ls ~/cloud                                   # should list cloud root
cat ~/cloud/<some-text-file>                 # triggers hydration
fusermount3 -u ~/cloud
```

Expected: no errors. The hydrated file's content matches what's on the cloud.

- [ ] **Step 6: Uninstall**

```bash
sudo apt remove unidrive
systemctl --user disable unidrive.service
```

Expected: clean removal. Per-user state under `~/.config/unidrive/` and `~/.local/share/unidrive/` remains (per spec §4.6 — packaging never touches it).

**(spec §10 AC #7 verified)**

- [ ] **Step 7: Verify install page is accurate**

```bash
curl -fsS https://unidrive.krost.org/install/ \
  | grep -E '(apt\.unidrive|dnf\.unidrive|aur\.archlinux|unidrive-releases\.gpg)' \
  | head
```

Expected: all four channel sections render with correct URLs. The fingerprint matches what `gpg --verify` reports for any downloaded `.asc` file.

**(spec §10 AC #8 verified)**

---

## Task 9: Close out + announce

- [ ] **Step 1: Verify all eight ACs**

| AC | Verified in |
|---|---|
| §10 AC #1 (JAR + sigs in unidrive GH Release) | Task 5 Step 3 |
| §10 AC #2 (per-arch tarballs in unidrive-mount-linux GH Release) | Task 5 Step 4 |
| §10 AC #3 (end-user tarball bundle in unidrive-dist GH Release) | Task 5 Step 7 |
| §10 AC #4 (signed apt repo) | Task 7 Step 1 |
| §10 AC #5 (signed dnf repo) | Task 7 Step 2 |
| §10 AC #6 (AUR package) | Task 6 Step 1 |
| §10 AC #7 (E2E manual smoke on Ubuntu 24.04 LTS) | Task 8 |
| §10 AC #8 (install page) | Task 8 Step 7 |

All ✅.

- [ ] **Step 2: Move BACKLOG items to CLOSED in all four working repos**

For each repo, any BACKLOG items that this release closed get moved to CLOSED.md. In unidrive-dist specifically, the "first MVP release" tracking item is closed.

- [ ] **Step 3: Tag-only safety check**

Verify the three tags exist and are protected:

```bash
for repo in gkrost/unidrive gkrost/unidrive-mount-linux gkrost/unidrive-dist; do
    echo "=== $repo ==="
    gh release view v0.0.1 --repo "$repo" | head -5
done
```

Expected: each prints "Tag: v0.0.1". Optionally configure GitHub branch protection rules to prevent tag deletion (Settings → Tags → Add tag protection rule for `v*`).

- [ ] **Step 4: Optional — announce**

If the project has a public-facing channel (mailing list, blog, social), post the release. Out of scope for this plan; mention in passing.

- [ ] **Step 5: No further commits**

P5 is complete. The release is live.

---

## Self-Review Notes

**Spec coverage:**

| Spec requirement | Implementing task |
|---|---|
| §3.3 tagging protocol | Task 5 |
| §3.5 `--version` output reconciliation (review-3 Issue 1) | Task 1 |
| §4.7 first RELEASES.md entry | Task 2 |
| §8.4 E2E manual smoke | Task 8 |
| §10 AC #1–#8 | Tasks 5–8 |

**Maintainer-only steps:** Tasks 1 (PR merge), 2 (curate bullets), 3 (write landing page), 5 Step 5 (merge PR), 6 (AUR co-maintainer), 7 (curl verification — could be automated but the human eyeballs reading the apt index is part of the smoke), 8 (whole task — interactive OAuth requires a real human).

**Placeholder scan:** the one `FINGERPRINT_PLACEHOLDER` in Task 3's HTML template is explicitly fixed in Task 3 Step 4. No other TODO/TBD.

**Cross-plan dependencies:**

- Inputs: P1, P2, P3, P4a, P4b all merged.
- Outputs: a live `v0.0.1` UniDrive release. No further plans needed for MVP — subsequent releases follow `docs/release-process.md` (Task 4).

**Risks captured:**

- Cross-build edition-2024 incompatibility (P2 Task 4a should have caught it; documented fallback if it surfaces here).
- AUR first-push edge cases (handled in Task 6 with a fallback re-trigger).
- BuildInfo reconciliation tested in dev (Task 1 Steps 5+6) before tagging.
