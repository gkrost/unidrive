# Developer tools (for humans and agents)

Small scripts under `scripts/dev/` that automate the plumbing around this
repo. Every one exists because a previous session burned ≥ 5 tool calls /
minutes on the manual version. See the 2026-04-19 session handover for
the motivating post-mortem.

Normative rules these tools enforce live in
[CODE-STYLE.md](CODE-STYLE.md).

## `ktlint-sync.sh` — one-shot format + baseline refresh

```bash
scripts/dev/ktlint-sync.sh              # format + regen baselines if still red
scripts/dev/ktlint-sync.sh --check-only # dry-run: just ktlintCheck
```

After a non-trivial edit to a `.kt` file near an existing baseline
entry, line numbers drift and ktlint re-flags "already-covered"
violations. This script runs `ktlintFormat` in every composite, falls
back to `ktlintGenerateBaseline` when formatting can't resolve a rule
(typically `max-line-length`), and prints the baseline + source diff so
the reviewer can tell which lines actually shifted.

## `log-watch.sh` — tail and group `unidrive.log`

```bash
scripts/dev/log-watch.sh               # follow live, anomalies only
scripts/dev/log-watch.sh --last 500    # print last 500 raw lines
scripts/dev/log-watch.sh --summary     # WARN/ERROR/429/JWT/TLS counts
scripts/dev/log-watch.sh --anomalies   # full log, filtered to non-noise
```

Default log path is `%LOCALAPPDATA%\unidrive\unidrive.log`; override via
`UNIDRIVE_LOG`. Noise filter drops `Delta: N items` progress and IPC
disconnect lines. Anomaly filter matches `WARN | ERROR | Exception |
throttled | Download failed | handshake | JWT` — the categories that
actually need a human (or agent) to look.

## `backlog.py` — mutate BACKLOG.md / CLOSED.md safely

```bash
# Print the next free UD-xxx in a category
python scripts/dev/backlog.py next-id core
python scripts/dev/backlog.py next-id providers
python scripts/dev/backlog.py next-id tooling

# Show a ticket (searches BACKLOG.md then CLOSED.md)
python scripts/dev/backlog.py show UD-232

# File a new ticket
python scripts/dev/backlog.py file \
  --id UD-234 \
  --title "Short noun phrase" \
  --category core \
  --priority medium \
  --effort S \
  --code-refs path/to/file.kt other/file.kt \
  --body-file /tmp/ticket-body.md

# Close a ticket (moves frontmatter from BACKLOG to CLOSED with status/closed/resolved_by)
python scripts/dev/backlog.py close UD-234 \
  --commit 9e6de34 \
  --note "One-paragraph prose about what the fix did."
```

Category → ID-range mapping follows `docs/AGENT-SYNC.md`. The script
refuses to file an ID that already exists (in either file) and refuses
to close one that isn't in BACKLOG.md. On close, the `status: open`
line is replaced with `status: closed / closed: <today> / resolved_by:
commit <sha>. <note>` — the exact pattern the hand-rolled CLOSED
entries follow. Safe to run against a dirty working tree; changes are
shown by `git diff` before you commit.

## `oauth-mcp/` — scoped short-lived OneDrive token issuer

Python MCP server under `scripts/dev/oauth-mcp/` that lets a Claude
Code session mint a read-only (or opt-in write) access token against
the user's already-authenticated unidrive profile, without ever
handling the refresh token itself.

Runs on the user's native machine (outside the MSIX sandbox — the
sandbox hides `%APPDATA%\unidrive\<profile>\token.json` from
Python). Setup + wire-up instructions in
[scripts/dev/oauth-mcp/README.md](../../scripts/dev/oauth-mcp/README.md);
design context in [oauth-test-injection.md](oauth-test-injection.md).

Once registered, the agent has two tools:

- `probe()` — lists profiles with a token.json on disk. Never mints.
- `grant_token(profile, scope="read"|"write", ttl_seconds=3600)` —
  refreshes and returns an access token. Caller pipes it into
  `UNIDRIVE_TEST_ACCESS_TOKEN` and runs `./gradlew :providers:onedrive:test`.

Bypasses the 24-hour live-test feedback loop that motivated this whole
effort. Start with `python scripts/dev/oauth-mcp/smoke.py <profile>`
to verify the refresh works end-to-end before wiring the MCP.

---

## `pre-commit/scope-check.sh` — commit-scope railguards

```bash
scripts/dev/pre-commit/install.sh     # one-time: wire core.hooksPath
git config --unset core.hooksPath     # uninstall
```

Enforces the Conventional-Commits scope conventions from
[CODE-STYLE.md](CODE-STYLE.md) §9:

- `chore(ktlint)` may only stage `**/config/ktlint/baseline.xml`.
- `chore(deps)` may only stage Gradle dep files.
- `docs(backlog)` may only stage `docs/backlog/*.md`.
- `docs(handover)` may only stage `handover.md`.
- `fix(UD-###)` / `feat(UD-###)` — warns when a referenced UD-### is
  missing from BACKLOG.md / CLOSED.md.

Bypass with `git commit --no-verify` for deliberate cross-cutting
commits. Catches the halted-agent WIP-leakage class (see memory
`feedback_halted_agent_leaks`).

Companion `backlog.py audit-commits` walks the whole branch and warns
post-hoc when a landed commit's scope disagrees with its touched paths:

```bash
python scripts/dev/backlog.py audit-commits --since main
```

`ktlint-sync.sh --module :app:cli` now also refuses to sweep when
uncommitted `.kt`/`.kts` files exist outside the scoped module (set
`KTLINT_SYNC_SKIP_PREFLIGHT=1` to override).

---

See also `~/.claude/projects/…/memory/MEMORY.md` for reusable
gotchas the agent has picked up across sessions (MSIX sandbox, jar hot-
swap, ktlint baseline drift, Kotlin default-param ordering).
