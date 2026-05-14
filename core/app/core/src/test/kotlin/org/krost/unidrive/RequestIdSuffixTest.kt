package org.krost.unidrive

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * UD-203: pin the `requestIdSuffix` log-formatting helper. Three
 * orthogonal invariants:
 *
 * 1. ProviderException with a non-null `requestId` renders
 *    ` requestId=<id>` (leading space, no trailing punctuation).
 * 2. ProviderException with a null `requestId` renders the empty
 *    string — so non-correlated provider failures don't pollute log
 *    lines with `requestId=null`.
 * 3. Non-ProviderException throwables render the empty string — the
 *    helper is safe to sprinkle into every catch site without an `is`
 *    check by the caller.
 *
 * Plus one null-safety case so a `null` argument doesn't crash the
 * helper.
 */
class RequestIdSuffixTest {
    @Test
    fun `non-null requestId renders the leading-space suffix`() {
        val ex = ProviderException("boom", requestId = "abc-123")
        assertEquals(" requestId=abc-123", requestIdSuffix(ex))
    }

    @Test
    fun `null requestId on a ProviderException renders the empty string`() {
        val ex = ProviderException("boom") // requestId defaults to null
        assertEquals("", requestIdSuffix(ex))
    }

    @Test
    fun `non-provider throwables render the empty string`() {
        // IllegalStateException is the workhorse for non-provider
        // failures the SyncEngine still catches; pin that no suffix
        // is added in that case.
        assertEquals("", requestIdSuffix(IllegalStateException("not from a provider")))
    }

    @Test
    fun `null argument is safe`() {
        assertEquals("", requestIdSuffix(null))
    }

    @Test
    fun `AuthenticationException carries requestId through to the suffix`() {
        val ex = AuthenticationException("auth failed", requestId = "auth-trace-789")
        assertEquals(" requestId=auth-trace-789", requestIdSuffix(ex))
    }
}
