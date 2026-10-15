# Stable and flaky tests

The tests are two sets. **Stable** is the default: `./gradlew check` runs it, and a failure there fails the build. **Flaky** is a quarantine: its tests still run and report, in their own task, and can never fail a build or part of it.

## What flaky means here

A test is flaky when it fails on CI with no related change. Evidence, at least one of:

- it failed and then passed on a re-run of the identical commit;
- it failed on a push to main, in a change that does not touch what the test covers;
- it failed on two or more unrelated branches;

and the failure is after the latest attempt to fix that test. A stated cause, or a hypothesis marked as one, goes with it.

Not flaky: a test that is slow; a test that failed once on one branch; a test that fails every time (that is a bug, in the test or in the code). Do not loosen an assertion to make a test pass, and do not delete a test to be rid of it.

## Marking a test

Both lines, together, in this order, on the test function (or the class):

```kotlin
@Category(FlakyTests::class)
@Flaky(issue = 0, reason = "what makes it non-deterministic",
    firstFailure = "https://github.com/gkrost/unidrive/actions/runs/...")
```

(`org.junit.experimental.categories.Category`, `org.krost.unidrive.testing.Flaky` and `FlakyTests`; the last two live in `:app:core`'s test fixtures, which every module's tests see.)

- `issue` is an **open** GitHub issue labelled `bug` and `kind:test` that says what has to be fixed to bring the test back. No issue, no quarantine.
- `firstFailure` is the first CI run that showed the failure.
- The build checks the marker, not the issue: `checkFlakyQuarantine` (part of every `check`) fails on a marker that lacks a number, a reason or a run link, or on one of the two lines without the other, and it prints the whole quarantine once per build. CI warns when a quarantined test's issue is closed.

## Un-marking a test

Delete the two lines (and the imports) when the fix is in and has held: ten green runs on main on the platform that flaked. Close the issue then.

## What runs where

| Task | Runs | Fails the build |
| --- | --- | --- |
| `test`, `check` (every module) | the stable tests only | on a stable failure |
| `flakyTest` (every module, plus the root aggregate) | only the quarantined tests | never |

`./gradlew flakyTest --continue` runs all of them and ends with a summary. Reports are the usual ones, under `build/reports/tests/flakyTest` and `build/test-results/flakyTest`. To run one quarantined test, use `:module:flakyTest --tests '...'`; `test --tests` does not see it.

`ignoreFailures` covers failed assertions only. A test JVM that dies or a run that outlives `flakyTestTimeoutSeconds` (default 300, `-PflakyTestTimeoutSeconds=`) is stopped by the build and reported as "did not finish"; the reports of that module are then missing, and so are the tests behind the one that died.

## How CI reports it

After the stable `check` of each job, a step runs `./gradlew flakyTest --continue --no-daemon` with `continue-on-error`. It writes a table (run, passed, failed, skipped, and each test) to the job summary, one `::warning::` annotation per failed flaky test, and uploads the reports as an artifact. The step cannot turn a job red.

## Review

Look at the quarantine every two weeks and before a release (`./gradlew checkFlakyQuarantine` lists it; the job summary shows how it behaved). For each entry: fixed, so un-mark; still failing, so work on the issue; no issue activity, so ask whether the test earns its place. A quarantine that only grows is a failure of this policy.

## Why stable failures still block

`./gradlew check` is the only gate. A red stable test is a defect until evidence says otherwise; re-running until green hides flakiness instead of removing it and teaches everyone to ignore red. The quarantine is the one deliberate exception: visible in every build, tracked by an open issue, and bounded by the evidence above.
