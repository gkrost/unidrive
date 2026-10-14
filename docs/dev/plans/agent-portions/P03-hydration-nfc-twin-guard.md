# P03 Mount writes: do not let an NFD twin overwrite the NFC file's cloud content

| | |
|---|---|
| Issues | gkrost/unidrive#491 |
| Labels | bug, sev:medium, area:sync, kind:correctness, lane:nfc-freshness |
| Size | M |
| Priority | rank 3 of 54 (correctness group), wave 4 |
| Status | blocked on owner decision |
| Depends on | the owner decision below |
| Not in parallel with | P04 (identity rules), P18, P35 (`HydrationImpl`); same file family (take them one after the other, order in README): P18, P17, P08, P35, P36, P06, P02 |
| Status check | verified on main a0c6c4d3, 2026-10-14: partly fixed (sync path), the mount write path is open |

## Problem
- Since #171 every path is NFC-canonical at the engine's boundaries, so two different names that normalize to one string (NFC/NFD twins such as `cafe` plus U+0301, or U+212B against U+00C5) are one row and one cloud path. [measured] (issue, golden run 3, isolated localfs): with a client that matches names by NFC, the NFD member is uploaded and REPLACES the NFC member's content in the provider; the NFC original still exists locally; five pairs were checked by SHA-256.
- Owner decision 2026-10-03: refuse accent-variant pairs for now (option 1). Done: `LocalScanner.refuseNfcTwins` syncs neither twin and warns once (PR #509, `LocalScannerNfcCollisionTest`); prefix queries (PR #492); the download direction is guarded ("Remote path collision ... stays cloud-only"); the client refuses upload of a clashing pair (unidrive-windows#123).
- [code] Open: the engine's hydration write verbs have no twin guard. `HydrationIpcHandler.pluckPath` (about line 461) canonicalises every path to NFC BEFORE the verb runs, so `HydrationImpl.openWriteBegin` (about line 1971), `create` (about line 2001; rejects only via `PathExists` on the NFC key) and `openForWrite` cannot see which spelling the client sent. A client without its own guard still overwrites. The Linux FUSE write path is gkrost/unidrive-mount-linux#85 (other repository).
- [inferred] Refusing every non-NFC spelling for an existing NFC row also refuses a macOS NFD client editing its own file. Telling a twin from a legitimate NFD spelling needs the spelling first seen per row remembered, and no such column exists.

## Scope
- Change (after decision 1): `HydrationIpcHandler.pluckPath` keeps the raw spelling next to the canonical one and passes both to the verbs that write (`create`, `open_write_begin`, `open_write`, `rename`); `HydrationImpl` refuses a write whose raw spelling differs from the row's recorded spelling while a DIFFERENT name already owns that NFC key, with a typed error (`name_clash`, token to be agreed, additive in the corpus). Record the first-seen spelling per row only if the decision requires it.
- Must not change: NFC canonicalisation for reads and listings (#171), the scanner's refusal, the single-spelling case (a lone NFD name stays stored as NFC, `FORM` in the golden suite).
- Out of scope: the FUSE side, the client, case twins (P04).

## Acceptance criteria
1. `HydrationIpcHandlerNfcTest` (pattern: it already sends non-NFC paths): `create` of `cafe` + U+0301 while an NFC `café` row exists is refused and the row's content and remote hash are unchanged.
2. `open_write_begin` / `open_write` of the NFD spelling of an existing NFC file: refused per the decision; the same spelling as the row's own is accepted.
3. A lone NFD name with no twin is still created (stored NFC).
4. `HydrationImplCreateTest`: nothing reaches the provider on a refused twin (fake provider records zero calls).
5. Corpus fixture for the new error (additive), `IpcContractCorpusTest` green.
Commands: `cd core && ./gradlew :app:hydration:test --tests '*HydrationIpcHandlerNfcTest' --tests '*HydrationImplCreateTest' --tests '*HydrationImplTest' && ./gradlew :app:sync:test --tests '*LocalScannerNfcCollisionTest' && ./gradlew :app:cli:test --tests '*IpcContractCorpusTest'`.

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
1. The rule: (a) refuse any write whose spelling differs from the row's recorded first-seen spelling (needs a column), (b) refuse only when a distinct existing name collides (needs both spellings to be known at write time), or (c) leave it to the clients. Proposal: (b) with the raw spelling passed from the handler; no new column.

## How to verify live
Owner, isolated localfs profile and the golden unicode tree (`scripts/golden/unicode-tree`, `hazards/nfc-nfd-twins`): both members of each pair keep their own content after a mount write of the NFD member.
