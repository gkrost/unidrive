# Gradle daemon poison: `EOFException` from `SerializableTestResultStore` — stop the daemon first

**Surfaced 2026-05-25 during implementation of `docs/dev/specs/unidrive-daemon-design.md`.**

## Symptom

Every `:app:cli:test` invocation immediately fails with

```
java.io.EOFException
  at org.gradle.api.internal.tasks.testing.results.serializable.
      SerializableTestResultStore.openAndInitializeDecoder
```

reading a **0-byte `results-generic.bin`**. The test worker JVM never spawns. Unrelated
test classes fail with the same exception — the failure is not about your code.

## What poisons the daemon

A daemon wedged mid-write of the test-results store (observed after a custom task
registration was added to `core/app/cli/build.gradle.kts` during scratch debugging while
the daemon was running with the old task graph). The truncated 0-byte
`results-generic.bin` then fails the decoder on every subsequent test run.

## The three diagnostics that pointed there

1. The same code via a scratch `main()` on the gradle-managed JDK ran in ~1 s and produced
   correct output — nothing is wrong with the code or the JDK.
2. Unrelated test classes (e.g. `AnsiHelperTest`) failed with the **same** `EOFException`
   from the same store — the failure predates the test.
3. `core/app/cli/build/tmp/test results` (the `results-generic.bin` under the task's
   `SerializableTestResultStore` directory) was **0 bytes** — a truncated write.

## The fix

```bash
./gradlew --stop
```

immediately fixed both symptoms. Try this **first** when the test-run infrastructure fails
below the level of your code — it costs seconds, where the false-trail debugging here cost
about 25 minutes.

## Cost of not knowing

~25 minutes of false-trail debugging (task registration, test worker JVM, JDK) before
trying `./gradlew --stop`.
