# Agent instructions

unidrive is a multi-platform cloud-sync core. Today it ships as a Linux daemon syncing **Internxt Drive** and **OneDrive**. Tomorrow it ships as the engine that powers a Windows desktop client, an Android app, and a Linux UI. See [docs/adr/multi-platform.md](docs/adr/multi-platform.md) for the framing this file enforces.

This file is the rulebook for everyone touching the repo — human contributors and LLM agents alike. End-users land on `README.md`; the moment you want to *change* something, you read this file.

## Hard rules

- **Two cloud providers, Internxt and OneDrive.** Adding a third is a deliberate decision tied to a platform-surface need, not a quick win. The provider SPI lives in `core/providers/`; new surfaces consume what's already there.
- **Smoke tests are the live-integration target** (currently 5 onedrive + 5 internxt + 2 sync = 12). Add to the smoke set when an existing test no longer covers a path. Structural-safety invariants — tests that prove a class of bug is impossible, not just an instance — earn their keep; the tracking-set lemma is the standard example. Unit tests get added with intent, not speculatively. Pruning unit tests is per-test, not a sweep (the *Ask before deleting* rule applies).
- **Doc surface is bounded.** Shared docs are this file, `README.md`, `BACKLOG.md`, `CLOSED.md`, `BOOSTERS.md`, ADRs under `docs/adr/`, audits under `docs/audits/`, design specs under `docs/dev/specs/`, implementation plans under `docs/dev/plans/`. Per-module `README.md` files inside `core/providers/<name>/` and `core/app/<module>/` are permitted; they document that module only. New ADRs may be added when a decision is load-bearing enough to outlive memory. New audits when a provider quirk needs reverse-engineering. Specs and plans are per-feature working notes; once a spec ships, its closing BACKLOG entry cites the spec path so the documentation surface stays grep-discoverable.
- **No IDs, dates, or version numbers** in commit messages, file names, or document content. Describe what a thing is, not when it was filed or which release ships it.
- **Abstractions earn their keep.** With four planned surfaces (daemon + Windows + Android + Linux UI), some module seams are required. The bar: a new abstraction needs a named justification — a platform-surface need or a structural-safety property — and at least one concrete consumer. Don't preemptively factor; don't refuse on principle either. The tracking-set engine is the current explicit exception, tied to the `.safe/` incident class.
- **No CI policing.** `./gradlew check` is the gate. No semgrep, gitleaks, codecov, trivy, ktlint baselines, version-drift jobs.
- **Never hot-swap a `.jar` on a running JVM.** The classloader corrupts mid-execution. Stop the daemon (`systemctl --user stop unidrive.service`), then copy.

## Output token management

- **Write long outputs to disk.** For long analysis or ticketing sessions, write outputs (tickets, summaries, audits) to files rather than emitting them inline to chat.
- **Keep chat updates concise.** Offload verbose content (logs, full ticket bodies, large diffs) to disk and reference their paths.

## How to work

1. Read the top of `BACKLOG.md`. Pick the first item that isn't blocked.
2. Read three nearby source files before writing. The existing patterns are the style guide.
3. **Pre-execution sanity check.** If the work goes beyond the BACKLOG item itself — scope expansion, deletion of a user-facing feature, rewrite of a stable file in the same session as adjacent prunes, introducing a new abstraction not already approved — surface it and pause for confirmation before executing. Plan approval doesn't cover sideband cuts.
4. Make the change. Run `./gradlew check`. Iterate.
5. Move the item from `BACKLOG.md` to `CLOSED.md` in the same commit that lands the work. One commit, one item.
6. If you discover a new piece of work, append it to `BACKLOG.md` under the matching priority section in one line.

## Verification

- **Do not rely on summaries.** Verify load-bearing claims (labels, gap analyses, file states) with a full pass before reporting.
- **Re-verify everything on red flags.** If code review flags fabricated configurations or wrong config precedence, treat it as a signal to re-verify the *whole artifact*, not just the called-out line.

## What lives where

- `core/providers/internxt/` — Internxt client (see its `README.md` for client notes, robustness model, known limits)
- `core/providers/onedrive/` — OneDrive client (ditto)
- `core/app/core/` — auth framework, HTTP, error handling, credential vault, shared `org.krost.unidrive.{http,auth,io}` helpers
- `core/app/sync/` — current reconciliation engine; on a retirement path as the tracking-set engine lands
- `core/app/sync-tracking/` — structural-safety engine (tracking-set predicate over deletion). Landed to make the phantom-row delete-cascade class of bug impossible by construction.
- `core/app/cli/` — CLI entry point
- `core/app/config/` — TOML config + profile management
- `BOOSTERS.md` — what each provider's API offers and what to adopt next
- `docs/audits/` — reverse-engineering notes for Internxt; keep, don't expand without reason
- `docs/adr/` — architectural decisions, including [`multi-platform.md`](docs/adr/multi-platform.md) (the platform-scope decision), [`core-app-contract.md`](docs/adr/core-app-contract.md) (the engine/client boundary), [`capability-contract.md`](docs/adr/capability-contract.md) (the provider SPI), and the two slim-phase ADRs that `multi-platform.md` supersedes

Future platform surfaces (Windows desktop, Android, Linux UI) will land as separate platform tiers — likely outside this repo's `core/` tree. They consume the core; they don't extend it inline.

## Build and run locally

```bash
cd core && ./gradlew check     # the only gate
cd core && ./gradlew :app:cli:shadowJar
bash dist/install.sh           # drops the fat JAR under ~/.local/lib/unidrive/
systemctl --user enable --now unidrive.service
journalctl --user -u unidrive.service -f
```

The daemon log lives at `~/.local/share/unidrive/unidrive.log` (also surfaced via `journalctl --user -u unidrive.service`). For a quick triage pass: `scripts/dev/log-watch.sh --summary`.

Live-integration tests (`UNIDRIVE_INTEGRATION_TESTS=true`) need OAuth credentials. Export `UNIDRIVE_TEST_ACCESS_TOKEN` and run the test; otherwise `assumeTrue` skips cleanly. `LiveGraphIntegrationTest` at `core/providers/onedrive/src/test/kotlin/org/krost/unidrive/onedrive/LiveGraphIntegrationTest.kt` is the template.

## MCP servers

* **Full restarts required.** MCP server config changes only take effect on a true process restart. `/exit` inside a session does not reload the MCP registry. You must fully exit and relaunch.
* **Mind the environment.** Claude Code and Claude Desktop have separate MCP config locations. Confirm which one is running before editing `settings.json`.

## Commit etiquette

* Conventional Commits style — see recent `git log` for examples.
* One BACKLOG item per commit. The BACKLOG → CLOSED move lands in the same commit as the code change.
* No `UD-###` references in new commits, file names, or document body — describe work, not tickets.
* **Split commits cleanly.** Stage hunks explicitly (`git add -p`) rather than `git add .` when working across mixed concerns (e.g., docs vs. code vs. deletions).

## Design constraints (not tickets)

Some constraints bind only when future work happens — they have no current actionable item, but they must not be silently forgotten. File them in the *Design constraints* section near the bottom of `BACKLOG.md`, one per constraint: the rule, the file:line anchor it binds, and the trigger condition. Don't put them in the main BACKLOG tables — work-down readers shouldn't encounter them as tickets.

## What not to do

* **Don't reintroduce removed providers.** s3, sftp, webdav, rclone, localfs were cut during the slim phase and stay cut. The two-provider line is in the Hard rules.
* **Don't grow the daemon to host a UI tier.** The new platform surfaces (Windows desktop, Android, Linux UI) are *separate* apps that consume the core. They don't live inside `core/app/`. Bringing a UI surface back as a module of the daemon is the failure mode the multi-platform ADR is shaped to prevent.
* **Don't put human-facing strings, platform-specific APIs, or user interaction in engine code.** The engine (`core/app/{sync,sync-tracking,core,config,providers/*}`) emits structured outcomes; clients render them. See [`docs/adr/core-app-contract.md`](docs/adr/core-app-contract.md) for the boundary and the split for hard cases.
* **Don't rename files to add or restore dates / IDs / versions.**
* **Don't add docstrings, KDoc, or block comments where the existing code has none.** The code is the spec.
* **Don't open PRs that combine core work with platform-surface work** when those surfaces eventually exist; keep each surface focused.
* **Ask before deleting things you don't recognize.** Unfamiliar files, scripts, branches, or config sections may be in-progress work or load-bearing in a way that isn't obvious. Investigate or ask; don't sweep.

## Backlog discipline in one line

If it isn't in `BACKLOG.md`, it isn't going to happen. Add it or drop it.
