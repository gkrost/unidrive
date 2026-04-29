# Contributing to UniDrive

Thanks for considering a contribution. UniDrive is in early-stage public
preview (v0.0.1) — APIs, IPC, and on-disk layout are still moving. Expect
breakage; expect strong opinions about provenance and trust.

## Before you open a pull request

1. **Discuss first for non-trivial work.** File an issue describing the
   problem and the proposed direction before writing more than a few hours
   of code. UniDrive favours small, reviewable changes; surprise refactors
   are usually rejected on cost-of-review grounds, not on merit.
2. **Sign your commits** (`git commit -s`). The Developer Certificate of
   Origin ([DCO](https://developercertificate.org/)) applies — by signing
   off you assert you have the right to submit the contribution under
   Apache 2.0.
3. **Match the backlog discipline.** Every change references a `UD-###`
   ticket. Run `python scripts/dev/backlog.py next-id <category>` to
   allocate one and `… file …` to seed the entry. The contract is
   [`docs/AGENT-SYNC.md`](docs/AGENT-SYNC.md); the toolchain is
   [`docs/dev/TOOLS.md`](docs/dev/TOOLS.md).
4. **Build and test locally.**
   ```bash
   cd core && ./gradlew build test
   ./scripts/smoke.sh
   ```
   `ktlintCheck` runs as part of `check`; baselines are committed at
   `<project>/config/ktlint/baseline.xml`. After non-trivial Kotlin edits
   run `scripts/dev/ktlint-sync.sh` so line-anchored baseline entries
   don't drift.

## Code style and commit conventions

- [`docs/dev/CODE-STYLE.md`](docs/dev/CODE-STYLE.md) — Kotlin style,
  UTF-8/LF discipline, conventional-commit format.
- One `UD-###` per commit scope. Backlog moves (BACKLOG → CLOSED) commit
  separately from the code change, with `resolved_by: commit <sha>` pointing
  at the immediately-preceding code commit.
- Don't commit `__pycache__/`, `.venv-mcps/`, `build/`, or any generated
  artefact. The `.gitignore` covers the common cases.

## Security issues

**Do not** open a public issue for security-sensitive reports. See
[`SECURITY.md`](SECURITY.md) for the disclosure process.

## Project name

"UniDrive" is **not** a registered trademark — see [`NAMING.md`](NAMING.md)
for the full policy, the prior automotive-class registration that does
not block software use, and the (permissive) rules for forks and
derivative works. Apache 2.0 §4 attribution applies regardless of
naming.

## Architectural docs (read before bigger changes)

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — sync algorithm, state
  DB, auth flows.
- [`docs/SPECS.md`](docs/SPECS.md) — normative intent catalog with
  file:line anchors and per-claim verification status.
- [`docs/adr/`](docs/adr/) — accepted architectural decisions; see
  ADR-0001 (monorepo) and ADR-0007 (versioning) for the structural
  ground rules.
- [`docs/dev/lessons/`](docs/dev/lessons/) — six short notes on
  things the previous maintainer burned a session on
  (jar-hotswap, ktlint baseline drift, Kotlin trailing-lambda param
  ordering, MDC inside suspend functions,
  verify-before-narrative, halted-agent WIP leaks). Read at session
  start — saves an hour each.

## What is and isn't in the tree (gitignore policy)

To make a fork-and-go experience possible for any agent (Claude Code,
Opencode, aider, Cursor, …), the tree includes everything an outside
contributor needs to pick up where the maintainer left off:

- The full `core/` source, build, and Gradle lockfiles.
- The `docs/` tree — architecture, security, ADRs, backlog,
  changelog, dev tooling, lessons.
- `scripts/dev/` — backlog.py, log-watch.sh, ktlint-sync.sh,
  oauth-mcp/, gradle-mcp/, etc. (their `.venv*/` directories are
  intentionally **not** included; each contributor creates their
  own.)
- `.claude/skills/` — project-scoped skills (`challenge-test-assertion`,
  `unidrive-log-anomalies`).
- `.semgrep/`, `.gitleaks.toml`, `.semgrep.yml`, `.trivyignore` —
  scanner configs.

The following stay gitignored on purpose:

| Path | Why it's out |
|---|---|
| `.claude/settings.local.json` | Per-developer Claude Code config (model choice, hook settings, sometimes API keys). Each contributor's is different; nothing useful to share. |
| `.claude/scheduled_tasks.lock` | Runtime lock for Claude Code's scheduled-task feature. |
| `.claude/worktrees/` | Per-developer agent worktree state — uncommitted, in-flight, often broken mid-stream. Sweeping these into a tree-level commit is exactly the failure mode [`docs/dev/lessons/halted-agent-leaks.md`](docs/dev/lessons/halted-agent-leaks.md) documents. |
| `**/__pycache__/`, `**/.venv/`, `**/.venv-mcps/` | Python venv / bytecode artefacts under `scripts/dev/*/`. Each contributor regenerates. |
| `**/build/`, `**/.gradle/`, `**/.kotlin/` | Gradle / Kotlin build outputs. |
| `docs/internal/` | Maintainer's pre-publication notes (competitive briefs, financial planning) — not part of the OSS contract. |
| `_recovered/` | Maintainer's local doc-reference cache; not part of the OSS contract. |

The maintainer's per-session memory under
`~/.claude/projects/<machine-encoded-path>/memory/` is **outside** the
repo entirely — that's where things like "my dev laptop's SSH access
details", "this Windows machine's MSIX sandbox quirk", or "a third
hypothesis I'm still chewing on for UD-219" live. Universal-
applicability lessons are the ones that get sanitized and committed
into [`docs/dev/lessons/`](docs/dev/lessons/).
