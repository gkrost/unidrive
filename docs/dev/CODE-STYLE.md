# Code style & commit conventions

Normative rules for the unidrive repo. Short enough to read in one sitting;
every rule has a one-sentence "why" so reviewers and agents can judge edge
cases instead of guessing.

This consolidates ad-hoc rules that previously lived in build files,
ktlint baselines, commit messages, and per-agent memory. Where a rule is
mechanically enforced, the enforcing tool is named.

---

## 1. Encoding

**UTF-8 everywhere** — source, tests, docs, generated output.

- `.kt` / `.kts` / `.java` files are UTF-8 by Kotlin / Java contract.
- Python scripts that emit multi-byte characters call
  `sys.stdout.reconfigure(encoding="utf-8")` before printing. See
  [scripts/dev/backlog.py:182](../../scripts/dev/backlog.py) for the
  canonical pattern.
- Gradle launcher scripts (generated from
  `core/gradle/scripts/windows-utf8.template`) include
  `-Dstdout.encoding=UTF-8` so the CLI prints `→` / `≥` / `KiB`
  correctly on Windows consoles. Why: UD-258 — cp1252 consoles mangled
  every byte-label / progress arrow until this was made explicit.
- Code that cannot assume UTF-8 at the sink (CLI stdout on PS 5.1)
  downgrades gracefully via `GlyphRenderer`. Env override
  `UNIDRIVE_ASCII=1` forces ASCII. See
  [core/app/cli/src/main/kotlin/org/krost/unidrive/cli/GlyphRenderer.kt](../../core/app/cli/src/main/kotlin/org/krost/unidrive/cli/GlyphRenderer.kt).

## 2. Line endings

LF in the repo. CRLF only on `.bat` / `.cmd` / `.ps1` (Windows shell
files whose interpreters require CRLF).

Enforced by [`.gitattributes`](../../.gitattributes) — `* text=auto eol=lf`
with three explicit `eol=crlf` exceptions. Why: mixed line endings churn
diffs and break ktlint.

## 3. ktlint baselines

Treat `config/ktlint/baseline.xml` as **tracked technical debt**, not as
permanent opt-outs.

- Every edit near a baselined violation carries a responsibility to
  either fix the violation or regenerate the baseline. Baselines are
  line-number-anchored; edits above a covered violation re-surface it
  under a different rule id.
- Never silently re-baseline to make CI green without reading the diff.
  The `scripts/dev/ktlint-sync.sh --module <path>` workflow prints the
  baseline delta — scan it before committing.
- Per-module baselines live at
  [core/app/*/config/ktlint/baseline.xml](../../core/app). If a baseline
  entry disappears because you fixed the underlying code, celebrate.

Why: UD-706b + the repeat "baseline drift" incidents in 2026-04-19. See
agent memory `feedback_ktlint_baseline_drift`.

## 4. Multi-line expression wrapping

When an assignment's RHS spans multiple lines, put the opening
`{` / `(` / `try` **on a new line after the `=`**:

```kotlin
// Preferred — ktlintFormat auto-produces this.
val result =
    someComputation(
        arg1,
        arg2,
    )

/* NOT: val result = someComputation(
     arg1,
     arg2,
 ) */

ktlintFormat applies this automatically; naming it explicitly here
prevents reviewers (human or agent) from "un-fixing" it. Why: three
separate commits in 2026-04-19 flipped this back and forth.

## 5. Line length

160-char soft cap (ktlint `max-line-length`). Docstrings and prose in
`.md` hard-wrap at 120 for readability on narrow terminals and in
side-by-side diff viewers.

Oversized lines already past the cap are grandfathered in baselines;
new code should not extend them.

## 6. Import order

- Lexicographic within each group, one blank line between
  `java` / `javax` / `kotlin` groups. ktlint-enforced.
- No wildcard imports, with one exception: `kotlinx.serialization.json.*`
  where the DSL shape (`buildJsonObject { put(..., JsonPrimitive(...)) }`)
  pulls in enough symbols that the wildcard wins on readability.

## 7. Test naming

Kotlin test methods use backticked behavioural descriptions, not the
function under test:

```kotlin
@Test fun `ThrottleBudget opens the circuit after three 429s in a row`() { ... }
// NOT: @Test fun testHandle429() { ... }
```

Already the dominant pattern; this section makes it normative so new
agents don't regress to the `testXyz` style.

## 8. Kotlin default-parameter ordering

New default parameters go **before** trailing-lambda parameters. Adding
a default param at the end of the signature breaks every
`Foo(arg) { … }` call site because Kotlin reassigns the trailing lambda
to the new default slot.

Why: see agent memory `feedback_kotlin_default_param_ordering` for the
UD-254 incident that cost a test cycle.

## 9. Commit messages

[Conventional Commits](https://www.conventionalcommits.org/). Format:

```
<type>(<scope>): <subject>

<body>
```

- `type` ∈ `feat`, `fix`, `docs`, `chore`, `refactor`, `test`, `perf`.
- `scope` is one of:
  - A ticket id: `fix(UD-228)` / `feat(UD-216, UD-252)`.
  - A tool: `chore(ktlint)` / `chore(deps)`.
  - A doc section: `docs(backlog)` / `docs(handover)`.
- **One `UD-###` per commit scope**. A single commit resolving multiple
  related tickets may list all of them; a commit that bundles unrelated
  changes should be split.
- The body ends with a `Co-Authored-By:` trailer when an agent helped.

Enforced by the pre-commit scope-check hook (UD-728). Bypass with
`--no-verify` only for deliberate cross-cutting commits.

## 10. Backlog ↔ commit coupling

Always commit the ticket move (BACKLOG → CLOSED) **separately** from the
code change:

```
# 1. Code commit
git commit -m "feat(UD-228): …"
# 2. Docs commit, pointing resolved_by at the code commit
python scripts/dev/backlog.py close UD-228 --commit <sha-from-step-1> --note "…"
git commit -m "docs(backlog): close UD-228 — <one-line summary>"
```

The `resolved_by: commit <sha>` line in the CLOSED.md entry always
points at the immediately-preceding code commit, never at the same
commit as the docs close. Why: keeps `git log --grep=UD-228` honest —
one hit for the code, one hit for the close, no circular reference.

## 11. Everything else

When in doubt, read the five most recent commits (`git log --oneline -5`)
and match the style. If a rule above disagrees with what five consecutive
recent commits do, the rule is probably out of date — open a ticket to
realign.

**Cross-links:**
- [docs/AGENT-SYNC.md](../AGENT-SYNC.md) — BACKLOG/CLOSED contract.
- [docs/dev/TOOLS.md](TOOLS.md) — scripts that automate these rules.
- [CLAUDE.md](../../CLAUDE.md) — agent-scoped pointers.

Acceptance for UD-714: a fresh agent session reading this file plus
`CLAUDE.md` and shipping a commit passes CI without bouncing once.
