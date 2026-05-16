package org.krost.unidrive.sync

import kotlinx.serialization.KSerializer

/**
 * Shared test scaffolding for provider Snapshot tests (UD-356).
 *
 * Every provider's `XxxSnapshotTest` duplicated the same empty-snapshot
 * and invalid-base64 cases. They are lifted here so the five callers
 * can delegate via a one-line helper.
 *
 * Lives in the main source set (not `testFixtures`) because the `:app:sync`
 * module doesn't apply the `java-test-fixtures` plugin and the UD-356
 * scope forbids adding new plugins. Acceptable tradeoff: the helper is
 * tiny, has no runtime cost when unused, and keeps the test classpath
 * for every `:providers:*` module pointing at one source of truth.
 *
 * Round-trip tests stay per-provider because the entry shapes differ.
 */
object SnapshotTestSupport {
    /**
     * Verifies that encoding then decoding an empty snapshot yields an
     * empty `entries` map. Returns the decoded snapshot so callers can
     * assert further if they want.
     */
    fun <E> verifyEmptySnapshotRoundTrip(elementSerializer: KSerializer<E>): Snapshot<E> {
        val snapshot = Snapshot<E>(entries = emptyMap(), timestamp = 0L)
        val encoded = snapshot.encode(elementSerializer)
        val decoded = Snapshot.decode(encoded, elementSerializer)
        check(decoded.entries.isEmpty()) { "expected empty entries, got ${decoded.entries.size}" }
        return decoded
    }

    /**
     * Verifies that decoding a string that is not valid base64 throws.
     * Throws if decode unexpectedly succeeds.
     */
    fun <E> verifyInvalidBase64Rejects(elementSerializer: KSerializer<E>) {
        var threw = false
        try {
            Snapshot.decode("not-valid-base64!!!", elementSerializer)
        } catch (_: Exception) {
            threw = true
        }
        check(threw) { "decode of invalid base64 should have thrown" }
    }
}
