package org.krost.unidrive.auth

import org.krost.unidrive.AuthenticationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * UD-348: tests ported from the per-provider `TokenManagerTest` copies
 * (OneDrive + HiDrive). Pre-lift the same six tests existed twice; now
 * they cover the shared parser once.
 */
class OAuthCallbackServerTest {
    private val provider = "Azure"

    @Test
    fun `parseAndValidateCallback returns code when state matches`() {
        val requestLine = "GET /?code=abc123&state=expected-state HTTP/1.1"
        val code = parseAndValidateCallback(requestLine, expectedState = "expected-state", providerLabel = provider)
        assertEquals("abc123", code)
    }

    @Test
    fun `parseAndValidateCallback rejects missing state as CSRF`() {
        val requestLine = "GET /?code=abc123 HTTP/1.1"
        val ex =
            assertFailsWith<AuthenticationException> {
                parseAndValidateCallback(requestLine, expectedState = "expected-state", providerLabel = provider)
            }
        // Error message must identify the CSRF failure mode, not the code path.
        assert(ex.message?.contains("state", ignoreCase = true) == true) {
            "Expected state-related error message, got: ${ex.message}"
        }
    }

    @Test
    fun `parseAndValidateCallback rejects mismatched state as CSRF`() {
        val requestLine = "GET /?code=abc123&state=attacker-state HTTP/1.1"
        val ex =
            assertFailsWith<AuthenticationException> {
                parseAndValidateCallback(requestLine, expectedState = "expected-state", providerLabel = provider)
            }
        assert(ex.message?.contains("state", ignoreCase = true) == true) {
            "Expected state-related error message, got: ${ex.message}"
        }
    }

    @Test
    fun `parseAndValidateCallback still rejects missing code`() {
        val requestLine = "GET /?state=expected-state HTTP/1.1"
        assertFailsWith<AuthenticationException> {
            parseAndValidateCallback(requestLine, expectedState = "expected-state", providerLabel = provider)
        }
    }

    @Test
    fun `parseAndValidateCallback still surfaces provider error param`() {
        val requestLine = "GET /?error=access_denied&error_description=User%20cancelled HTTP/1.1"
        val ex =
            assertFailsWith<AuthenticationException> {
                parseAndValidateCallback(requestLine, expectedState = "expected-state", providerLabel = provider)
            }
        assert(ex.message?.contains("access_denied") == true) {
            "Expected provider error to surface, got: ${ex.message}"
        }
    }

    @Test
    fun `parseAndValidateCallback order-independent state and code`() {
        // Real callbacks may arrive with params in any order; validator must not depend on it.
        val requestLine = "GET /?state=expected-state&code=abc123 HTTP/1.1"
        val code = parseAndValidateCallback(requestLine, expectedState = "expected-state", providerLabel = provider)
        assertEquals("abc123", code)
    }

    @Test
    fun `parseAndValidateCallback uses providerLabel as prefix in error message`() {
        val requestLine = "GET /?error=access_denied&error_description=User%20cancelled HTTP/1.1"
        val azureEx =
            assertFailsWith<AuthenticationException> {
                parseAndValidateCallback(requestLine, expectedState = "expected-state", providerLabel = "Azure")
            }
        assert(azureEx.message?.startsWith("Azure returned error") == true) {
            "Expected Azure-prefixed error, got: ${azureEx.message}"
        }
        val hidriveEx =
            assertFailsWith<AuthenticationException> {
                parseAndValidateCallback(requestLine, expectedState = "expected-state", providerLabel = "HiDrive")
            }
        assert(hidriveEx.message?.startsWith("HiDrive returned error") == true) {
            "Expected HiDrive-prefixed error, got: ${hidriveEx.message}"
        }
    }
}
