# Roadmap

unidrive is in preview. This page sketches where releases are headed.
The authoritative ticket list lives in
[`docs/backlog/BACKLOG.md`](backlog/BACKLOG.md); the shipping surface
contract lives in [ADR-0014](adr/0014-v01-surface-consolidator.md).

## v0.1.0 — first release (Linux MVP)

**Goal:** a daemon + CLI you can trust with one cloud account on Linux.

- Quality-gated providers: `localfs`, `s3`, `sftp`.
- `:app:cli` + `:app:mcp` + `:app:sync` ship; `:app:benchmark` ships
  with the speed-ranking publication pipeline (UD-701).
- Linux-only. Windows / macOS are out of scope — see
  [ADR-0012](adr/0012-linux-mvp-protocol-removal.md) and
  [`docs/NON-GOALS.md`](NON-GOALS.md).
- Outstanding quality gates tracked in
  [BACKLOG.md](backlog/BACKLOG.md) at `priority: high`. Highlights:
  test coverage ≥ 60% for v0.1.0 scope (UD-801), retry-budget
  rollout across all HTTP providers (UD-330), NFC path
  normalisation (UD-739), single-flight-per-profile enforcement
  (UD-004).

## v0.2.0 — preview providers graduate

**Goal:** the eight providers in `core/providers/` are all
quality-gated.

Each currently-preview provider (`onedrive`, `webdav`, `hidrive`,
`internxt`, `rclone`) needs to clear:

- Live-integration test in CI behind the `unidrive-test-oauth` MCP
  (see [`scripts/dev/oauth-mcp/README.md`](../scripts/dev/oauth-mcp/README.md)).
- Capability-contract round-trip (move / mkdir / share /
  listShares / revokeShare / webhook / verifyItemExists /
  deltaWithShared) per [ADR-0005](adr/0005-provider-capability-contract.md)
  — tracked under UD-805.
- Parallelism budget tuned in `ProviderMetadata` against a real
  account.
- The provider-specific reliability work filed in BACKLOG
  (Internxt: UD-300..307; OneDrive: UD-806 sibling chunks; S3 / SFTP
  / WebDAV: UD-806 failure-path harness rows).

## v0.3.0 — release artefacts

**Goal:** users install unidrive without a Gradle invocation.

- Standalone installer script (`dist/install.sh`, UD-761).
- GitHub Releases artefact: fat JAR + minimal shell wrapper. Source
  build remains the canonical path.
- Webhook-driven sync graduates from experimental (UD-??? — to be
  filed when the design lands).
- Public speed-ranking dashboard at `unidrive.krost.org` driven by
  `:app:benchmark` runs (UD-701).

## Beyond v0.3.0 — not committed

Items in this section are tracked but not scheduled. Inclusion here
does not imply they will ship.

- **Shell extensions.** Linux (Nautilus + Dolphin integration);
  Windows (Explorer overlay icons, depends on appetite + the
  Windows re-opening criteria in [ADR-0012](adr/0012-linux-mvp-protocol-removal.md)).
  Idea-list lives in `docs/BACKLOG_IDEAS_UI.md`.
- **Companion projects.** `unidrive-android` is in flight in an
  adjacent repo. `unidrive-tray` is explicitly an out-of-tree
  community surface — the daemon's NDJSON event stream over UDS is
  the public contract.
- **Provider expansion.** Google Drive, Dropbox, Box are currently
  only reachable via the rclone gateway provider. First-class
  providers for any of these would be ≥ v0.4.0 territory and would
  require a community contributor proposing to own ongoing
  maintenance.
- **macOS.** Conditional on the re-opening criteria in
  [ADR-0012](adr/0012-linux-mvp-protocol-removal.md): a funded
  effort, a specific paying customer, or a community contributor
  who proposes to own ongoing maintenance.

## How to influence the roadmap

- File a `priority: ?` BACKLOG ticket in the relevant category.
  See [`docs/AGENT-SYNC.md`](AGENT-SYNC.md) for the contract.
- Open a discussion on GitHub if the proposal touches a non-goal
  (see [`docs/NON-GOALS.md`](NON-GOALS.md)) — a non-goal is not
  a permanent ban, but moving it back into scope needs an ADR.
- Sponsor a provider's preview → quality-gated transition: pick
  one of the UD-806 / UD-805 chunks for that provider and
  contribute it.
