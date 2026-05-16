# Subagent `git checkout` can silently detach the host shell's HEAD

When dispatching a `Task` subagent (e.g. `general-purpose`, `superpowers:code-reviewer`) that touches git, the subagent shares the host shell's working directory and `.git` *but operates inside its own bash session*. A `git checkout origin/main` (or any other ref) executed inside the subagent **mutates the host repo's HEAD** — but the controller does not see the move until it explicitly re-reads `git branch --show-current`. The controller's mental model of "I'm still on branch X" silently desynchronises from reality.

The failure mode this surfaces in: the subagent reports a successful `git commit <sha>` in its log, but that commit lands on a **detached HEAD** at some other ref. When the controller later checks `git log --oneline -3`, it sees neither the feature branch's expected HEAD nor the subagent's claimed commit. The commit is reachable only via the reflog as a dangling tip; the feature branch is untouched and stale.

## Worked failure (UD-014 session, 2026-05-16)

Two distinct instances during a single 12-task plan execution against `spec/UD-014-mcp-auth-provider-agnostic`:

1. **Task 6 dispatch** — the implementer subagent reported `DONE` at commit `72d48ac` with `tests=7, failures=0`. Host shell check:
   - `git branch --show-current` returned empty (detached).
   - `git log --oneline` showed `71711cd` (origin/main tip), not the expected Task 5 tip.
   - `git show 72d48ac` → `fatal: ambiguous argument '72d48ac': unknown revision`.
   - `git reflog | head -3` revealed `HEAD@{0}: checkout: moving from spec/UD-014-mcp-auth-provider-agnostic to origin/main` — the subagent's environment had `git checkout origin/main` somewhere in its setup, then committed onto the detached HEAD. The "commit" was a dangling object reachable only via `git fsck --lost-found`.
   - **Recovery cost:** ~10 minutes redoing Task 6 inline + tracking down the cause. The implementer's claimed test output was real (the test compiled and passed in *their* environment), just invisible to the host.

2. **Task 5/6 boundary detour** — same shape, two task boundaries earlier. Verified via the same reflog pattern.

Both incidents had **identical symptoms** and would have been caught immediately by a 3-second host-shell verification step after every dispatched commit. We did not have that step in the loop.

## Mitigations

### Always verify host git state after a subagent dispatch that claims to commit

After every Task subagent that reports "I committed `<sha>`", run from the host shell BEFORE trusting the report:

```bash
git branch --show-current   # must equal the feature branch
git log --oneline -3        # top commit must equal the claimed <sha>
git status --short          # must be clean (no orphaned working-tree changes)
```

If `git branch --show-current` returns empty → detached HEAD. Stop, switch back to the feature branch (`git checkout <feature>`), and redo the work inline. Do not attempt to re-dispatch — the subagent will likely repeat the same checkout.

### Prefer inline execution for tasks that mutate git state on a hot feature branch

For multi-task plans executing against a live feature branch where every commit must accumulate, **inline execution is safer than subagent dispatch** for the implementer role. Code-review subagents (read-only) are safe; implementer subagents (commit-emitting) are not, until the sandbox isolation issue is fixed in the Task tool.

The cost of fresh-subagent-per-task (no context pollution, parallel-safe) is real, but it is paid back only when the work *actually lands* in the host repo. A "successful" subagent execution that commits to a detached HEAD is worse than no execution — it consumes tokens AND requires recovery work to detect.

### If you must dispatch, gate every implementer agent with a verifier

Wrap each implementer dispatch with a small read-only verification step. The pattern:

```
1. controller: dispatch implementer subagent for Task N
2. implementer: commits <sha>, reports DONE
3. controller: in host shell, run:
     test "$(git rev-parse HEAD)" = "<sha>"
     test "$(git branch --show-current)" = "<expected-branch>"
   If either fails → STOP, recover, do not advance to Task N+1.
```

This is `superpowers:verification-before-completion` applied to the controller→subagent boundary, not just to the implementer's self-claims.

## What this is NOT about

This is *not* about subagents being unreliable in general. The implementer subagents in the UD-014 session executed Tasks 1–5 and 7–12 correctly, including non-trivial test design, KDoc rewrites, and a 100-line override implementation. The defect is specifically about **state visible to the host shell** when the subagent's bash session mutates that shared state mid-execution.

This is also *not* about a flaw in the `superpowers:subagent-driven-development` skill. The skill correctly specifies the dispatch + review cycle. The detection gap is at the boundary between the controller (host shell) and the implementer (subagent shell) — a sandbox property of the Task tool, not a skill defect. The skill could be sharpened to include the verification step above.

## Pattern

If a subagent reports a commit SHA, verify the SHA exists at the expected branch tip in the host shell *before* moving to the next task. If the host shell's branch matches the expected feature branch and the top commit matches the claimed SHA, proceed. Otherwise, treat the subagent's work as lost and redo inline.

## Related

- [`docs/dev/lessons/verify-before-narrative.md`](verify-before-narrative.md) — the more general principle: confirm distinguishing attributes before building a diagnosis narrative.
- [`docs/dev/lessons/halted-agent-leaks.md`](halted-agent-leaks.md) — adjacent failure-mode where a subagent stops mid-execution and leaves orphaned state.
- `superpowers:verification-before-completion` — applies the same "evidence before assertions" rule to the implementer's self-review; this lesson extends it to the controller→subagent boundary.
- [UD-014](../../backlog/CLOSED.md#ud-014) — the session where this surfaced twice in a single 12-task plan.
