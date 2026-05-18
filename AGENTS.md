# Agent instructions

You are working on the slim branch of unidrive. The goal is one clean release shipping **Internxt Drive** and **OneDrive** sync over Linux. Nothing else.

This file is the rulebook for everyone touching the repo — human contributors and LLM agents alike. End-users land on `README.md`; the moment you want to *change* something, you read this file.

## Hard rules

- **No new providers.** Two only.
- **No new tests beyond smoke.** Smoke = "auth works, upload works, download works, delete works, delta reconciles" — 12 tests total (2 sync, 5 internxt, 5 onedrive). Add a smoke test when an existing one no longer covers a path; do not add unit tests speculatively.
- **No new docs at repo or `docs/` level.** Per-module `README.md` files inside `core/providers/<name>/` and `core/app/<module>/` are permitted; they document that module only. The shared doc surface is this file, `README.md`, `BACKLOG.md`, `CLOSED.md`, `BOOSTERS.md`, plus the existing ADRs under `docs/adr/` and audits under `docs/audits/`.
- **No IDs, dates, or version numbers** in commit messages, file names, or document content. Describe what a thing is, not when it was filed or which release ships it.
- **No new abstractions.** Two implementations of the provider SPI is not enough to justify another layer. If a refactor is needed, do it inside the existing SPI shape.
- **No CI policing.** `./gradlew check` is the gate. No semgrep, gitleaks, codecov, trivy, ktlint baselines, version-drift jobs.
- **Never hot-swap a `.jar` on a running JVM.** The classloader corrupts mid-execution. Stop the daemon (`systemctl --user stop unidrive.service`), then copy.

## How to work

1. Read the top of `BACKLOG.md`. Pick the first item that isn't blocked.
2. Read three nearby source files before writing. The existing patterns are the style guide.
3. Make the change. Run `./gradlew check`. Iterate.
4. Move the item from `BACKLOG.md` to `CLOSED.md` in the same commit that lands the work. One commit, one item.
5. If you discover a new piece of work, append it to `BACKLOG.md` under the matching priority section in one line.

## What lives where

- `core/providers/internxt/` — Internxt client (see its `README.md` for client notes, robustness model, known limits)
- `core/providers/onedrive/` — OneDrive client (ditto)
- `core/app/core/` — auth framework, HTTP, error handling, credential vault, shared `org.krost.unidrive.{http,auth,io}` helpers
- `core/app/sync/` — reconciliation engine
- `core/app/cli/` — CLI entry point
- `core/app/config/` — TOML config + profile management
- `BOOSTERS.md` — what each provider's API offers and what to adopt next
- `docs/audits/` — reverse-engineering notes for Internxt; keep, don't expand without reason
- `docs/adr/` — the three surviving decisions that explain the shape of the current tree

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

## Commit etiquette

- Conventional Commits style — see recent `git log` for examples.
- One BACKLOG item per commit. The BACKLOG → CLOSED move lands in the same commit as the code change.
- No `UD-###` references in new commits, file names, or document body — the slim branch describes work, not tickets.

## What not to do

- Don't reintroduce removed providers, modules, scripts, or workflows.
- Don't write planning documents (`plans/`, `specs/`, `adr/` beyond what already exists).
- Don't rename files to add or restore dates / IDs / versions.
- Don't add docstrings, KDoc, or block comments where the existing code has none. The code is the spec.
- Don't open PRs that combine slim-branch work with feature work; keep this branch focused.
- **Ask before deleting things you don't recognize.** Unfamiliar files, scripts, branches, or config sections may be in-progress work or load-bearing in a way that isn't obvious. Investigate or ask; don't sweep.

## Backlog discipline in one line

If it isn't in `BACKLOG.md`, it isn't going to happen. Add it or drop it.
