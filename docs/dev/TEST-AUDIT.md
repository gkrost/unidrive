# Test audit — challenge-test-assertion rubric

This document records the per-test triage outcome for tests under
`core/**/src/test/`, ticket UD-813. Each entry names the invariant the test
should be protecting (per the rubric in
[`.claude/skills/challenge-test-assertion/SKILL.md`](../../.claude/skills/challenge-test-assertion/SKILL.md))
and one of four verdicts:

- **healthy** — asserts the invariant. No change.
- **impl-anchored** — asserts an implementation detail. Should be rewritten
  to assert the invariant directly.
- **misleading** — name describes a different assertion than the body
  (UD-800 pattern). Should be split / renamed / strengthened.
- **delete** — asserts behaviour that isn't load-bearing. Tests the
  compiler or a trivial getter. Remove.

The exception clause from the rubric:

> For unit tests that are narrowly scoped to a pure function with obvious
> inputs/outputs — those are legitimately about the implementation because
> the implementation IS the contract.

Tests in this category are scored **healthy** even when they pin specific
output values.

## Status

| Module | Audited | Findings | Rewritten in this audit | Follow-ups filed |
|---|---|---|---|---|
| `:app:core` | 2026-05-13; batch A resolved 2026-05-14 (246769d) | 9 | 3 inline + 4 via UD-810 | UD-811 (still open: retry-budget renames + request-id; InFlightDedupTest part already done via UD-811's PR #15 rider) |
| `:app:sync` | — | — | — | — |
| `:app:cli` | — | — | — | — |
| `:app:benchmark` | — | — | — | — |
| `:app:e2e-360` | — | — | — | — |
| `:providers:*` | — | — | — | — |

## `:app:core` audit (2026-05-13)

16 files, 112 `@Test` methods. The matrix below names each finding plus the
invariant the test should be protecting. Tests not listed audited as
**healthy** by the rubric.

| File | Test | Verdict | Invariant the test should protect | Action |
|---|---|---|---|---|
| `SpiDiscoveryTest.kt` | `knownTypes includes standard 5 built-in providers` | misleading | The 5 canonical built-in provider ids resolve to known. | Rewrite (this audit). Body asserted only non-empty; name promised exactly the canonical set. |
| `SpiDiscoveryTest.kt` | `all returns discovered factories` | impl-anchored / vacuous, classpath-scope | Service loader returns at least one factory; every entry has non-blank id and displayName. | Rewrite + split (this audit). The `:app:core` test classpath does NOT include the provider modules (each provider lives in its own Gradle module), so `ProviderRegistry.all()` returns empty here — the original `if (factories.isNotEmpty())` was technically necessary for the test to compile, but it masked the real invariant. Split: (a) `:app:core` narrows the assertion to "every returned entry, whatever the count, has non-blank id + displayName" (the registry-layer structural contract, holds for any classpath state); (b) `:app:cli`'s new `ProviderRegistryDiscoveryTest.ServiceLoader discovers at least one provider on cli classpath` asserts the "at least one factory" invariant where every provider IS on the test classpath. |
| `ProviderMetadataTest.kt` | `getMetadata returns correct metadata when discovered` | impl-anchored / vacuous, classpath-scope | Discovered OneDrive metadata exposes the canonical display name + tier + CloudAct flag. | Rewrite + split (this audit). Same classpath-scope issue as above. `:app:core` test rewritten to "`getMetadata` is consistent with `get`" (pure registry contract, holds for any classpath state). The substantive "OneDrive → Microsoft OneDrive / Global / CloudAct=true" assertion lifted to `:app:cli`'s new `ProviderRegistryDiscoveryTest.OneDrive metadata exposes canonical display name tier and CloudAct flag`. |
| `ProviderMetadataTest.kt` | `allByTier returns sorted metadata` | misleading | `allByTier()` returns metadata sorted by the canonical tier order. | Resolved by 246769d. Renamed to `allByTier applies the canonical tier ordering`; rewritten as a synthesizing test that pins the canonical order constant (Local / DE-hosted / EU-hosted / Self-hosted / Global) without depending on the provider classpath. |
| `ProviderMetadataTest.kt` | `allMetadata returns list` | misleading | Every discovered metadata has non-blank id and displayName. | Resolved by 246769d. Renamed to `every discovered metadata has non-blank id and displayName`; the structural invariant is now plain on the tin. The "at least one entry discovered" pin lives in :app:cli's ProviderRegistryDiscoveryTest. |
| `ProviderMetadataTest.kt` | `ProviderMetadata stores all required fields` / `optional fields` / `data class equality and copy` | delete-candidate | Kotlin data-class equals/hashCode/copy contract. | Resolved by 246769d: deleted. Tests the compiler, not domain invariants. Plus `ShareInfo stores all fields` + `ShareInfo defaults` (same rationale, picked up in the same commit). |
| `CloudItemTest.kt` | `equal items have equal hashCodes` / `items differing in any field are not equal` / `hashCode is stable across calls` / `works correctly as HashMap key` | delete-candidate | Kotlin data-class equals/hashCode contract. | Resolved by 246769d: all four deleted. The business-invariant defaults tests (`deleted defaults to false`, `hydrated defaults to true`) remain. |
| `HttpRetryBudgetMatrixTest.kt` | `429 honors Retry-After capped by maxRetryAfter` | misleading | A 429 below the storm threshold does not open the circuit; a storm of 429s makes `resumeAfterEpochMs` honor the largest observed `Retry-After`. | Filed UD-811. Name promises a `maxRetryAfter` cap that does not exist in `HttpRetryBudget` (the matrix doc cell is vapor); body asserts the storm-open path. |
| `HttpRetryBudgetMatrixTest.kt` | `network IOException retries with exponential backoff` | misleading | `isRetriableIoException` classifies transient TCP failures as retriable and DNS/SSL misconfig as non-retriable. | Filed UD-811. Body does classification only; no backoff verification despite the name. |
| `InFlightDedupTest.kt` | `concurrent callers for same key share exactly one loader invocation` / `loader failure is rethrown to all callers …` | impl-anchored (timing) | Concurrent callers share one loader invocation; failure propagates without negative caching. | Filed UD-811. Same `Dispatchers.Default + delay(10) spin-wait` flake class as the now-fixed Internxt UD-205 test; should migrate to `runTest` + `testScheduler.advanceUntilIdle()`. |
| `RequestIdPluginTest.kt` | `every request carries X-Unidrive-Request-Id header` | impl-anchored (minor) | Every request carries a non-blank, unique `X-Unidrive-Request-Id`. | Filed UD-811. The exact-8-char assertion pins the current format; the invariant is "non-blank + unique per call". |
| `RequestIdPluginTest.kt` | `request attribute exposes the id for caller introspection` | impl-anchored / partially-vacuous | Builder-side `requestId()` returns the same id that the engine receives in the header. | Filed UD-811. The builder-side check is wrapped in `if (capturedFromBuilder != null)`, so a regression that returns null silently passes. |

### Inlined rewrites (this audit)

Three findings were strict improvements to the invariant assertion with no
behaviour-of-test-suite change beyond strengthening — done in the same
commit so the audit doc and the rewritten tests land together:

- `SpiDiscoveryTest.knownTypes includes standard 5 built-in providers` — body now
  iterates the canonical 5-provider set and asserts each is `isKnownType`.
- `SpiDiscoveryTest.all returns discovered factories` — renamed to
  `every discovered factory has non-blank id and displayName`. Drops the
  vacuous `if (factories.isNotEmpty())` wrapper; now pins the per-entry
  structural invariant that holds in any classpath state. The "at least one
  factory" check moves to `:app:cli`'s new `ProviderRegistryDiscoveryTest`.
- `ProviderMetadataTest.getMetadata returns correct metadata when discovered`
  — renamed to `getMetadata is consistent with get`. Drops the
  classpath-dependent OneDrive specifics (those move to the new
  `:app:cli` test); pins the registry-layer consistency contract instead.
- New file `core/app/cli/src/test/kotlin/.../ProviderRegistryDiscoveryTest.kt`
  — three tests exercising the "providers are actually discoverable" invariants
  on the `:app:cli` classpath: at-least-one-factory, every-factory-shape, and
  the OneDrive canonical-metadata pin.

### Follow-up tickets

- **UD-810** — Test cleanup: `:app:core` impl-anchored / misleading data-class
  and registry tests. Lower-risk batch.
- **UD-811** — Test cleanup: `:app:core` impl-anchored / misleading
  retry-budget, dedup, and request-id tests. Higher-touch; involves
  rewriting the dedup tests against `runTest` (UD-807 pattern).

## Rubric quick-reference

For every test under audit, answer:

1. **What invariant is this test protecting?** Name it in one sentence
   without reading the implementation.
2. **Would a reasonable alternative implementation satisfying the same
   invariant fail this test?** If yes → impl-anchored.
3. **If you deleted this test, what real-world harm would reach
   production?** If "none" → delete-candidate.

The full rubric, examples, and unhealthy-vs-healthy contrast table live in
[`.claude/skills/challenge-test-assertion/SKILL.md`](../../.claude/skills/challenge-test-assertion/SKILL.md).
