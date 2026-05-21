---
name: challenge-test-assertion
description: Before accepting, updating, or writing a test, verify its assertion reflects the business invariant being protected, not just the current implementation's behaviour. Use when reviewing legacy tests, adding tests for existing code, fixing a failing test by making it pass rather than by fixing the code, or whenever a test looks redundant with another. Born from the 2026-04 Buschbrand experiment (UD-715) — on the pre-greenfield unidrive, lint-green + CI-green tests cemented increasingly wrong business logic that survived because no agent challenged the inherited narrative.
---

# challenge-test-assertion

A test is only valuable if it asserts something worth preserving. Many tests
assert **the what** (the current output) instead of **the why** (the invariant
the output is meant to satisfy). When the implementation changes to better
serve the requirement, the what-test fails — and the agent under pressure to
get CI green tends to update the test to match the new output, cementing
whatever the implementation now does. The invariant quietly disappears.

When you are about to touch a test — accept it in a code review, rewrite it
because it's failing, or add a new one — run these three questions:

1. **What invariant is this test protecting?** Name it in one sentence,
   without looking at the implementation under test. If you can't, either
   the test is protecting the implementation (not a real invariant) or the
   invariant is undocumented. Write it down as a comment before proceeding.
2. **Would a reasonable alternative implementation satisfying the same
   invariant fail this test?** If yes → the test asserts the implementation,
   not the invariant. Candidate for rewrite to assert the invariant directly.
3. **If you deleted this test, what real-world harm would reach production?**
   If "none" → candidate for deletion, not keep-and-ignore.

Examples from unidrive's own codebase:

| Healthy assertion | Unhealthy assertion |
|---|---|
| `ThrottleBudgetTest`: "4 throttles in 20s opens the circuit; 3 do not" (asserts the contract) | `ThrottleBudgetTest`: "after 4 throttles, resumeAfterEpochMs equals 12000" (asserts exact computation; any backoff-formula tune breaks it) |
| `SweepCommandTest`: "a fully-NUL file with size matching remote is flagged" (asserts the invariant) | `SweepCommandTest`: "exactly 3 blocks are read per file" (asserts implementation detail of the sampling strategy) |

## What to actually do when a test fails

When you are tempted to change a test to make it pass:

- **Stop.** Run the three questions on the test first.
- **If the test is asserting an invariant** that the new code violates → the
  change is wrong. Revert or redesign. Don't touch the test.
- **If the test is asserting implementation** that happened to be captured
  → rewrite the test to assert the invariant, then the change stays. Leave
  a one-line comment above the test naming the invariant, so the next
  reader doesn't re-commit the same sin.
- **If the test was always junk** → delete it. Leave a CHANGELOG note so
  future readers know it was removed deliberately, not accidentally.

## Background — why this skill exists

A wide test sweep on this project surfaced that a sizeable fraction of
existing tests asserted implementation behaviour, not invariants. They
had been lint-green, human-reviewed, and CI-green for months. They were
still wrong: the tests did not protect anything a reasonable
alternative implementation could break.

Moral: test-green is necessary but not sufficient. Tests can actively
mislead when they assert the what. This skill installs the question
"what's the why?" as a reflex.

See also [`docs/ARCHITECTURE.md`](../../../docs/ARCHITECTURE.md) for
the current set of invariants unidrive's tests should be defending.

## When NOT to invoke

- During pure refactors where the test already asserts the right invariant
  and you're only renaming a variable — don't slow down for the ritual.
- For unit tests that are narrowly scoped to a pure function with obvious
  inputs/outputs (e.g. `LocaleFormatter.formatSize(1536) == "1.5 KB"`) —
  those are legitimately about the implementation because the
  implementation IS the contract.
- For contract tests whose entire purpose is "this external API returns
  this shape" — pinning the shape IS the invariant.
