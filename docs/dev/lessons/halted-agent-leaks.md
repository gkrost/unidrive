# Halted sub-agents leak WIP into the main worktree

Learned 2026-04-20, after the IpcClient-stub incident on the
pre-greenfield tree.

## The failure mode

1. A sub-agent spawned with `isolation: "worktree"` starts writing
   source changes in its assigned worktree.
2. The agent hits a rate limit, times out, or the session is
   otherwise halted mid-stream. The harness does NOT refresh or
   resume it — it stays halted.
3. **The uncommitted working-tree edits persist.** The agent's UI
   shows these "sessions" as idle, but the git worktree still has
   modified files.
4. Subsequent tree-level tools run in the main repo worktree can
   sweep those files: `scripts/dev/ktlint-sync.sh --module X` does
   `./gradlew ktlintFormat` which may format across the whole Gradle
   project (not just module X's scope), **including files the stuck
   agent left dirty**.
5. When the calling session does `git add …` + commit, the stuck
   agent's half-done change ships inside an ostensibly
   scope-limited commit.

Result: `git blame` points at the maintainer, but the code was
written by an agent that never finished. Bisect loses signal. The
build can be silently broken on the branch for hours because the
ktlint chore commit carried non-compiling code and no subsequent
build ran on that Gradle project.

## What to check before "scope-limited" tree commands

Before `ktlint-sync.sh`, `gradle build`, `docker compose build`, any
`git add .`:

```bash
# list uncommitted-but-not-mine files
git status --short | head -40
git diff --stat | head -20
```

If any file in the output is outside the scope you're working on,
either:

- Explicitly commit or stash it first (attribute it to whatever
  session originated it, if identifiable), OR
- `git restore --worktree <path>` to drop it before running the
  scoped tool, OR
- Ask the user. "Halted agent left this here" is a safer hypothesis
  than "I accidentally edited it".

## What to check before trusting `git blame` on recent commits

If a commit's diff looks out of scope for the commit message
(`chore(ktlint): …` that touches `IpcClient.kt` heavily), suspect
agent-state leakage, not your own error. Narrate accordingly: "the
sweep carried in an earlier agent's WIP", not "not my change".

## Defensive commit hygiene

- Prefer `git add <explicit paths>` over `git add .` / `-A`.
- Before committing a "chore" commit, inspect `git diff --stat` and
  reconcile every file with the stated scope. If something's
  unexpected, stop.
- After `ktlint-sync.sh` or any auto-format tool, diff the result
  and flag any file you didn't expect to be touched. Line-number
  drift is expected; net line-count changes aren't.

## On the "not my change" reflex

The first reply when the stub was discovered was "not my change" —
technically correct (different agent authored the reflection stub)
but unhelpful: the maintainer's commit landed it. Chain of custody
ends at the merge SHA. Don't outsource blame to an earlier author;
describe what actually happened and take the fix.
