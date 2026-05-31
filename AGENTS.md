# Agent instructions

unidrive is a multi-platform cloud-sync core (Linux daemon; engine for Windows/Android/Linux UI). See [multi-platform.md](docs/adr/multi-platform.md). Read this before changing anything.

## Hard rules
- **Smoke tests are the live-integration target** (5+5+2=12). Add when coverage gaps emerge. Structural-safety invariants earn their keep; tracking-set lemma is the example. Unit tests with intent. Per-test pruning (ask).
- **Doc surface is bounded.** Allowed: this file, `README.md`, `BACKLOG.md`, `CLOSED.md`, `BOOSTERS.md`, ADRs (`docs/adr/`), audits (`docs/audits/`), specs (`docs/dev/specs/`), plans (`docs/dev/plans/`), per-module `README.md`. Closing BACKLOG entry cites spec path.
- **No IDs, dates, or version numbers** in commits, filenames, or docs.
- **Abstractions earn their keep.** Need named justification (platform-surface need or structural-safety property) + one consumer. Don't preemptively factor; don't refuse on principle. Tracking-set is the explicit exception.
- **No CI policing.** `./gradlew check` is the gate. Exception: security scanning (CodeQL) may run on push/PR/schedule, but stays advisory — it MUST NOT be a required merge check; `./gradlew check` remains the sole gate.
- **Never hot-swap a `.jar` on a running JVM.** Stop daemon first.

## How to work
1. Read github issues
2. Read three nearby source files before writing.
3. Make change. Run `./gradlew check`. Iterate.
4. One commit, one item.

## Verification
- Verify load-bearing claims with full pass, not summaries.
- Red flags → re-verify whole artifact, not just called-out line.

## What lives where
- `core/providers/{internxt,onedrive}/` — cloud clients
- `core/app/{core,sync,sync-tracking,cli,config}/` — engine modules
- `docs/audits/` — Internxt notes
- `docs/adr/` — architectural decisions

Future platforms consume core from outside `core/`.

## Build and run locally
```bash
cd core && ./gradlew check     # the only gate
cd core && ./gradlew :app:cli:shadowJar
bash dist/install.sh
systemctl --user enable --now unidrive.service
journalctl --user -u unidrive.service -f
```
Daemon log: `~/.local/share/unidrive/unidrive.log`. Triage: `scripts/dev/log-watch.sh --summary`.
Live-integration tests need `UNIDRIVE_INTEGRATION_TESTS=true` + `UNIDRIVE_TEST_ACCESS_TOKEN`. Template: `LiveGraphIntegrationTest`.

## MCP servers
- Full restarts required. `/exit` doesn't reload registry.
- Claude Code / Desktop have separate config locations.

## Commit etiquette
- Conventional Commits style.
- One BACKLOG per commit; BACKLOG→CLOSED same commit.
- No `UD-###` references.
- Stage hunks explicitly (`git add -p`) for mixed concerns.

## Design constraints
File future-only constraints in BACKLOG.md *Design constraints*: rule, file:line, trigger.

## What not to do
- Don't grow daemon to host UI. Platforms are separate apps.
- Don't put UI strings, platform APIs, or user interaction in engine code.
- Don't rename files to add dates/IDs/versions.
- Don't add docstrings/KDoc where none exist.
- Don't combine core + platform work in same PR.
- Ask before deleting unfamiliar files.

If it isn't in github issues, it isn't going to happen. Add it or drop it.
