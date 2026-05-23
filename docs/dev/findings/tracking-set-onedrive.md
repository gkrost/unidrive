# Tracking-set engine — OneDrive end-to-end verification

End-to-end verification of `:app:sync-tracking` against a real OneDrive
profile. Closes the BACKLOG entry "Tracking-set engine: verify OneDrive
provider end-to-end" and the second half required to close the
Critical-tier tracking-set entry.

## Setup

- Repo branch: `verify/tracking-set-onedrive`, off `main` at `5f11bdb`.
- Daemon: `systemctl --user is-active unidrive.service` → `inactive`
  (no contention for OneDrive tokens, rate budget, or `state.db`).
- Profile under test: `posteo_onedrive`.
  `token.json` present at `/home/gernot/.config/unidrive/posteo_onedrive/token.json`
  (token refreshed by user immediately before agent dispatch; on-disk
  refresh-on-401 pattern from `OneDriveDeltaIntegrationTest` was used
  rather than the env-var bearer pattern from `LiveGraphIntegrationTest`).
  No `state.db` for this profile — first-ever live run for the
  tracking-set engine here. Real OneDrive sync root configured at
  `/home/gernot/Onedrive` (per `~/.config/unidrive/config.toml`); the
  test does NOT touch the user's sync root — it uses a fresh
  `createTempDirectory("ts-onedrive-live")` as its sync root.
- The default `OneDriveConfig()` constructor resolves `tokenPath` to
  `~/.config/unidrive/onedrive/` (the legacy single-profile default).
  The new live test constructs
  `OneDriveConfig(tokenPath = ~/.config/unidrive/posteo_onedrive)`
  explicitly so the profile token gets picked up rather than the stale
  one at the legacy default path.
- Baseline: `./gradlew :app:sync-tracking:test --rerun-tasks` against
  the pre-existing test set → `BUILD SUCCESSFUL in 9s`. No regressions
  on unit / `FakeTrackingProvider` tests after adding
  `testImplementation(project(":providers:onedrive"))` to the module's
  `build.gradle.kts` (mirror of the existing Internxt entry).
- Invocation:
  `cd core && UNIDRIVE_INTEGRATION_TESTS=true ./gradlew :app:sync-tracking:test
   --tests "org.krost.unidrive.tracking.TrackingEngineOneDriveLiveTest" -q --info`
- Full log captured at `/tmp/tracking-onedrive-run.log` (530 lines, not
  committed — gradle `--info` output, no tokens, but kept off-tree per
  AGENTS.md "long outputs to disk, concise chat updates").

## Test run

- Outcome: PASS.
  JUnit XML (`build/test-results/test/TEST-...OneDriveLiveTest.xml`):
  `tests="1" skipped="0" failures="0" errors="0" time="23.554"`.
- Wall clock for the test method (single `engine.syncOnce()` call):
  23.55 s (3232 files, full delta-page drain).
- Total Gradle build: `BUILD SUCCESSFUL in 25s`.
- Operator-facing summary line emitted by the test:
  `TrackingEngineOneDriveLiveTest: plan size=3232 (all DownloadRemote);
   adopted=0 (should be 0); collisions=0 (should be 0);
   remoteEnumerationComplete=true`.
- 3232 files visible to the engine via the OneDrive Graph delta endpoint.
  Two orders of magnitude smaller than the Internxt profile (~196k),
  so the runtime is dominated by network round-trips rather than the
  engine's hashing / per-path reconciliation. Folders and the root item
  are correctly filtered out by `OneDriveProvider.delta()` before the
  engine sees them.

## Observations (lemma)

INVARIANT 1 — **HOLDS**.

Empty local sync_root + empty `tracking.db` + populated remote (3232
files visible) → zero `PropagateLocalDelete` and zero
`PropagateRemoteDelete` actions emitted. Test's
`assertEquals(0, deletes, …)` passed.

The structural property the engine exists to prove is what the test
proves on this run against the real OneDrive Graph API: no path is a
candidate for deletion until the client has crossed the sync boundary
for it at least once. With an empty tracking set, deletion is
impossible by construction. Unlike the Internxt run, the OneDrive run
did **not** rely on the secondary `complete=false` suppression
backstop: enumeration completed cleanly
(`remoteEnumerationComplete=true`), so the lemma alone explains the
zero-delete outcome.

## Observations (downloads-only invariant)

INVARIANT 2 — **HOLDS**.

All 3232 actions in `report.plan` were `ReconcileAction.DownloadRemote`.
Test's `assertTrue(nonDownload.isEmpty(), …)` passed.

INVARIANT 3 — **HOLDS**.

Zero collisions surfaced. Test's
`assertTrue(report.collisions.isEmpty(), …)` passed. Correct:
collisions require both sides present with different content,
impossible when local is empty.

Adoption count is 0, as expected (same reason — adoption requires both
sides present with matching content).

No uncaught exception, no NPE, no `JsonDecodingException` — the
engine survived OneDrive's real `@odata.nextLink`-paged delta-response
shape with file/folder mix, the per-item deletion-state mix
(`@microsoft.graph.removed` state=deleted vs state=removed vs
unspecified), and root-item filtering. The "no-crash" axis is
satisfied.

## Observations (provider interaction — delta-cursor / 410-Gone-resync / FastBootstrap)

Search of the 530-line run log for `429`, `Retry-After`, `throttle`,
`401`, `refresh.*token`, `token.*refresh`, `expir`, `410`, `Gone`,
`resync`, `tokenInvalid`, `deltaLink`, `nextLink`, `FastBootstrap`,
`NullPointerException`, `JsonDecodingException`,
`IllegalStateException` returned **zero matches** other than gradle's
own task-scheduling noise. The 23.55 s pass produced no WARN-level or
ERROR-level log lines from any provider, engine, or HTTP-client
component.

- **Delta-cursor.** `OneDriveProvider.delta()` correctly returns
  `cursor = result.deltaLink ?: result.nextLink ?: ""` and the engine
  in `TrackingEngine.enumerateRemote()` drains the page chain
  in-memory via `while (page.hasMore) { provider.delta(page.cursor) }`.
  3232 items came back across whatever page count Graph chose. The
  engine acquired and used a cursor, then **discarded it** at end of
  pass — every future pass is a full re-enumeration. This is the
  known scope cut documented in `core/app/sync-tracking/README.md`
  ("Delta-cursor persistence. Every pass is a full remote
  enumeration… Real-provider work (Internxt, OneDrive) will need a
  per-profile cursor that survives across runs."). **Confirmed
  end-to-end:** the engine survives a real OneDrive delta enumeration,
  but does not yet exploit OneDrive's `@odata.deltaLink` for
  incremental subsequent passes. This is a feature gap, not a bug.
- **410-Gone-resync.** Not observed on this single first pass — the
  on-disk cursor was empty (no prior cursor to be invalidated). A 410
  Gone on the delta endpoint is what OneDrive returns when a stored
  cursor is too old or the drive has been re-keyed; with no cursor
  persistence yet, the engine has no surface area where a 410 could
  arrive. Code-path inspection: `GraphApiService.kt:521-542` handles
  410/404 explicitly **only for upload sessions** (`uploadLargeFile`);
  the `getDelta` call path has no explicit 410-resync. On a delta
  410 the engine would receive a `GraphApiException`,
  `TrackingEngine.enumerateRemote()` would catch the resulting
  `ProviderException` at `TrackingEngine.kt:348-351`, mark the pass
  `complete=false`, and the engine's existing
  "enumeration-incomplete → suppress all deletes" backup path at
  `TrackingEngine.kt:108-127` would protect the user. Whether the
  provider should *additionally* auto-resync (re-fetch from latest)
  on a 410 of the delta endpoint is a question for follow-up. **Not
  exercised on this run.**
- **FastBootstrap.** Verified the tracking-set engine path is
  **independent of FastBootstrap**. `OneDriveProvider` declares
  `Capability.FastBootstrap` (`OneDriveProvider.kt:51`), but
  `:app:sync-tracking/src/main/` contains zero references to either
  the `FastBootstrap` capability constant or the `fastBootstrap()`
  call. The engine reaches the provider only via `delta`,
  `getMetadata`, `download`, `upload`, and `delete` — the FastBootstrap
  path is a legacy-engine concept, not a tracking-engine concern. The
  BACKLOG entry's mention of "FastBootstrap interactions in the engine
  path" is satisfied negatively: there is no such interaction by
  construction.

## Concrete issues to file as BACKLOG entries

- **OneDrive delta cursor (`@odata.deltaLink`) acquired but discarded
  per pass — every tracking-set pass against a real OneDrive profile
  is a full re-enumeration** — the engine's
  `TrackingEngine.enumerateRemote()` drains the page chain via
  `provider.delta(cursor)` and then throws away the final `deltaLink`
  at end of pass. For a 3232-file profile this costs ~23 s round-trip
  per pass; for larger OneDrive accounts it scales linearly with
  remote inventory rather than with the delta since last pass. Known
  scope cut per `core/app/sync-tracking/README.md` ("What is
  intentionally NOT implemented yet" → "Delta-cursor persistence");
  filing here so it has a BACKLOG home now that real-provider
  verification is done. — `core/app/sync-tracking/src/main/kotlin/org/krost/unidrive/tracking/TrackingEngine.kt:309-353`

- **OneDrive provider has no explicit 410-Gone resync path on the
  delta endpoint** — `GraphApiService.kt` handles 404/410 explicitly
  only for upload-session reuse (`uploadLargeFile` two-attempt loop
  at `:521-542`); the `getDelta` code path at `:163-180` has no
  Gone-handling. The engine's
  `enumerateRemote → ProviderException → complete=false → suppress
  deletes` backup path protects the user from spurious deletes when
  this fires, but the engine then loops forever on
  enumeration-incomplete plans until something else triggers a
  resync. Consider auto-resync from latest on `410 Gone` in
  `OneDriveProvider.delta()` (the `deltaFromLatest()` capability
  already exists at `OneDriveProvider.kt:205-215` — the provider can
  reuse it). — `core/providers/onedrive/src/main/kotlin/org/krost/unidrive/onedrive/OneDriveProvider.kt:153-176`

- **No JWT / OAuth2-refresh path exercised by the live OneDrive test
  on this short pass** — the 23.55 s wall-clock didn't span a single
  token refresh. The user-supplied token was minutes-fresh at start
  of run. The standard OneDrive token TTL is ~60 min, so any
  enumeration of <60 min on a freshly-refreshed token will not
  structurally exercise the `TokenManager.getValidToken(forceRefresh)`
  401-recovery seam. Future longer-running variant (e.g. by stub
  enlargement of the profile, or by injecting a short-TTL token
  via test seam) would close this gap. **Cannot confirm
  refresh-during-enumeration code path is exercised by this test.** —
  (no anchor — observed via log)

- **No throttling / 429-handling path exercised by the live OneDrive
  test** — natural rate of a single 3232-file delta-page drain did
  not trip Microsoft Graph's per-app rate budget. Cannot confirm
  Retry-After-handling code path is exercised by this test. —
  (no anchor — observed via log)

## Conclusion

The three structural assertions in `TrackingEngineOneDriveLiveTest`
hold against the real OneDrive provider for the `posteo_onedrive`
profile (3232 files, OneDrive Graph delta endpoint): the lemma (zero
deletes from empty tracking), downloads-only plan (3232
`DownloadRemote` actions, nothing else), and no spurious collisions
on first scan. The engine completed enumeration cleanly
(`remoteEnumerationComplete=true`), so the lemma alone explains the
zero-delete outcome — the secondary `complete=false` suppression
backstop that fired on Internxt was not needed here.

The verification half of the BACKLOG entry "Tracking-set engine:
verify OneDrive provider end-to-end" is satisfied on the lemma /
downloads-only / no-crash axes, and on the FastBootstrap-independence
axis (the engine has no `Capability.FastBootstrap` call site by
construction). The delta-cursor and 410-Gone-resync axes called out
in the BACKLOG entry are partially verified: cursor is acquired and
used **within** the pass but not persisted **across** passes; 410
handling is not observable on a first pass with no stored cursor —
both are noted as concrete follow-ups above.

Combined with the Internxt findings, the Critical-tier tracking-set
entry is now end-to-end verified against both shipping providers.
The structural-safety story holds against real provider data on both
fronts. Follow-ups to file from this verification are listed above;
the cursor-persistence gap is the most likely to bite in practice
(every pass re-walks the full remote — fine for 3k files, not fine
for the 196k Internxt case).
