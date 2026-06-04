# Review: unidrive-distribution-design.md

> Intensive review against project conventions (AGENTS.md, ADRs), technical
> soundness, security, and operational completeness.
>
> Status: **Approved with issues** — eight should-address items, one must-fix
> (the postinst `systemctl --global` command). None are design-level blockers;
> all are resolvable at the implementation-plan stage.

---

## §0. Cross-reference verification

Checked against:
- `AGENTS.md` (93 lines — hard rules, what-lives-where, commit etiquette)
- `docs/adr/multi-platform.md` (40 lines — the platform-scope framing)
- `docs/adr/core-app-contract.md` (59 lines — engine/client boundary)
- `docs/adr/capability-contract.md` (30 lines — provider SPI contract)
- `dist/install.sh` (120 lines — current local-dev installer)
- `dist/unidrive.service` (14 lines — current systemd unit)

The spec is structurally consistent with all of them.

---

## §1. Alignment with project conventions (AGENTS.md / ADRs)

| Convention | Compliance | Notes |
|---|---|---|
| Hard rule: two providers | ✓ | No provider changes |
| No CI policing | ✓ | `smoke-all.sh` gate, no coverage/lint baselines |
| Abstractions earn their keep | ✓ | §2.2 gives three named justifications for `unidrive-dist` |
| Doc surface bounded | ✓ | Lives in `docs/dev/specs/` |
| No IDs/dates/versions | ✓ | §6.1 carves explicit exception for dist repo |
| Core-app-contract: no engine packaging | ✓ | All packaging is client-layer; `--enable-native-access` flag preserved |
| Multi-platform: daemon focus | ✓ | Scope is Linux daemon distribution only |
| No CI policing | ✓ | §8.1 matches the stance |
| Commit etiquette | ✓ | Not directly addressed (out of scope for this doc) |

---

## §2. Issues requiring action

### [MUST-FIX] #1 — `systemctl --global daemon-reload` is invalid (§4.2)

The spec's `postinst` scriptlet for the deb package says:
```
systemctl --global daemon-reload || true
```

`systemctl --global` does not exist. The correct reload for user units at
`/usr/lib/systemd/user/` is `systemctl --user daemon-reload`, but this runs
as root in `postinst` and has no effect on any user's session — it reloads
root's own `systemd --user` (which probably isn't running).

**Standard Debian practice** for user units: systemd discovers them
automatically from `/usr/lib/systemd/user/` on the next user login. No
`postinst` daemon-reload is needed. `postinst` should simply not call it.

The rpm `%post` scriptlet has the same pattern and the same issue.

### [SHOULD-FIX] #2 — Lockstep tagging for unchanged repos is wasteful (§3.3)

The protocol requires `unidrive-mount-linux` to tag `vX.Y.Z` even when
nothing changed in the Rust code. Re-issuing a tag on an unchanged repo
produces byte-identical artefacts (same binary, same SHA256) under a
different tag, which breaks the "one tag = one content" assumption that
most readers hold about git tags.

Recommendation: the protocol should say that each repo tags *only when its
own content changes* for that release. `fetch-artefacts.sh` then resolves
the needed upstream tag and uses whichever artefact tag exists (or falls
back to the prior tag's artefacts if the auth repo has no new tag).
Simplify: make the dist tag `vX.Y.Z` authoritative and have the fetch
script query sibling GH Releases for the *latest* release at the same
SemVer (which works whether or not a new tag was cut). This avoids the
"empty tag" problem entirely.

### [SHOULD-FIX] #3 — `fetch-artefacts.sh` version-parsing is load-bearing, not trivial (§3.4)

§3.4: "Cost: `fetch-artefacts.sh` must parse `v0.0.1-pkgN` as referring to
upstream tag `v0.0.1`. Trivial."

This parsing is the correctness invariant for the entire packaging-only
respin workflow. The spec should spell it out:

- Input: `git tag --describe` output or CI env `GITHUB_REF_NAME` =
  `v0.0.1-pkg2`
- Rule: strip suffix `-pkg<digits>` if present → upstream tag `v0.0.1`
- Rule: if no suffix matches `-pkg<digits>`, the tag IS the upstream tag
- The regex `/^v(\d+\.\d+\.\d+)(-pkg\d+)?$/` anchors the parse

Hand-waving a correctness-critical parsing step is a gap.

### [SHOULD-FIX] #4 — `java21-runtime` virtual package may not exist (§4.2)

The `debian/control` dependency line:
```
Depends: openjdk-21-jre-headless | java21-runtime
```

The `java21-runtime` virtual package is not a standard Debian/Ubuntu virtual
package. The known virtual packages are `java-runtime-headless` (versioned,
e.g. `java-runtime-headless>=21`), `java-runtime`, `java11-runtime`, etc.
`java21-runtime` may not resolve to any provider.

Recommendation: depend directly on `openjdk-21-jre-headless (>= 21)` without
an OR alternative (or use `java-runtime-headless (>= 21)` if that's the
accepted virtual). Verify with `grep-aptavail -F Package -s Provides` on
both Ubuntu 24.04 and Debian 12.

### [SHOULD-FIX] #5 — `--enable-native-access` JDK-version risk not addressed (§4.6)

The spec correctly flags preserving `--enable-native-access=ALL-UNNAMED`
across all channels. But:
- JDK 22 changed the flag to `--enable-native-access=<module-name>`
- In future JDKs, `ALL-UNNAMED` may be deprecated or removed
- Ubuntu 26.04 may ship JDK 23+

The spec should either: (a) document the JDK range explicitly (21-22, with
a BACKLOG item to track deprecation), or (b) define the wrapper script to
detect `java --version` and select the right flag variant.

For MVP, pinning to JDK 21 is fine — just call out the shelf life.

### [SHOULD-FIX] #6 — GPG signing architecture description is ambiguous (§5)

§5 alternates between three architectures without picking one clearly:

1. "Stored in a restricted secret store on the krost-infra server"
2. "The dist CI runner accesses it via a chrooted SSH user (pkg-publisher)"
3. "(simpler MVP: the CI runner pulls the key into ephemeral memory ...)"

If (3) is the MVP choice (the text says "MVP starts with in-CI signing"),
then (1) and (2) describe how the key is stored before CI pulls it. But
(2) says the access is via SSH to krost-infra — that contradicts "in-CI"
if the runner is on GH Actions (it must SSH to krost-infra to get the key,
then sign locally). That's actually *remote signing with local execution*.

Recommendation: pick exactly one architecture for MVP and describe it as a
numbered decision. Ideally: "MVP: the signing key lives as a GH Actions
encrypted secret (`GPG_SIGNING_KEY` + `GPG_SIGNING_PASSPHRASE`). The release
workflow imports the key into an ephemeral GPG keyring, signs, then scrubs
the secret from memory. A remote signing service on krost-infra is a BACKLOG
item." Keep `pkg-publisher` as the rsync SSH user for artefact upload only,
not key access.

### [SHOULD-FIX] #7 — `makepkg` in Docker for AUR test needs non-root + sudo (§8.2)

The smoke test `test/aur-install-in-docker.sh` runs `makepkg -si` in an
Arch Linux container. `makepkg` refuses to run as root. The standard Arch
Docker image sets up a `build` user, but `-i` (install deps) requires sudo.

The test script must either:
- Configure sudo for the build user in the Dockerfile/entrypoint, or
- Use `PKGDEST` and `PKGEXT` to build without installing, then
  `pacman -U` separately with `--noconfirm`

This needs to be documented in the spec or deferred to the implementation
plan with a note.

### [SHOULD-FIX] #8 — Ubuntu 26.04 in acceptance criteria doesn't exist yet (§10)

AC #7 says "fresh Ubuntu 26.04 VM." As of this review, Ubuntu 26.04 has not
been released. The spec should use Ubuntu 24.04 LTS (Noble) as the primary
target for MVP (matching §4.2 which lists 24.04 + 26.04 as targets). 26.04
can be added when it exists.

---

## §3. Minor issues and observations

### #9 — `--version` output divergence from deb version string (§3.5)

The spec says `unidrive --version` prints exactly `0.0.1`, while the deb
version string is `0.0.1-1` (with Debian revision). A user running
`dpkg -l` sees `0.0.1-1` but the binary prints `0.0.1`. This is a minor but
real version-string mismatch that will surface in bug reports ("I have 0.0.1"
— "which packaging revision?"). Add a note in `compatibility.md`.

### #10 — No logrotate config for deb/rpm (§4.2)

The daemon writes to `~/.local/share/unidrive/unidrive.log`. For FHS
(deb/rpm) installs, the daemon still writes to the user's home directory
(per-user state), so system-wide logrotate doesn't apply. But the rpm `%logrotate`
spec or a `debian/logrotate` config could still be shipped as a convenience.
Not required for MVP — add to the deferred list or mention as a note.

### #11 — JAR naming convention: `unidrive-cli-*.jar` vs `unidrive-*.jar` (§4.1)

The spec uses `unidrive-cli-0.0.1.jar` in the tarball layout. The current
build produces `unidrive-<version>.jar` (no `-cli-` infix). If the naming
is changing, call it out explicitly.

### #12 — No Docker-in-Docker mention for CI tests (§8.2)

The smoke tests run Docker containers inside GH Actions. GH Actions runners
have Docker available, but the spec doesn't mention this dependency. Trivial.

### #13 — AUR push collision risk not addressed (§4.4)

`publish-aur.sh` pushes to the AUR git repo via SSH deploy key. If someone
else pushed between our fetch and push, the push fails. For solo-maintainer
projects this is unlikely, but the release docs should cover
`git pull --rebase && re-generate` as the recovery.

### #14 — `pkg-server` resource limits are generous (§7.1)

64M RAM and 0.25 CPU for an nginx serving static files — this is ample.
Fine for MVP; note for future scaling.

### #15 — `traefik.enable=false` + explicit router in dynamic.yml (§7.1/§7.2)

Valid pattern (disables Docker auto-discovery but file-provider routes still
work). Add a comment in the nginx config snippet or the spec to explain why
this isn't contradictory.

### #16 — Missing failure mode: CI runner outage (§3.7)

The spec covers signing key compromise, container down, and sibling CI
failure. But not GH Actions outage itself. Acceptable for a solo-maintainer
project but worth a tactical note in `docs/release-process.md`.

### #17 — Tarball wrapper script must be dynamically generated (§4.1)

The tarball's `install.sh` writes the wrapper script with a hardcoded JAR
path. If the JAR name includes the version (as in the tarball layout), the
wrapper must be generated at install time (which the spec says `install.sh`
does). The spec's tarball layout lists `lib/unidrive-cli-X.Y.Z.jar` but
doesn't show the wrapper generation step. Call this out explicitly.

### #18 — No `--help` or man page content for the stub man page (§4.2)

§4.2: `/usr/share/man/man1/unidrive.1.gz ← stub for MVP`. What does the
stub contain? "See `unidrive --help`"? The spec could either drop the man
page from MVP scope or define the stub content.

---

## §4. Things done right (notable)

- **Four named justifications for a separate repo** (§2.2). Clears the
  "abstractions earn their keep" bar solidly.

- **Explicit AGENTS.md exception for versions** (§6.1). Well-bounded: release
  artefacts, changelogs, spec files yes — commit messages, BACKLOG entries no.
  Prevents the version-prohibition rule from making release tooling unusable.

- **No auto-start, no auto-enable** (§4.6). Consistent with the project's
  "user explicitly chose" philosophy and the README's anti-telemetry pitch.
  The rationale is fully spelled out.

- **Runtime kernel-floor check, not install-time** (§4.2). Correct: avoids
  blocking legitimate use cases (container builds, kernel-upgrade in progress).
  The RHEL 9 caveat in §4.3 is well-handled.

- **Snap/Flatpak deferral** (§4.5). The rationale is detailed and persuasive.
  The re-evaluation criteria mirror the project's existing reopening criteria.

- **Four failure modes documented** (§3.7). They're the right ones. The
  rollback story (§3.6) is refreshingly honest ("no automatic rollback").

- **End-to-end manual smoke** (§8.4). One page someone can follow to verify
  everything works end-to-end on a real machine. Essential for the first
  release.

- **Test matrix is bounded, not aspirational** (§8.2). Four smoke test
  scripts, each in its target container, with explicit non-tests. Matches
  the project's "smoke tests are the live-integration target" rule.

- **Many deferred items explicitly called out** (§9). Knowing what is *not*
  MVP is almost as important as what is. The `BACKLOG.md` design-constraint
  filing is noted.

---

## §5. Summary

| Category | Count | Details |
|---|---|---|
| MUST-FIX | 1 | #1: `systemctl --global` is invalid |
| SHOULD-FIX | 7 | #2 (lockstep tagging), #3 (version parsing gap), #4 (java21-runtime virtual), #5 (JDK-native-access risk), #6 (GPG architecture ambiguity), #7 (AUR Docker test), #8 (Ubuntu 26.04 doesn't exist) |
| Minor | 10 | #9–#18 above |

None are design-level blockers. The spec is thorough, well-structured, and
consistently cross-references the project's existing conventions. The
must-fix and should-fix items are refinement issues that can be resolved
during the implementation plan phase without re-approval.
