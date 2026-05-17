# Pin validator / second-opinion agents to a specific commit SHA, not a branch name

**Surfaced 2026-05-17 during the Wave-1 PR review cycle of the Internxt audit.**

## Problem

A validator agent was spawned to independently re-verify a list of audit claims, one of which was: "the merged code at `main` correctly gates `effective_scope` writes on `!dryRun`." The validator's setup said:

> Toolchain: Python `sqlite3` stdlib + `os.walk` + `jq`. Worktree:
> agent-a0070d9f00a4c1415 on branch `claude/dazzling-ride-883ff6`.

The validator looked at the file at the worktree's HEAD on `claude/dazzling-ride-883ff6` — which had three commits *before* the squash-merge to main:

```
fa612ba — file 10 backlog tickets
e53f42f — UD-256 phase 1 (the buggy version)
0fcd1f1 — UD-256 dry-run gates fix (PR review)  ← critical
```

When main merged the PR, all three squashed into `d1fb70a` (which includes the fix). But the validator's worktree was on the pre-squash branch, where the **`HEAD`-then-walk-backward search** happened to land on `e53f42f` — the commit with the bug. The validator then confidently reported "the merged code on main is broken, dry-run leaks `effective_scope`."

A second-opinion validator with the explicit instruction *"read `git show d1fb70a:.../SyncEngine.kt`"* immediately produced the opposite (correct) conclusion. The first validator's "I1 finding" had to be retracted — both publicly in the audit report and in a follow-up message to the user.

The cost of this near-miss: ~20 min of triage, one explicit retraction in the audit report, and the risk of the user trusting a wrong "code-on-main-is-broken" claim.

## Why it bites

A branch reference is a moving target. Between the moment a validator starts and the moment it inspects code, the branch may have been:

- Squash-merged into main (the squash SHA is *not* the same as any branch SHA).
- Force-pushed after a rebase (the SHAs change but the branch name stays).
- Updated with a merge from main (extra commits appear).
- Auto-updated by GitHub's "Update branch" button (a merge commit appears at HEAD).

Any of those leaves the validator inspecting a state that is no longer "what landed." If the validator reasons "I'm on branch X, X is the change in question," it can read a pre-fix commit and report it as live code.

This bites worst in fast-moving review cycles where a PR gets fixed-and-force-pushed multiple times in a session: the validator may inspect any of the intermediate states.

## What to do

When you brief a validator (or any read-and-report agent that depends on a specific repo state):

1. **Name the SHA, not the branch.** Replace "the merged code on main" with "the file at `git show <SHA>:<path>`" where `<SHA>` is the commit you're actually validating. Use `git log origin/main -1 --format=%H` at the time of brief-writing if "main" is what you mean.
2. **Tell the validator to verify the SHA before reading.** A one-line `git rev-parse HEAD` or `git log -1 --format=%H` at the start of the agent's run, echoed back in the report, makes drift visible.
3. **Don't say "the merged PR" alone.** Squash-merge produces a new SHA that is not the PR head. If you need "the merged-on-main version" specifically, name `origin/main` *and* the specific commit you expect.

For the validator's task spec, the contrast is:

| Worse | Better |
|---|---|
| "Read the merged code on main." | "Read `git show d1fb70a:core/.../SyncEngine.kt`. The branch may have intermediate commits; ignore them." |
| "Verify the fix landed." | "Verify the fix is present at SHA `d1fb70a`. If you find yourself reading any other SHA, stop and report which SHA you read." |

## What we did about it

- Spawned a second validator with explicit instructions to `git show <SHA>:<path>` against the merged-on-main commit, not the branch HEAD.
- The second validator independently confirmed the first validator's E refutation (the load-bearing finding) AND retracted its I1 (the dry-run-leak claim) by reading the right SHA.
- The first validator's report stays in its worktree as a historical artefact, with a note that I1 was a SHA-mismatch error, not a code finding.

## Cross-refs

- `docs/dev/lessons/one-truth-sync-discipline.md` — same family of "trail can lie when it gets out of sync with code" problems.
- 2026-05-17 audit report: `.claude/worktrees/dazzling-ride-883ff6/inxt-audit-2026-05-17.md` §"Validator (second pass — confirmed)" for the live example.
- V1 report: `agent-a0070d9f00a4c1415/validation-report-2026-05-17.md`.
- V2 report: `agent-a4e72b4fa42abc63b/validator2-report-2026-05-17.md`.
