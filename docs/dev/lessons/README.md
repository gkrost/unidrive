# Lessons

Cross-session lessons accumulated while building unidrive — the kind of
thing that's wrong with high enough probability that a sentence in the
right place would have saved an hour. Sanitized from the maintainer's
agent-session memory and committed here so any agent (Claude Code,
Opencode, aider, Cursor, etc.) attached to a fresh fork picks them up
through the standard `CLAUDE.md` → repo-doc trail.

The contract: a lesson lands here only if it's **actionable** (you can
read it and avoid the failure) and **universal-enough** (a contributor
who is not the original maintainer would recognise the situation).
Personal preferences, machine-specific quirks, and one-off session
notes stay out of the repo by design — see
[CONTRIBUTING.md §"What is and isn't in the tree"](../../CONTRIBUTING.md)
for the gitignore policy.

## Index

| File | Topic | When it bites |
|---|---|---|
| [jar-hotswap-windows.md](jar-hotswap-windows.md) | Never overwrite a JVM-loaded jar on Windows | Windows-only contributor running `gradle deploy` against a live daemon |
| [ktlint-baseline-drift.md](ktlint-baseline-drift.md) | Baselines are line-number-anchored | Editing a `.kt` file with covered violations |
| [kotlin-default-param-ordering.md](kotlin-default-param-ordering.md) | New default params go BEFORE the trailing-lambda param | Adding a constructor / function parameter to a class with trailing-lambda call sites |
| [mdc-in-suspend.md](mdc-in-suspend.md) | SLF4J MDC inside suspend functions | Adding scoped log-context across coroutines |
| [verify-before-narrative.md](verify-before-narrative.md) | Confirm distinguishing attributes before writing the diagnosis | Building a root-cause story from circumstantial evidence |
| [halted-agent-leaks.md](halted-agent-leaks.md) | Halted sub-agents leak WIP into the main worktree | Multi-agent sessions, scope-limited tree commands |
| [jfr-internal-noSuchMethodError.md](jfr-internal-noSuchMethodError.md) | JFR captures internal `NoSuchMethodError`s the JVM uses as control flow | Triaging a JFR's `JavaErrorThrow` events without filtering |
| [one-truth-sync-discipline.md](one-truth-sync-discipline.md) | Code change ↔ docs / open tickets / lessons must move together | Refactoring; lifting helpers; renaming public symbols |

## Adding new lessons

When something burns you, ask "would the same situation burn the next
contributor?" If yes, write a one-page note with the same shape as the
existing files (problem, why it bites, how to apply, defensive
checklist) and link it in the index above. Lessons here are versioned
with the rest of the repo; agent memory in `~/.claude/.../memory/`
remains the maintainer's per-machine notebook for things that don't
generalise.
