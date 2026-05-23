# Tracking-set engine — Internxt end-to-end verification

End-to-end verification of `:app:sync-tracking` against a real Internxt
profile. Closes one half of the BACKLOG entry "Tracking-set engine:
verify Internxt provider end-to-end" (the other half is OneDrive).

## Setup

- Repo branch: `verify/tracking-set-internxt`, off `main` at `7f71589`.
- Daemon: `systemctl --user is-active unidrive.service` → `inactive`
  (no contention for Internxt tokens or rate budget).
- Profile under test: `internxt_gernot_krost_posteo`.
  `credentials.json` present under `~/.config/unidrive/<profile>/`.
  `state.db` present (~62 MB; tracking-set engine does not touch it —
  it uses its own `tracking.db` in the test's temp dir).
- Baseline: `./gradlew :app:sync-tracking:test -q` → BUILD SUCCESSFUL.
  No regressions on the existing unit / `FakeTrackingProvider` tests.
- Invocation:
  `cd core && UNIDRIVE_INTEGRATION_TESTS=true ./gradlew :app:sync-tracking:test
   --tests "org.krost.unidrive.tracking.TrackingEngineInternxtLiveTest" -q --info`
- Full log captured at `/tmp/tracking-internxt-run.log` (538 lines, not
  committed — contains profile path strings).

## Test run

- Outcome: PASS.
  JUnit XML (`build/test-results/test/TEST-...InternxtLiveTest.xml`):
  `tests="1" skipped="0" failures="0" errors="0" time="3754.326"`.
- Wall clock for the test method (single `engine.syncOnce()` call):
  3754.3 s ≈ 62 min 34 s.
- Total Gradle build: `BUILD SUCCESSFUL in 1h 2m 37s`.
- Operator-facing summary line emitted by the test:
  `TrackingEngineInternxtLiveTest: plan size=195740 (all DownloadRemote);
   adopted=0 (should be 0); collisions=0 (should be 0)`.
- 195,740 files visible to the engine. Consistent with the BACKLOG-line
  observation of ~191k sparse rows on this profile via the legacy
  `status` command. The delta-gather dropped 288 items (168 files +
  120 folders) on this pass — see invariant section below.

## Observations (lemma)

INVARIANT 1 — **HOLDS**.

Empty local sync_root + empty `tracking.db` + populated remote (195,740
files visible) → zero `PropagateLocalDelete` and zero
`PropagateRemoteDelete` actions emitted. Test's `assertEquals(0, deletes, …)`
passed.

The structural property the engine exists to prove is what the test
proves on this run: no path is a candidate for deletion until the
client has crossed the sync boundary for it at least once. With an
empty tracking set, deletion is impossible by construction.

This survives the incomplete remote enumeration on the same pass (see
provider-interaction section): the engine's belt-and-suspenders code
path in `TrackingEngine.kt` (`enumerateRemote()` returns
`complete=false`, and `syncOnce()` filters out delete actions
unconditionally when `complete=false`) would have suppressed deletes
anyway, but the lemma itself meant there were none to suppress.

## Observations (downloads-only invariant)

INVARIANT 2 — **HOLDS**.

All 195,740 actions in `report.plan` were `ReconcileAction.DownloadRemote`.
Test's `assertTrue(nonDownload.isEmpty(), …)` passed.

INVARIANT 3 — **HOLDS**.

Zero collisions surfaced. Test's
`assertTrue(report.collisions.isEmpty(), …)` passed. Correct: collisions
require both sides present with different content, impossible when local
is empty.

Adoption count is 0, as expected (same reason — adoption requires both
sides present with matching content).

## Observations (provider interaction — 429 / JWT-refresh / malformed-delta)

Search of the 538-line run log for `429`, `Retry-After`, `ratelimit`,
`jwt`, `refresh.*token`, `token.*refresh`, `expir`, `NullPointerException`,
`JsonDecodingException`, `IllegalStateException` returned **zero
matches**. Only two WARN lines were emitted during the entire 62-minute
enumeration:

```
09:38:43.594 WARN  o.k.u.internxt.InternxtProvider - Internxt delta gather
  dropped 288 item(s) (168 file(s), 120 folder(s)) whose ancestor uuid was
  missing from this page's folderMap; returning DeltaPage(complete=false).
  Dropped items recover when their ancestors next change or via --reset.

09:38:45.899 WARN  o.k.unidrive.tracking.TrackingEngine - Remote
  enumeration incomplete; suppressing delete actions for this pass
  (tracked rows that we didn't see are kept, not deleted).
```

- **429-storm:** not observed. Either the Internxt rate budget was never
  approached during a single bulk enumeration, or the internal retry
  shim handles 429s without emitting WARN-level log lines. Cannot
  confirm 429-handling code path is exercised by this test; would need
  a synthetic burst or a much bigger account. **Not a regression.**
- **JWT-refresh:** no token-refresh log lines were observed across the
  62-minute enumeration. Either (a) the JWT TTL is longer than 62 min
  on this profile so refresh wasn't needed, or (b) refreshes happened
  silently. The enumeration completed cleanly with no auth errors —
  whichever case, the engine's externally observable behavior was
  correct. **Cannot confirm JWT-refresh code path is exercised by this
  test.**
- **Malformed-delta:** not observed in the sense of parser exceptions
  (`NullPointerException` / `JsonDecodingException`). However, the
  delta page DID arrive in a partially-defective shape — 288 items
  referenced ancestor folder UUIDs that were missing from the same
  page's folderMap. `InternxtProvider.kt:1158-1198` handled this
  defensively: dropped the affected items, counted them, set
  `complete=false` on the returned `DeltaPage`, and emitted a
  diagnostic WARN. The engine in turn correctly suppressed all delete
  actions on the pass (`TrackingEngine.kt:108-127`). End-to-end this
  is exactly the resilience pattern the engine documents.

## Concrete issues to file as BACKLOG entries

- **Internxt delta-page ancestor-uuid drop on first-pass enumeration is
  not self-healing on the next pass** — 288 items (168 files + 120
  folders) were dropped because their ancestor folder UUIDs were absent
  from the same page's `folderMap`; the WARN says "Dropped items recover
  when their ancestors next change or via `--reset`," which means a
  second `ts sync --dry-run` against an unchanged remote will produce
  the same incomplete plan and continue to suppress deletes
  indefinitely. Either the gather should re-fetch the ancestor on miss
  (synchronous resolve of `parentUuid → Folder`), or the cursor logic
  should re-enqueue ancestors on next pass. — `core/providers/internxt/src/main/kotlin/org/krost/unidrive/internxt/InternxtProvider.kt:1158-1198`

- **Tracking-set lemma test runtime is impractical for routine CI**
  — 62 min for a single `syncOnce()` against a 195k-file Internxt
  account makes this test unsuitable for any pre-commit / per-PR loop.
  Consider gating behind a separate Gradle task (e.g.
  `:app:sync-tracking:liveTest`) and/or capping enumeration via a test
  hook so the smoke variant runs in <5 min while a nightly variant
  runs full. — `core/app/sync-tracking/src/test/kotlin/org/krost/unidrive/tracking/TrackingEngineInternxtLiveTest.kt` (no specific line)

- **No JWT-refresh path exercised by the live test, despite 62-minute
  enumeration** — either token TTL exceeds 62 min on this profile or
  refresh fires silently; in neither case does the test prove the
  refresh path is functional. A future variant should force a
  refresh (short-TTL token, or assertion on a refresh-counter
  hook) so JWT-refresh-during-enumeration is structurally covered. —
  (no anchor — observed via log)

- **No 429-handling path exercised by the live test** — the natural
  rate of a single bulk enumeration didn't trip Internxt's rate
  limiter. The "429-storm interaction" listed in the BACKLOG entry
  remains unverified end-to-end; would need either a stress variant
  with parallel `delta()` callers or a fault-injection seam. —
  (no anchor — observed via log)

## Conclusion

The three structural assertions in `TrackingEngineInternxtLiveTest`
hold against the real Internxt provider on a profile with ~196k files:
the lemma (zero deletes from empty tracking), downloads-only plan
(195,740 `DownloadRemote` actions, nothing else), and no spurious
collisions on first scan. The engine also correctly fell into its
"enumeration incomplete → suppress all deletes" backup path when the
provider's delta-gather had to drop 288 items due to an ancestor-uuid
gap. The lemma plus the suppression path together gave the run two
independent reasons no deletes were emitted; either alone would have
been sufficient.

The verification half of the BACKLOG entry "Tracking-set engine:
verify Internxt provider end-to-end" is satisfied on the lemma /
downloads-only / no-crash axes. The two interaction surfaces the
BACKLOG entry calls out as engine-owned (429-storm, JWT-refresh)
were **not** structurally exercised on this run, so the verification
is partial on those axes — see the matching findings above. The
"malformed-delta" axis was exercised in the milder form of the
ancestor-uuid drop and the engine handled it as designed.

Follow-ups to file from this verification are listed above; the
ancestor-uuid drop is the only one that surfaces a likely real bug
in the provider client (potential perpetual incompleteness on an
unchanging remote).
