# Every documented CLI command needs a matrix row before docs ship

When you're writing user-facing documentation for the CLI, every
fenced shell command in the doc must correspond to a passing row in
[`docs/dev/cli-verification-matrix.md`](cli-verification-matrix.md).
If the matrix doesn't already cover it, add a row before committing
the doc. Don't rely on memory of "what feels like a command this CLI
should have" — the picocli `@Command` / `@Option` declarations are
the source of truth.

## Worked failure (UD-708 / PR #39, 2026-05-16)

PR #39's `docs/MCP-USER-GUIDE.md` shipped four CLI commands that
didn't exist in the codebase, written from memory of "what an MCP
user guide should tell people":

```bash
unidrive profile add --name X --type Y --sync-path Z    # picocli rejects --name (interactive wizard)
unidrive auth begin --profile X                         # no `begin` subcommand
unidrive auth complete --profile X                      # no `complete` subcommand
unidrive identity --profile X                           # no `identity` subcommand at all
```

Three caught by Codex review on the PR, one by manual audit after.
Author memory had conflated the MCP tool surface
(`unidrive_auth_begin`, `unidrive_auth_complete`, `unidrive_identity`,
`unidrive_profile_add` — all real) with parallel CLI subcommands
(none of which exist). The CLI doesn't have a two-phase auth or an
`identity` subcommand; `profile add` is an interactive wizard.

Worked-example commits: PR #39 commits `8b20377` (introduces bugs)
→ `9c11c54` (fixes all four after Codex).

## Why it bit

The MCP surface and CLI surface look superficially similar — same
verbs in the same product. The MCP exposes 23 tools named after CLI
verbs. The reasonable but wrong assumption is they parallel each
other 1:1. They don't:

- `unidrive_identity` (MCP) has no CLI sibling. The CLI uses `status`
  / `profile list` / `quota` for who-am-I.
- `unidrive_auth_begin` + `unidrive_auth_complete` (MCP, two-phase)
  collapses to a single blocking `unidrive -p X auth [--device-code]`
  on the CLI.
- `unidrive_profile_add` (MCP, args-based) vs `unidrive profile add`
  (CLI, interactive wizard).

This drift is the cost of separately-evolving surfaces. The
verification matrix locks the CLI surface so the next doc PR can be
cross-checked mechanically.

## How to apply

- **Before writing CLI commands into any user-facing doc**, run
  `python scripts/dev/verify-cli.py --filter <command>` to confirm
  the form you're about to document actually parses. The runner
  drives every invocation against an ephemeral sandbox; a passing
  row means the command compiles past picocli.
- **For every fenced shell block in a new doc**, either add a
  matching row to `scripts/dev/verify-cli.py` or confirm the doc
  command matches an existing row's argv.
- **Regression rows** (labeled `regr-*` in the matrix) assert that
  *fabricated* commands return picocli usage errors. When fixing a
  doc bug like UD-708's, add a regression row pinning the absence of
  the bad command — so future docs that re-introduce it fail the
  matrix.
- **CI integration** (future):
  `python scripts/dev/verify-cli.py && git diff --exit-code
  docs/dev/cli-verification-matrix.md` should be a pre-merge gate
  once the matrix is broad enough.

## Defensive checklist

- [ ] Every `unidrive ...` shell block in `docs/` matches an
      existing matrix row's `argv` (grep the matrix file).
- [ ] When introducing a new doc command, add a row to
      `scripts/dev/verify-cli.py` and re-generate the snapshot in
      the same commit.
- [ ] When *removing* a CLI command, add a `regr-*` row pinning that
      it now errors — so docs that haven't caught up don't silently
      regress.
- [ ] The matrix snapshot
      (`docs/dev/cli-verification-matrix.md`) is regenerated and
      committed in every PR that touches `core/app/cli/**.kt`. CI
      should fail on snapshot drift.
