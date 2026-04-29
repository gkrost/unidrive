# Notes for Claude Code (and any other agent)

Project-scoped guidance for any agent (Claude Code, Claude Desktop
sub-agent, Opencode, aider, Cursor, …) attached to this repo. You're
continuing work on the **unidrive** project: a multi-provider
cloud-sync client for Linux. Kotlin monorepo at `core/` — CLI + MCP
server + sync engine + provider adapters.

## Quick start for a new session

1. Read this file (you're already doing it).
2. Skim [`docs/dev/lessons/`](docs/dev/lessons/) — six short notes on
   the rakes contributors keep stepping on (jar hot-swap, ktlint
   baseline drift, Kotlin trailing-lambda ordering, MDC in suspend
   functions, verify-before-narrative, halted-agent WIP leaks).
3. For backlog work, use `python scripts/dev/backlog.py` — never
   hand-edit `docs/backlog/BACKLOG.md` / `CLOSED.md`. Per-topic
   detail in §"Before you start mutating BACKLOG.md" below.
4. For sync-log triage, invoke the `unidrive-log-anomalies` skill
   (or `bash scripts/dev/log-watch.sh --summary` if your host
   doesn't load skills). See §"Before you read unidrive.log".
5. unidrive uses feature branches, not direct-to-main commits.
   `git branch --show-current` and `git log --oneline main..HEAD`
   show your current state.

The "Before you X" sections below are the per-topic detail. Read
them on demand.

## Before you start mutating BACKLOG.md / CLOSED.md

Use `scripts/dev/backlog.py` instead of hand-editing. It handles ID
allocation, the BACKLOG → CLOSED transform, and the pattern used for
`resolved_by`. Full usage in [docs/dev/TOOLS.md](docs/dev/TOOLS.md).

```bash
python scripts/dev/backlog.py next-id core
python scripts/dev/backlog.py show UD-232
python scripts/dev/backlog.py file --id UD-234 --title "…" --category core \
    --priority medium --effort S --body-file /tmp/body.md
python scripts/dev/backlog.py close UD-234 --commit 9e6de34 --note "…"
```

The script refuses duplicate IDs and refuses to close a ticket that
isn't in BACKLOG.md — cheap guard-rails that are easy to lose by hand.

## Before you read `unidrive.log`

**At session start** invoke the `unidrive-log-anomalies` skill — it runs
the summary pass, compares counts against the session-close baseline in
the skill file, and hands you a decision rubric for what to do with each
signal class (retry storm, permanent failure, malformed JWT, TLS
handshake flake, token refresh race).

On-demand, the same helper as a shell one-liner:

```bash
scripts/dev/log-watch.sh --summary     # 10-second overview of recent pain
scripts/dev/log-watch.sh --anomalies   # non-noise lines only
scripts/dev/log-watch.sh               # live follow (anomalies)
```

The log can reach 10+ MB with `Delta: N items` progress and IPC
disconnect noise. Filtering first saves a lot of context.

## Before you rewire ktlint

After a non-trivial `.kt` edit, run `scripts/dev/ktlint-sync.sh`
before committing. Baselines are line-number-anchored; edits above a
covered violation re-surface it. See
[docs/dev/lessons/ktlint-baseline-drift.md](docs/dev/lessons/ktlint-baseline-drift.md).

For a module-scoped change, use `--module <gradle-path>` to avoid
dragging unrelated modules into the diff, e.g.
`scripts/dev/ktlint-sync.sh --module :app:cli`.

## Build sanity

- Gradle composite: `core/` (Kotlin app). Single `./gradlew` from `core/`.
- `./gradlew build` from `core/` runs unit tests + ktlint + jacoco. ~40
  s warm daemon, ~60 s cold.
- Live-integration tests (`UNIDRIVE_INTEGRATION_TESTS=true`) need
  OAuth credentials. If the user has set up the
  [`unidrive-test-oauth` MCP](scripts/dev/oauth-mcp/README.md),
  call `mcp__unidrive-test-oauth__grant_token(profile="...",
  scope="read")` to mint a short-lived token, export it as
  `UNIDRIVE_TEST_ACCESS_TOKEN`, and run the test. Otherwise
  `assumeTrue` skips cleanly.
- `LiveGraphIntegrationTest` at
  [core/providers/onedrive/src/test/kotlin/org/krost/unidrive/onedrive/LiveGraphIntegrationTest.kt](core/providers/onedrive/src/test/kotlin/org/krost/unidrive/onedrive/LiveGraphIntegrationTest.kt)
  is the canonical consumer of `UNIDRIVE_TEST_ACCESS_TOKEN`: one `getDrive` smoke test + a
  parallel-load test that exercises `ThrottleBudget` against real Graph. Use it as the template
  when adding more live tests.

## Commit etiquette

- One `UD-###` per commit scope. Conventional Commits format — see
  recent `git log` for examples.
- Always commit the ticket move (BACKLOG → CLOSED) separately from
  the code change, with `resolved_by: commit <sha>` pointing at the
  immediately-preceding code commit.
- Never hot-swap a `.jar` on a running JVM. On Windows the
  classloader corrupts mid-execution; on Linux it's friendlier but
  still not safe. Always kill the daemon first, then copy. See
  [docs/dev/lessons/jar-hotswap-windows.md](docs/dev/lessons/jar-hotswap-windows.md).

## Everything else

Backlog lives at [docs/backlog/BACKLOG.md](docs/backlog/BACKLOG.md);
closed archive at [docs/backlog/CLOSED.md](docs/backlog/CLOSED.md). The
contract is [docs/AGENT-SYNC.md](docs/AGENT-SYNC.md) — read it once
before filing new tickets. Code style and commit conventions live in
[docs/dev/CODE-STYLE.md](docs/dev/CODE-STYLE.md). Architecture high-level
in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md); intent vs code audit in
[docs/SPECS.md](docs/SPECS.md).
