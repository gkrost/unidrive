# Session review — MCP user guide + CLI verification matrix (2026-05-16)

> Audience: an external reviewer (peer AI agent, e.g. opencode, or a
> human co-maintainer) auditing the work that landed across PRs #38,
> #39, #40 on 2026-05-16. Self-contained brief so the reviewer doesn't
> need to reconstruct context from the PR threads. Once the issues
> below are dispositioned (or new ones raised), this file can be
> deleted or moved under `docs/dev/reviews/`.

## 0. tl;dr

Three PRs landed on 2026-05-16 starting from a single user prompt
("test drive the unidrive MCP (if available: retrieve internxt
quota)"):

| PR | Ticket | Scope | Status |
|---|---|---|---|
| [#38](https://github.com/gkrost/unidrive/pull/38) | UD-758 | MCP protocol-version negotiation — server rejected non-`2024-11-05` `initialize` requests with INVALID_PARAMS, breaking Claude Code 2.1.143+ | merged |
| [#39](https://github.com/gkrost/unidrive/pull/39) | UD-708, UD-283 | `docs/MCP-USER-GUIDE.md` (operator + LLM-user guide); SPECS.md §2.2 fix for stale `profile` tool-arg behaviour claim | merged |
| [#40](https://github.com/gkrost/unidrive/pull/40) | UD-776 | `scripts/dev/verify-cli.py` + golden matrix snapshot — empirical CLI surface verifier driven by 132 cases, 127 PASS / 0 FAIL / 5 SKIP at first run | merged |

This PR is **not** a code change. It files four follow-up tickets
surfaced by the work, fixes residual doc drift caught by an audit
sweep, adds a supersession note to one historical record, and packages
the whole session for peer review.

## 1. Code ↔ docs ↔ knowledge sync — what was checked

| Surface | Audit result |
|---|---|
| `docs/MCP-USER-GUIDE.md` — paths to deployed jar / launcher (Linux + Windows) | ✅ Match `core/app/mcp/build.gradle.kts` `deployLinux` / `deployWindows` exactly. |
| `docs/MCP-USER-GUIDE.md` — CLI command examples (§3.1, §4.1, §4.3) | ✅ Match picocli `@Command`/`@Option` declarations after PR #39's fix-up commit `9c11c54`. Verified by `verify-cli.py` regression rows (UD-776 PR #40). |
| `docs/SPECS.md §2.2` — `profile` tool-arg behaviour | ✅ Matches `ProfileArgValidator.profileMismatchError` (after UD-283 fix in PR #39). |
| `docs/SPECS.md §2.2` — tool count (23) | ✅ Matches `Main.kt:36-62` (15 user-facing + 8 admin UD-216). |
| `docs/CHANGELOG.md` `[Unreleased]` | ✅ All three of UD-758 / UD-708 / UD-283 / UD-776 represented. |
| `docs/dev/lessons/README.md` index | ✅ Both new lessons (mcp-protocol-version-negotiation, cli-surface-verify-before-doc) indexed. |
| `core/app/mcp/README.md` module pointer | ✅ Points at the user guide and the lessons. |
| `docs/dev/cli-verification-matrix.md` golden snapshot | ✅ 127 PASS / 0 FAIL / 5 SKIP, regression rows pinning the four PR #39 fabrications as picocli errors. |
| `docs/dev/manual-test-checklist.md` | **❌ Drift found — fixed in this PR.** §1 referenced `unidrive profile create --provider <p>` (doesn't exist; CLI is interactive wizard `unidrive profile add`) and `unidrive identity` (MCP-only); §10 referenced `unidrive auth begin / auth complete` (no two-phase CLI). Now corrected. |
| `docs/backlog/CLOSED.md` UD-708 entry | **⚠ Historical record contained pre-fix JSON example with wrong jar path** (`unidrive-mcp-all.jar` in `~/.local/bin/`, neither produced by the build). Codex flagged this in the original UD-708 filing PR (#33). The shipped doc at `MCP-USER-GUIDE.md §5.2` has the correct path; this PR adds a supersession note next to the historical record so a copy-paster sees the redirect immediately. AGENT-SYNC.md's append-only convention preserved — the original block is untouched. |

## 2. Codex (chatgpt-codex-connector) review responses

Five P2-rated findings across the three PRs. Disposition:

### PR #38 (UD-758) — no Codex findings.

### PR #39 (UD-708)

| # | Finding | Disposition |
|---|---|---|
| F1 | `unidrive profile add --name X --type Y --sync-path Z` doesn't exist — `ProfileAddCommand` is an interactive wizard with no `@Option`s | ✅ Fixed in commit `9c11c54` — guide §3 rewritten to show the wizard + point at MCP `unidrive_profile_add` for scripted setups. Now pinned by matrix row `regr-profile-add-rejects-name-flag`. |
| F2 | `unidrive auth begin --profile X` / `auth complete --profile X` don't exist — `AuthCommand` is leaf-level with only `-d/--device-code`, profile is top-level `-p/--provider` | ✅ Fixed in `9c11c54` — §4.1 corrected to `unidrive -p <profile> auth [--device-code]`; called out explicitly that two-phase begin/complete is MCP-only. Pinned by matrix rows `regr-no-auth-begin-subcommand`, `regr-no-auth-complete-subcommand`. |
| F3 | `unidrive_sync` documented as fire-and-forget — actually synchronous via `runBlocking { engine.syncOnce(...) }` in `SyncTool.kt:98`, blocks until completion | ✅ Fixed in `9c11c54` — §0, §7, §9, §9.1 all rewritten; explicit that `unidrive_watch_events` watches the *separate* `unidrive sync --watch` daemon, not the MCP-invoked sync. |
| F4 (filing PR #33) | `unidrive-mcp-all.jar` in `~/.local/bin/` (the JSON example in the original UD-708 body) doesn't match what `:app:mcp:deploy` produces | ⚠ Partially addressed: the SHIPPED guide (`MCP-USER-GUIDE.md §5.2`) has correct paths verified against `build.gradle.kts:202-215`. The HISTORICAL ticket body in CLOSED.md has the old wrong example. This PR adds a supersession note. |

### PR #40 (UD-776)

| # | Finding | Disposition |
|---|---|---|
| F5 | Setting `HOME`/`USERPROFILE` doesn't override Java's `System.getProperty("user.home")`; `LOCALAPPDATA`/`XDG_*` not overridden either, so logback writes leak to the operator's real `unidrive.log` | ✅ Fixed in commit `10cc364` — `run_case` now passes `-Duser.home=<sandbox>` as a JVM property and overrides `LOCALAPPDATA`, `APPDATA`, all four `XDG_*` vars. Verified by checking `~/AppData/Local/unidrive/` for an `unidrive.log` after a full 132-case run — none present (only the pre-existing `unidrive-mcp.log` from earlier UD-758 work). |
| F6 | `--filter` silently overwrites the canonical matrix snapshot when `--out` is left default | ✅ Fixed in `10cc364` — added `CANONICAL_MATRIX_PATH` constant and a guard that refuses to run when `--filter` is set and `--out` equals the default, suggesting `--out /tmp/verify-cli-scratch.md` instead. |

I missed one bug Codex didn't catch in PR #39: `unidrive identity --profile X` (also fabricated; no `identity` CLI subcommand). Caught by manual audit and fixed in `9c11c54`; pinned by matrix rows `regr-no-identity-subcommand` and `regr-no-identity-with-profile`.

## 3. New backlog items filed in this PR

| ID | Title | Why filed |
|---|---|---|
| [UD-777](../backlog/BACKLOG.md#ud-777) | CI pre-merge gate for verify-cli.py matrix | UD-776 ships a runner + snapshot but no automation. Without a CI gate the matrix decays — exactly the "remember to do it" failure mode UD-776 was supposed to eliminate. tooling / medium / S. |
| [UD-778](../backlog/BACKLOG.md#ud-778) | MCP tool-surface verification matrix runner | UD-776 covers the CLI; the same doc-drift bug class can recur on the MCP side (PR #39 was MCP-side docs!). Parallel harness needed, ideally reuses the sandbox hardening from UD-776. tooling / medium / M. |
| [UD-409](../backlog/BACKLOG.md#ud-409) | CLI: `-c` on nonexistent dir exits silently | Matrix row 007 surfaced that `unidrive -c /no/such/dir status` exits 1 with empty stderr. Operator UX gap; small fix. cli / low / S. |
| [UD-410](../backlog/BACKLOG.md#ud-410) | CLI: document canonical `-p <profile> <subcommand>` ordering | PR #39 used `--profile X` as if it were a subcommand option; neither the CLI's own help text nor any doc states the canonical top-level placement. Filing locks in the discipline. cli / low / S. |

## 4. Patterns observed worth discussing (not yet filed)

### 4.1 Closed-ticket bodies as accidental copy-paste sources

`CLOSED.md` is append-only by AGENT-SYNC convention, and most readers
treat its content as authoritative. But the body of a ticket filed
BEFORE its resolution often contains placeholder examples that the
shipped fix replaces. UD-708's bad JSON example is the smoking gun;
similar pre-fix-but-now-misleading text probably exists in older
closed tickets we haven't audited.

**Options:**

A. **Supersession notes** (what I did for UD-708). Cheap, preserves
   history, but accumulates clutter and depends on the reviewer noticing
   the note before the code block.
B. **Strikethrough the pre-fix example** with `~~...~~` markdown. Visual,
   but doesn't render in all tooling and obscures the original.
C. **Convention shift**: when closing a ticket, the closing commit strips
   any code/config examples in the body that the shipped fix replaced.
   Requires updating AGENT-SYNC.md.
D. **Per-ticket files** (`docs/backlog/closed/UD-708.md` etc.) so
   stale-body issues are isolated and a `*-superseded` suffix marks them
   visibly. Large refactor; unlocks other things (parallel-PR friction —
   see 4.2 — also goes away).

Lean toward (C) for new closures + (A) opportunistically for old ones,
but flagging for opencode's call.

### 4.2 CHANGELOG.md + CLOSED.md as conflict-prone files

PRs #39 and #40 both conflicted on these two files during PR #40's
rebase, for the same boring reason: both append at the top of
`### Added` / the bottom of `CLOSED.md`. With three PRs in a 6-hour
window that's manageable; with twenty it's not.

Mitigations (no ticket — gathering opinion first):

- **Per-ticket files** (see 4.1.D) eliminates the bottom-of-CLOSED
  collision entirely.
- **Per-PR CHANGELOG fragments** (à la `towncrier`) deferred until
  release time, eliminates the top-of-Added collision.
- **Status quo + git rerere** for the conflict-resolving developer.

### 4.3 The matrix's `regr-*` rows are the headline value

The bulk of the 132 matrix rows assert "this works" (`-h`, no-args,
happy path). The five `regr-*` rows assert "this DOESN'T exist /
errors" — pinning the four PR #39 fabrications as picocli usage
errors. Those are the rows that catch the actual bug class going
forward. Generalising: every doc-fix PR should add a corresponding
regression row pinning the bad form as still-erroring.

The lesson at `docs/dev/lessons/cli-surface-verify-before-doc.md`
captures this discipline, but the matrix could go further: emit a
`regr-*` row count in the summary header so reviewers can tell at a
glance how much "absence-of-thing" coverage exists.

### 4.4 `verify-cli.py` design choices worth a second opinion

- **Python stdlib only.** Chosen for portability + matches existing
  `scripts/dev/` toolchain. Alternative was Kotlin under
  `:app:cli:test` — tighter to the build but loses end-to-end
  fidelity (in-process picocli ≠ subprocess picocli). Acceptable
  call, but flagging.
- **`subprocess.run` per case + JVM startup per case (~2-3 s).**
  Wall clock 70 s on 6 workers for 132 cases. Acceptable for
  manual + CI runs, painful for tight iteration. AppCDS / CRaC
  could halve it; deferred until pain materialises.
- **Lock-acquiring cases tagged `serial=True`.** Workaround for
  per-profile `.lock` collision when sync/refresh/apply/auth run
  in parallel on the same profile. Could instead give each case a
  unique profile name (would need ~10 more profiles in the sandbox
  TOML); current choice trades a couple seconds of wall clock for a
  simpler sandbox.
- **No `--update-snapshot` flag.** Current workflow is "run, git
  diff, commit." A `--update-snapshot` flag would be conventional
  but adds magic; deferred.

## 5. Specific asks for opencode review

In rough priority order — least-confident-of-my-own-judgement first:

1. **§4.1 closed-ticket-body discipline.** Is supersession-note (A) or
   convention-shift (C) the right move? AGENT-SYNC.md says append-only;
   the cost of stale examples is real. Strong opinion solicited.
2. **UD-778 scope.** Is "MCP tool-surface verification matrix" the
   right shape, or should it be folded into the existing Docker MCP
   harness (UD-815 — `docker-compose.mcp.yml`)? The latter already
   runs end-to-end sync through MCP; UD-778's matrix would be
   shallower (argument-surface focused). Two harnesses or one?
3. **UD-410 acceptance: should the CLI's `--help` *also* show the
   canonical ordering?** Picocli auto-generates help from
   `@Command.description`; adding ordering guidance there requires
   either a custom usage formatter or editing every subcommand's
   description. Heavy. The lighter path is doc-only.
4. **`verify-cli.py` design choices in §4.4.** Any of those four
   bullets a wrong call? Python vs Kotlin specifically — fidelity
   benefit was the deciding factor, but I might be over-weighting it.
5. **Did I miss any drift?** Audit summary in §1 is grep-based. The
   greps used (`unidrive identity|auth begin|auth complete|...`) are
   narrow; broader search across all `*.md` for "command examples
   that don't match an actual `@Command`" would need the UD-776
   runner itself, which doesn't yet exist for docs.

## 6. Outstanding observations not in any ticket

- **`unidrive -p <bogus>` exit code variance.** Matrix rows for
  `unknown-profile` assert exit=1 with stderr regex `(?i)unknown|not
  found|no.*profile|invalid` — wide because different code paths
  surface the error differently. Worth normalising the error text
  (and exit code) across `status`, `quota`, `ls`, `log`, `conflicts
  list`. Not currently a ticket; flagging.
- **CLI `auth` happy path is wrapped in `parent.acquireProfileLock()`
  ([AuthCommand.kt:20](../../core/app/cli/src/main/kotlin/org/krost/unidrive/cli/AuthCommand.kt#L20))
  but the MCP `unidrive_auth_begin` / `_complete` flow doesn't take a
  process lock.** Means an LLM could begin an auth flow while a
  long-running `unidrive sync` daemon holds the lock — the MCP write
  to `token.json` is atomic but the daemon's in-flight provider
  instance won't pick up the new token until next reconcile. Likely
  fine in practice; worth a one-line note in §10 troubleshooting?

## 7. Reviewer ergonomics — how to navigate

| File | Why a reviewer cares |
|---|---|
| [`docs/MCP-USER-GUIDE.md`](../MCP-USER-GUIDE.md) | The headline new doc. §5 (registration) and §9 (LLM workflows) most likely to drift in future. |
| [`scripts/dev/verify-cli.py`](../../scripts/dev/verify-cli.py) | The harness. `run_case` (sandbox hardening) and `build_matrix` (the row authors) are the active surfaces. |
| [`docs/dev/cli-verification-matrix.md`](cli-verification-matrix.md) | Golden snapshot. Diff against `verify-cli.py` output to see if the snapshot is current. |
| [`docs/dev/lessons/cli-surface-verify-before-doc.md`](lessons/cli-surface-verify-before-doc.md) | Captures the discipline UD-776 instituted. |
| [`docs/dev/lessons/mcp-protocol-version-negotiation.md`](lessons/mcp-protocol-version-negotiation.md) | Captures the UD-758 spec-compliance discipline. |
| [`core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp/McpServer.kt`](../../core/app/mcp/src/main/kotlin/org/krost/unidrive/mcp/McpServer.kt) | The UD-758 fix point. `handleInitialize` (lines 53–72) is the critical path. |
| [`docs/backlog/BACKLOG.md`](../backlog/BACKLOG.md) | New tickets UD-777, UD-778, UD-409, UD-410. |
| [`docs/backlog/CLOSED.md`](../backlog/CLOSED.md) | UD-758, UD-708, UD-283, UD-776 closures, with the supersession note on UD-708's bad JSON example. |
