## sync knowledge ↔ code ↔ .md's — there can only be one truth

When you change the code, the documentation that describes that code
changes too — in the same commit, or as a deliberate follow-up
committed before the next contributor (human or agent) sees the
divergence. Stale `.md` files that contradict the current code are not
merely "out of date"; they actively mislead the next reader and
**poison the ground truth** that an agent reasons against.

The principle has three layers:

```
knowledge ←→ code ←→ .md's
```

All three must agree, or the contributor's mental model goes wrong.
Knowledge here is the live state of the repo as understood by a fresh
agent that just read CLAUDE.md and the docs trail.

## The forms drift takes

This repo has hit each of the following at least once:

- **Cross-package KDoc references** that say
  `See [org.krost.unidrive.onedrive.setPosixPermissionsIfSupported]`
  in HiDrive's source, after the helper was lifted to `:app:core/io`
  and the OneDrive copy deleted. Three providers' docstrings were
  pointing at vapor.
- **Open-ticket prose** referencing a sibling ticket that has since
  closed. UD-344's body said "subsumes finding 3.3" — UD-347 (3.3)
  shipped independently, leaving UD-344's "subsumes" claim wrong on
  arrival.
- **ARCHITECTURE.md "Key files"** lists not mentioning a growing
  shared-helpers layer (`:app:core/http`, `:app:core/auth`,
  `:app:core/io`) — a future agent looking for "where do I put the
  next shared HTTP guard" has to grep instead of read.
- **Lessons** that pin a pattern at the wrong path because the file
  moved later. The lesson stays accurate prose-wise but the file:line
  citation goes 404.
- **Closed-ticket "resolved_by"** notes pointing at commits that
  *don't* mention the ticket id in the subject — the warning
  `commit X subject does not mention UD-Y` from `backlog.py close` is
  surfacing exactly this drift.

## Why it bites worst with agents

A fresh-context agent reads CLAUDE.md, follows the doc trail, and
takes the code's current state as ground truth. When the trail
contradicts the code, the agent picks one — usually whichever it saw
first — and confidently builds on the wrong premise. By the time the
contradiction surfaces in a build error or a runtime symptom, the
agent has spent context and tool calls on an inconsistent foundation.

The same is true for humans, but humans tend to recognise "this
comment looks old" by smell. Agents trust the words.

## The discipline

When a code change crosses these boundaries, sweep:

1. **Source comments + KDoc** — grep for the old class / package /
   file path. If you renamed a public symbol, every KDoc reference to
   the old name is now wrong.
2. **`docs/ARCHITECTURE.md`** — does the change introduce a new
   module-level concern (a shared helpers layer, a new tier, a new
   integration boundary)? Add or update the "Key files" / matrix /
   subsystem section.
3. **`docs/SPECS.md`** — does the change affect a contract the spec
   audit pinned? Update the row.
4. **Closed-ticket `resolved_by` notes** — usually fine because
   they're append-only history, but if you backed out a fix or moved
   its impl, surface that as a *new* ticket rather than rewriting
   history.
5. **Open-ticket bodies** — search BACKLOG.md for the old path /
   symbol / sibling ticket id. A "Related" section that points at a
   closed ticket is fine; a "Subsumes" / "Depends on" claim that no
   longer holds is not.
6. **`docs/dev/lessons/`** — if the change invalidates a lesson, fix
   the lesson or open a follow-up. Lessons that go wrong silently
   are worse than no lesson at all because they get internalised.
7. **`CLAUDE.md`** — if the change shifts the "before you start"
   guidance, update it. This is the entry point most fresh agents
   read first.

## Rule of thumb

> If the code change is named in a commit subject, the same commit
> (or a sibling commit pushed in the same branch) should touch the
> docs that name the same concept.

`git log --name-only` should show docs paths alongside source paths
for any commit that crosses an architectural boundary. If it doesn't,
the docs are drifting behind.

## When this lesson was written

2026-04-30, after a heavy refactoring session lifted six provider-
internal helpers into `:app:core` (UD-336, UD-337, UD-340, UD-342,
UD-343, UD-347, UD-349, UD-351). The maintainer caught the discipline
gap with the line "sync knowledge ↔ code ↔ .md's — there can only be
one truth" before any single drift had bitten yet — i.e. **the lesson
landed pre-failure** instead of post-mortem, which is the rare case.
That made the sweep cheap. Post-mortem sweeps are expensive because
you also have to undo the bad mental models the drift seeded in
intermediate readers.
