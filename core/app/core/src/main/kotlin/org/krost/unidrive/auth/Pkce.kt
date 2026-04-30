package org.krost.unidrive.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * UD-351: shared PKCE (Proof Key for Code Exchange, RFC 7636) helpers
 * for OAuth 2.0 authorization-code flows.
 *
 * Pre-UD-351 OneDrive and HiDrive `OAuthService` each shipped
 * word-for-word duplicate `generateCodeVerifier` + `generateCodeChallenge`
 * implementations: 32-byte SecureRandom random verifier, SHA-256
 * challenge, both URL-Base64-encoded without padding per RFC 7636.
 *
 * Lifted to `:app:core/auth/Pkce.kt` so future OAuth providers (S3
 * SSO, WebDAV bearer-token flows, Internxt OAuth flavours) inherit
 * the helpers without copy-paste.
 *
 * Reference: RFC 7636 Sections 4.1 and 4.2.
 */
public object Pkce {
    /**
     * Generate a fresh code verifier per RFC 7636 §4.1: 32 bytes of
     * cryptographically-random data, URL-Base64-encoded without
     * padding. Yields ~43 ASCII characters.
     *
     * Each authorization-code flow MUST use a freshly-generated
     * verifier. Callers store it locally (in memory) and feed the
     * derived [generateChallenge] to the authorization endpoint;
     * the verifier itself is sent to the token endpoint to prove
     * the same client started the flow.
     */
    public fun generateVerifier(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * Derive the code challenge from a [verifier] per RFC 7636 §4.2
     * (`S256` method): SHA-256 of the ASCII-encoded verifier,
     * URL-Base64-encoded without padding.
     */
    public fun generateChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }
}
