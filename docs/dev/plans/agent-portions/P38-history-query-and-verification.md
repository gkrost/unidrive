# P38 Transfer history for the Activity view: sort, per-column filters and a verification field

| | |
|---|---|
| Issues | gkrost/unidrive#860 (asks 1 and 2; asks 3 and 4, a Windows-operation writer and a safe retry, are design decisions listed below) |
| Labels | enhancement (desktop-UI lane; needed by gkrost/unidrive-windows#263) |
| Size | M (L with asks 3 and 4) |
| Priority | rank 38 of 54 (UX group), wave 3 |
| Status | ready (asks 3 and 4 are decisions) |
| Depends on | nothing |
| Not in parallel with | any edit of `HistoryReader` / `HistoryListRequest` / `TransferModel` |
| Status check | verified on main a0c6c4d3, 2026-10-14: open, all four asks missing |

## Problem
- Windows PR #263 can use the read-only history today but leaves unsupported controls unavailable. Wanted by the issue: (1) server-side sort and per-column filters/search that stay stable across pages, with a cursor tied to the sort/filter query; (2) a verification outcome when the engine has evidence, with an explicit unknown state otherwise; (3) a supported ingestion or read path for Windows-only operations with a single authoritative writer; (4) if retry from history is supported, a full-scope command that checks the current source/version and applies conflict-safe idempotency; a historical path must not be replayed blindly.
- [code] `HistoryListRequest.parse` (`core/app/cli/.../HistoryListRequest.kt`) accepts only the filters direction, result, `path_prefix`, `from_ms`, `to_ms`; the order is fixed newest-first (`HistoryReader` `HistoryQuery` / `HistoryCursor` keyset). `TransferRow` (`engine-core/.../telemetry/TransferModel.kt`, about line 59) has `oldHash` / `newHash` but they are not on the wire and there is no verification field. `HistoryRpcHandler` registers `history.list` and `history.subscribe` only. The spec (`docs/dev/specs/unidrive-daemon-design.md` section 4.8) says the engine has no verb that changes or exports history; asks 3 and 4 reverse that.
- Verification vocabulary from epic #650: not checked, unavailable, size checked, provider checksum checked, end-to-end readback checked, mismatch; these are new explicit states, not booleans. The post-upload verification design (EN-8) has no issue yet.

## Scope
- Change (asks 1 and 2): `HistoryListRequest` (sort key and direction from a fixed allow-list: time, path, size, duration, result; per-column filters: name contains, direction, result, size range, time range, error class), `HistoryReader` (query with a keyset cursor that encodes sort + filter, so a cursor from another query is rejected), `TransferRow` and the `history.list` row JSON (`verification`: one of the vocabulary values or `unknown`, derived from the hashes and the provider's `hashAlgorithm` only where evidence exists; schema migration of `telemetry.db` additive), spec section 4.8, fixtures `ipc-contract/history.list.ndjson` first.
- Must not change: default order and cursor behaviour for existing requests, retention, the writer queue, `history.subscribe`.
- Out of scope: asks 3 and 4 (decisions below), the Windows UI.

## Acceptance criteria
1. `HistoryRpcHandlerTest` (real `TransferHistory` on a temp `telemetry.db`): every allowed sort returns a stable total order (ties broken by row id), paging by cursor visits each row exactly once, and a cursor from another sort or filter is refused with a typed error.
2. `HistoryReaderTest` (`engine-core`): filters combine with AND and use indexes (assert the query plan or a row-count bound on 50,000 rows).
3. Rows with no evidence report `verification: "unknown"`; a row with equal local and remote hash under a declared algorithm reports `provider checksum checked`.
4. Old requests without sort/filters return the same rows in the same order as before (golden).
5. Unknown sort keys are refused; filter strings are length-capped; no URL, token or path outside `path_prefix` semantics leaks.
6. `IpcContractCorpusTest` green with updated fixtures.
Commands: `cd core && ./gradlew :app:cli:test --tests '*HistoryRpcHandlerTest' --tests '*IpcContractCorpusTest' && ./gradlew :app:engine-core:test --tests '*HistoryReaderTest' --tests '*TransferHistoryTest'`.

## Constraints
- Create your own worktree from origin/main (`git fetch origin`, then `git worktree add ../wt-<slug> -b <branch> origin/main`); never work in a shared checkout or in a worktree another session owns.
- Never `git stash`. Merge main into the branch (no rebase, no force-push).
- One branch and one DRAFT pull request for this portion; the owner merges. Check `gh pr list --state open` for a PR on the same files first.
- Claim each issue with a comment ("starting, branch ...") before editing, and report the PR on the issue.
- Commit messages describe the change; engine commits carry no ticket id in the subject, the PR body names the issues. End every commit with `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>` and the PR body with `🤖 Generated with [Claude Code](https://claude.com/claude-code)`.
- Line endings follow `.gitattributes` (LF; only `*.bat`, `*.cmd`, `*.ps1` are CRLF): keep each file's endings. `core/gradlew.bat` always shows an EOL-only change in a Windows checkout: never commit it.
- detekt must pass against the per-module baselines (never regenerate a baseline; there is no ktlint in the build) and add no compiler warnings.
- Run only the touched test classes locally (`cd core && ./gradlew :app:<module>:test --tests '<class>'`); CI runs `./gradlew check` on the PR (read it with `gh pr checks`).
- Public repository: no private paths, account names or addresses in code, tests, commits or PRs. No live account, no deploy, no running `unidrive`/`java` process touched; use fakes, `localfs` or temp dirs.

## Open decisions for the owner
1. Ask 3: who is the single authoritative writer for Windows-only operations (the client posts rows through an admin verb, or the engine reads a client-written file)?
2. Ask 4: is retry from history in scope at all, and with which idempotency rule (re-plan from current state, never replay a path)? Proposal: not before asks 1 and 2 have shipped.
3. Where does verification evidence come from beyond hashes (EN-8)?

## How to verify live
No live check needed; the client side is tested in gkrost/unidrive-windows#263.
