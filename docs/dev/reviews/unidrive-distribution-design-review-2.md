# Follow-up Review: unidrive-distribution-design.md (current branch)

> Re-review after the address-review-findings commit (`0617e0a`).
> Status: **No blocking issues. Minor observations only.**

---

## §1. Issues from initial review — resolution check

| # | Issue | Resolved? | How |
|---|---|---|---|
| 1 | `systemctl --global` | ✅ | postinst now empty, rationale documented |
| 2 | Lockstep tagging wasteful | ✅ | Sibling repos tag only when changed |
| 3 | Version parsing hand-waved | ✅ | Explicit regex `^v(\d+\.\d+\.\d+)(-pkg\d+)?$` |
| 4 | `java21-runtime` virtual | ✅ | Changed to `java-runtime-headless (>= 21)` |
| 5 | JDK-native-access risk | ✅ | Pinned to JDK 21, shelf life documented |
| 6 | GPG architecture ambiguous | ✅ | Clear pkg-signer container, key never leaves server |
| 7 | AUR Docker test needs non-root | ✅ | Non-root `build` user with sudo documented |
| 8 | Ubuntu 26.04 doesn't exist | ✅ | Changed to 24.04 primary, 26.04 "when available" |
| Minor: JAR naming | ✅ | `unidrive-X.Y.Z.jar` (existing convention) |
| Minor: wrapper generation | ✅ | §4.1 documents install.sh heredoc pattern |
| Minor: man page | ✅ | Deferred to §9 |

---

## §2. New observations (post-review)

### #A — Tarball signing in the pkg-signer flow (§5.2)

The release flow in §5.2 signs `.deb`, `.rpm`, and repo metadata via
`pkg-signer`. The tarball bundle's `SHA256SUMS.asc` (§4.1) must also be
signed. The flow should explicitly include the tarball's checksum file in
the set of artefacts pushed to `pkg-signer`. Currently step 2 says "pushes
the unsigned artefacts" but only lists "`.deb`, `.rpm`, AUR source tarball,
end-user tarball" — and step 3 signs everything queued. The checksum file
gets generated before upload to the signer, so it's covered, but calling
out `SHA256SUMS` explicitly would remove ambiguity.

### #B — OAuth step in E2E manual smoke is interactive (§8.4)

`unidrive auth` requires browser-based OAuth or a token input. The E2E
script runs this as a CLI command with no `--batch` or `--token` flag
shown. The spec should note that this step is interactive and requires a
real human to complete the OAuth flow, or that a pre-configured
`UNIDRIVE_TEST_TOKEN` equivalent is used for the smoke. Minor — the script
is documented as a one-time manual check, not automated.

### #C — pkg-signer forced command: stdin vs drop-dir ambiguity (§5.1)

§5.1: "via stdin (or a tightly-scoped sftp drop directory)". Two possible
approaches listed as alternatives. The implementation plan should pick one.

### #D — debian/changelog maintainer identity (§4.7)

The RELEASES.md → debian/changelog conversion needs a maintainer identity
(name, email, timestamp). The spec doesn't specify one. Should be
`releases@unidrive.krost.org` to match the GPG key identity.

### #E — `sign-packages.sh` in repo layout (§6)

The `release/` directory lists `sign-packages.sh`. The release-time flow
(§5.2) signs via SSH to krost-infra's `pkg-signer`, not locally. The dev
loop (`local-build.sh`, §8.5) may need local-only signing (e.g., GPG on the
developer's machine). The spec should note whether `sign-packages.sh` is
for the dev-loop path and how it differs from production signing.

### #F — Snap re-evaluation: Ubuntu 26.04 Snap-default angle (§4.5)

The re-evaluation criteria are sound, but if Ubuntu 26.04 ships with
aggressive Snap defaults (as Ubuntu has been trending), the "apt is the
canonical channel" assumption may face user friction. Not a current
concern — the criteria already cover this case under (a) funded effort.
Mentioned only for design-continuity awareness.

---

## §3. Overall assessment

The spec is complete, internally consistent, and fully aligned with project
conventions. Every issue from the initial review was addressed. The new
`pkg-signer` architecture, `RELEASES.md` changelog strategy, AUR push-auth
mechanics, and threat-model documentation are substantial improvements over
the original draft.

Ready for implementation planning. The six observations above are
implementation-plan fodder, not spec blockers.
