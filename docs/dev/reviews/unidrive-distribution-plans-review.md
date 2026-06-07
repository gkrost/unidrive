# Review: Distribution plans P1–P4a (branch `spec/distribution`)

> Third in the review series after the spec was approved. This review
> covers the four implementation plans drafted on top of the spec.
>
> Status: **No blocking issues. Six findings addressed in the plans
> via a follow-up fix commit.**

## Scale

Four plans totaling ~4,800 lines across 4,372 file-lines of plan
content, creating/modifying files in 4 repos (unidrive,
unidrive-mount-linux, krost-infra, unidrive-dist).

---

## Cross-plan verification table

| Spec AC | Plan | Status |
|---|---|---|
| §10 AC #1 — JAR + SHA256 + ASC in GH Release | P1 Task 2 | ✅ Implemented |
| §10 AC #2 — Per-arch tarballs + sigs in GH Release | P2 Task 3 | ✅ Implemented |
| §10 AC #3 — End-user tarball bundle in unidrive-dist | P4a Tasks 7–9 | ✅ Implemented |
| §10 AC #4 — `apt.unidrive.krost.org` signed repo | P3 Tasks 5, 10 (scaffold); content deferred to P5 | ⚠️ Deferred |
| §10 AC #5 — `dnf.unidrive.krost.org` signed repo | Same as #4 | ⚠️ Deferred |
| §10 AC #6 — AUR package | Deferred to P4b | ⏳ Pending |
| §10 AC #7 — E2E manual smoke | P5 | ⏳ Pending |
| §10 AC #8 — Install page on `unidrive.krost.org` | P5 | ⏳ Pending |

## Cross-repo coordination matrix

The plans correctly identify all cross-repo dependencies:

| Artefact | Produced by | Consumed by | Verified in |
|---|---|---|---|
| GPG private key (`GPG_PRIVATE_KEY`) | P3 Task 6 (krost-infra VPS) | P1, P2 workflows | P3 Task 11 |
| GPG fingerprint (`GPG_KEY_FINGERPRINT`) | P3 Task 6 | P1, P2 workflows | P3 Task 11 |
| `DIST_DEPLOY_KEY` | P3 Task 8 | P4a (unidrive-dist) | P4a Task 0 |
| `SIGNER_DEPLOY_KEY` | P3 Task 9 | P4a (unidrive-dist) | P4a Task 0 |
| `unidrive-0.0.1.jar` + sigs | P1 (unidrive) | P4a `fetch-artefacts.sh` | P4a Task 9 (local-build) |
| `unidrive-mount-0.0.1-*.tar.gz` + sigs | P2 (mount-linux) | P4a `fetch-artefacts.sh` | P4a Task 9 (local-build) |

## Issues found

### Issue 1: P1's "no BuildInfo changes" clause contradicts the spec's version output requirement (Medium)

P1 §"Scope explicitly NOT in this plan" said: `"No BuildInfo.kt changes
— the existing --version enrichment with commit-SHA is pre-existing
and stays as-is."` But spec §3.5 says `unidrive --version` prints
exactly `0.0.1` with "No commit SHA, no build date."

The current `BuildInfo.kt` (from `core/app/cli/build.gradle.kts:108-109`)
embeds the commit hash even on clean builds:

```kotlin
val versionString =
    if (dirty) "$version ($commit-dirty)" else "$version ($commit)"
```

P1 defers this change. If nothing else changes it, the first release's
`unidrive --version` will print `0.0.1 (abc1234)` — violating §3.5.
This needs to be resolved before the first tag.

**Resolution applied:** P1's scope clause was rewritten to make the
tension explicit and defer resolution to P5's pre-flight, where the
choice is (a) suppress the `(commit)` suffix on tag-built releases
or (b) accept the enriched form and treat the spec wording as
descriptive-of-the-coordinated-version rather than literal-CLI-output.
P1 stays focused on CI workflow; P5 takes responsibility.

### Issue 2: P3 pkg-signer `sign-forced-command.sh` drops originals before CI pulls from `signed/` (Medium)

The `sign-drop` subcommand (P3 Task 4 Step 4) removes the originals
from `drop/` AFTER signing and copying to `signed/`. If the CI
runner's sftp-pull from `signed/` fails (network blip, container
restart, runner crash), the signed artefacts remain in `signed/` but
the originals are gone from `drop/`. The recovery path is for the CI
to retry the sftp-pull — not to re-submit and re-sign.

This is correct-but-non-obvious. Documented as a comment inline in
the forced-command script (and a note that the recovery is
"sftp-pull retry, not re-sign").

**Resolution applied:** Inline comment added to P3 Task 4's
`sign-forced-command.sh` explaining the recovery semantics.

### Issue 3: P4a `build-tarball.sh` version extraction fragile (Low)

P4a's `local-build.sh` reads the unidrive version with:

```bash
JVM_SEMVER=$(awk -F'"' '/^[[:space:]]*version = / && !v { v=1; print $2 }' "$UNIDRIVE/core/build.gradle.kts")
```

This matches the FIRST `version = "..."` line that starts with
optional whitespace. In the current `core/build.gradle.kts`, that's
the `allprojects { version = "0.0.1" }` block — correct. If the root
build is ever refactored to use a property indirection (e.g.
`version = libs.versions.unidrive.get()`), the regex won't match the
new form and the extraction silently breaks.

**Resolution applied:** Inline comment in `local-build.sh` documents
the fragility and suggests a future-refactor path (`./gradlew -q
:printVersion` helper in the unidrive repo). The guard
`[[ -n $JVM_SEMVER ]]` makes the failure loud rather than silent.

### Issue 4: P4a `fetch-artefacts.sh` relies on `gh` CLI auth but doesn't document setup (Low)

The script uses `gh release list`, `gh release view`, and `gh release
download`. CI gets `GITHUB_TOKEN` automatically; local invocation
needs the user to have run `gh auth login`. The script header didn't
mention this prerequisite.

**Resolution applied:** A dedicated "Authentication prerequisite"
block added to the script header in P4a Task 7, distinguishing CI
(automatic) from local (`gh auth status` first).

### Issue 5: P4a `AGENTS.md` says "no JVM or Rust source code" but `test/local-build.sh` builds both (Minor)

P4a's AGENTS.md hard rule said "**No JVM or Rust source code.**" But
`test/local-build.sh` explicitly calls `./gradlew :app:cli:shadowJar`
and `cargo build --release --bin unidrive-mount` — this is a
dev-loop convenience for fast packaging iteration. The AGENTS.md
phrasing didn't carve out the exception.

**Resolution applied:** P4a Task 3's AGENTS.md draft now reads "No
JVM or Rust source code **committed here**" plus an explicit
"Dev-loop exception" bullet noting that `test/local-build.sh` may
invoke local sibling-repo builds while CI never does.

### Issue 6: P2 uses `cross v0.2.5` pinned but this version may not support Rust edition 2024 (Medium)

P2 Task 3 pins `cargo install --locked --version 0.2.5 cross`. The
`mount/Cargo.toml` uses `edition = "2024"` which requires
`rustc ≥ 1.85`. `cross` 0.2.5 was released February 2025 — close to
the rustc 1.85 ship date — and its release notes don't explicitly
mention edition 2024 support. Whether 0.2.5's pinned Docker images
ship a fresh enough rustc is unverified.

Risk: the cross-build fails on the first real tag push in CI.

**Resolution applied:** Two changes to P2:

1. The workflow YAML's "Install cross" step now carries an inline
   "KNOWN RISK" comment with two fallback strategies (bump to cross
   `main` pinned to a SHA, or override `Cross.toml` to a pinned
   `ghcr.io/cross-rs/aarch64-unknown-linux-gnu:edge` image).
2. A new **Task 4a** runs a real local aarch64 cross-build before
   the first tag push, so an incompatibility is caught at dev-loop
   time rather than during a real release.

### Issue 7: P3 `repo-server` nginx `autoindex on` may leak internal repo structure (Informational)

Standard apt/dnf practice. No action.

### Issue 8: P3 `sign-forced-command.sh` deb signing uses `dpkg-sig --sign builder` — convention but not enforced (Informational)

Standard. No action.

## Summary

| Category | Count | Details |
|---|---|---|
| **Cross-plan inconsistency (fixed)** | 1 | Issue 1 |
| **Potential CI failure (mitigated)** | 1 | Issue 6 |
| **Minor correctness (clarified)** | 2 | Issue 3, Issue 4 |
| **Documentation gap (fixed)** | 1 | Issue 5 |
| **Operational subtlety (documented)** | 1 | Issue 2 |
| **Informational (no action)** | 2 | Issues 7, 8 |
| **Implemented correctly** | ~30 | All spec requirements covered across the four plans |

## Overall

The P1–P4a plan set is thorough, consistent, and implementable. The
four plans correctly sequence dependencies (P3 infrastructure → P1+P2
workflows → P4a dist repo), properly tag maintainer-only steps, and
provide local dry-run procedures for every CI job. All six findings
were addressed inline in the plans on the same `spec/distribution`
branch; the cross-coordination concerns (Issues 1 and 6) are now
either re-scoped to a later plan (Issue 1 → P5 pre-flight) or
mitigated with an explicit verification task (Issue 6 → P2 Task 4a).
