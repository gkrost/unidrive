# ktlint baselines are line-number-anchored, not content-anchored

When a `.kt` file has existing ktlint violations covered by
`config/ktlint/baseline.xml`, the baseline records them as
`<error line="N" …/>`. If you edit the file and change line counts
above a covered violation, line N now points to a different (clean)
line and ktlint **re-flags** the violation at its new position — the
build fails on code that was previously green.

## Worked failure (UD-232 session)

A new `throttleBudget` constructor parameter shifted
`GraphApiService.kt` down by ~5 lines. The baseline entry at line 501
became stale and the underlying violation popped up at line 516.
Build broke; commit sequence stalled.

## How to apply

After any non-trivial edit to a `.kt` file listed in
`config/ktlint/baseline.xml`, run `./gradlew :<module>:ktlintCheck`
and expect surprises. Three acceptable responses:

1. **Best — pay down the debt.** Fix the underlying violation
   inline, drop the baseline entry. The baseline is meant to shrink,
   not persist.
2. **Fast but silent.** Run `./gradlew :<module>:ktlintGenerateBaseline`
   to re-pin line numbers. Always follow with
   `git diff config/ktlint/baseline.xml` so you can SEE what shifted —
   silent baseline regen is how dead code creeps in.
3. **Inline patch.** Wrap the long line / split the statement so the
   violation goes away without baseline churn.

`./gradlew ktlintFormat` auto-fixes many rules (`statement-wrapping`,
`multiline-expression-wrapping`) but leaves `max-line-length` alone —
those need manual wraps.

## Don't

- Don't add new code that deliberately reuses an existing baseline
  entry's "slot" — the baseline is a debt ledger, not a license to
  add new violations.
- Don't run `ktlintGenerateBaseline` blindly across all modules in a
  cleanup pass — it hides legitimate new violations under "looks like
  a baseline refresh". Module-scoped is the right tool;
  [`scripts/dev/ktlint-sync.sh --module :app:cli`](../../../scripts/dev/ktlint-sync.sh)
  exists for that.
