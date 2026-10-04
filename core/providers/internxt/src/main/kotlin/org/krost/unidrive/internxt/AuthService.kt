package org.krost.unidrive.internxt

import io.ktor.client.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.HttpDefaults
import org.krost.unidrive.TransientNetworkException
import org.krost.unidrive.UnidriveJson
import org.krost.unidrive.auth.CredentialStore
import org.krost.unidrive.auth.JwtExtractor
import org.krost.unidrive.auth.RefreshableTokenLatch
import org.krost.unidrive.internxt.model.*
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

open class AuthService(
    private val config: InternxtConfig,
    private val crypto: InternxtCrypto = InternxtCrypto(),
    // UD-204: install HttpTimeout so a slow-loris auth endpoint (the named
    // vector from the source ticket — internxt/sdk axios setup omits the
    // timeout) can't hang the whole sync indefinitely. Uses the same
    // HttpDefaults values as the other Ktor clients in the tree; the
    // four-class metadata/upload/download/auth matrix proposed in the
    // ticket body is deferred to a follow-up that touches all providers
    // together.
    //
    // A default-valued constructor arg so tests can pass a MockEngine-backed
    // HttpClient (same seam as OneDrive's OAuthService). Production uses the
    // default.
    private val httpClient: HttpClient =
        HttpClient {
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = HttpDefaults.CONNECT_TIMEOUT_MS
                socketTimeoutMillis = HttpDefaults.SOCKET_TIMEOUT_MS
                requestTimeoutMillis = HttpDefaults.REQUEST_TIMEOUT_MS
            }
        },
) : AutoCloseable {
    private val json = UnidriveJson
    private var credentials: InternxtCredentials? = null

    // UD-338: shared mutex + NonCancellable wrap lifted to :app:core/auth.
    private val refreshLatch = RefreshableTokenLatch()

    companion object {
        private const val CREDENTIALS_FILE = "credentials.json"

        // One-day pre-expiry refresh margin. Picked so that a sync running for
        // many hours never trips an interactive re-auth prompt: even on a JWT
        // with a 7-day lifetime, we have 6 days of clean operation followed by
        // 24 h during which any API call silently rotates the token. The
        // tighter OneDrive 5-minute margin doesn't translate — Internxt's
        // refresh path is reactive-401 today (no automatic replay), so a
        // missed pre-expiry refresh would surface as a hard failure mid-sync.
        const val JWT_REFRESH_MARGIN_MS: Long = 24L * 60 * 60 * 1000

        // A refresh error body is quoted at most this far; a Cloudflare error page
        // is tens of KB of HTML and the whole thing used to land in the log.
        private const val REFRESH_ERROR_BODY_CHARS = 200

        // `"retry_after": <seconds>` in Cloudflare / Internxt JSON error bodies.
        private val RETRY_AFTER_FIELD = Regex(""""retry_after"\s*:\s*(\d+)""")
        private val WHITESPACE_RUN = Regex("\\s+")
    }

    val isAuthenticated: Boolean get() = credentials != null

    val currentCredentials: InternxtCredentials?
        get() = credentials

    /** Check if the stored JWT is expired by decoding its payload. No network call. */
    fun isJwtExpired(): Boolean {
        val jwt = credentials?.jwt ?: return true
        // UD-010: body decode + exp extraction lifted to JwtExtractor.
        // UD-308 padding invariant lives in the shared impl now. Treat
        // unparseable / missing-exp as expired — same policy as before.
        val exp = JwtExtractor.extractExp(jwt) ?: return true
        return System.currentTimeMillis() / 1000 > exp
    }

    /**
     * Check whether the stored JWT will expire within [thresholdMs] of "now".
     * Returns true for absent / unparseable / missing-exp tokens (same
     * policy as [isJwtExpired]); returns true for already-expired tokens
     * too, so callers can treat this as a strict superset of [isJwtExpired].
     */
    fun isJwtNearExpiry(thresholdMs: Long): Boolean {
        val jwt = credentials?.jwt ?: return true
        val exp = JwtExtractor.extractExp(jwt) ?: return true
        return System.currentTimeMillis() + thresholdMs >= exp * 1000
    }

    suspend fun initialize() {
        credentials = loadCredentials()
    }

    private val stdinReader by lazy { BufferedReader(InputStreamReader(System.`in`)) }

    private fun readLine(prompt: String): String {
        System.console()?.let { console ->
            return console.readLine(prompt)?.trim()
                ?: throw AuthenticationException("No input provided")
        }
        print(prompt)
        System.out.flush()
        return stdinReader.readLine()?.trim()
            ?: throw AuthenticationException("No input provided")
    }

    private fun readPassword(prompt: String): CharArray {
        System.console()?.let { console ->
            return console.readPassword(prompt)
        }
        // Fallback: no console (e.g. Gradle run) — warn and read as plain text
        System.err.println("Warning: no console available — password will be echoed")
        return readLine(prompt).toCharArray()
    }

    /**
     * Non-interactive Internxt login. See spec
     * docs/superpowers/specs/2026-05-13-internxt-android-auth-flow-design.md
     * for the full contract.
     *
     * 2FA semantics:
     *   - If the challenge returns `tfa: false`, [tfaCode] is ignored.
     *   - If `tfa: true` and [tfaCode] is null, throws [TfaRequiredException]
     *     without making the access POST. Caller prompts and retries.
     *   - If `tfa: true` and [tfaCode] is non-null but rejected, throws
     *     [TfaInvalidException].
     *
     * Wrong email / password throws [BadCredentialsException] (from either
     * step 1 or step 4). Server-side rejections that aren't bad-creds or
     * TFA throw the generic [AuthenticationException]. [java.io.IOException]
     * subtypes (timeouts, DNS failures) propagate unwrapped so callers can
     * distinguish network failure from authentication failure.
     *
     * Request shape: when [tfaCode] is null, the `tfa` field is omitted from
     * the `/auth/login/access` POST body entirely (not sent as null / empty).
     */
    suspend fun authenticate(
        email: String,
        password: String,
        tfaCode: String? = null,
    ): InternxtCredentials {
        // Step 1: challenge.
        val challengeText = postLoginChallenge(email)
        val challenge = json.decodeFromString<LoginChallengeResponse>(challengeText)

        // Step 2: TFA gate.
        if (challenge.tfa && tfaCode == null) {
            throw TfaRequiredException()
        }

        // Step 3: password hash.
        val sKey = challenge.sKey ?: throw AuthenticationException("No sKey in login challenge")
        val hashedPassword = crypto.hashPassword(password, sKey, InternxtConfig.CRYPTO_KEY)

        // Step 4: access POST (omit `tfa` field when not provided).
        val accessBody = buildJsonObject {
            put("email", JsonPrimitive(email))
            put("password", JsonPrimitive(hashedPassword))
            if (tfaCode != null) put("tfa", JsonPrimitive(tfaCode))
        }
        val accessText = postLoginAccess(accessBody)
        val loginResult = json.decodeFromString<LoginAccessResponse>(accessText)

        val rootFolderId = loginResult.user.rootFolderId
            ?: loginResult.user.rootFolderIdNum?.toString()
            ?: throw AuthenticationException("No root folder ID in login response")

        // Step 5: decrypt mnemonic with the plaintext password.
        val mnemonic = crypto.decryptMnemonic(loginResult.user.mnemonic, password)

        val creds = InternxtCredentials(
            jwt = loginResult.newToken,
            mnemonic = mnemonic,
            rootFolderId = rootFolderId,
            email = email,
            bridgeUser = loginResult.user.bridgeUser ?: email,
            bridgeUserId = loginResult.user.userId ?: "",
            bucket = loginResult.user.bucket ?: "",
        )
        saveCredentials(creds)
        credentials = creds
        return creds
    }

    /**
     * HTTP seam: POST `/auth/login` with the given email; return the response body.
     * Throws [BadCredentialsException] on 4xx that indicates wrong credentials.
     * Test-overridable.
     */
    protected open suspend fun postLoginChallenge(email: String): String {
        val body = buildJsonObject {
            put("email", JsonPrimitive(email))
        }
        val response = httpClient.post("${InternxtConfig.API_BASE_URL}/auth/login") {
            contentType(ContentType.Application.Json)
            applyInternxtHeaders(config)
            setBody(json.encodeToString(JsonObject.serializer(), body))
        }
        if (response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden) {
            throw BadCredentialsException("Login challenge rejected: ${response.bodyAsText()}")
        }
        if (!response.status.isSuccess()) {
            throw AuthenticationException("Login challenge failed: ${response.bodyAsText()}")
        }
        return response.bodyAsText()
    }

    /**
     * HTTP seam: POST `/auth/login/access` with the given pre-built body; return the
     * response body. Throws [BadCredentialsException] for wrong-password 401, or
     * [TfaInvalidException] when the response body indicates 2FA rejection. The
     * caller is responsible for constructing the body with the right shape
     * (email + hashed password + optional `tfa`). Test-overridable.
     */
    protected open suspend fun postLoginAccess(
        body: JsonObject,
    ): String {
        val response = httpClient.post("${InternxtConfig.API_BASE_URL}/auth/login/access") {
            contentType(ContentType.Application.Json)
            applyInternxtHeaders(config)
            setBody(json.encodeToString(JsonObject.serializer(), body))
        }
        if (!response.status.isSuccess()) {
            val bodyText = response.bodyAsText()
            // Internxt's API returns 401 for both wrong-password and wrong-TFA.
            // Distinguish by whether the request carried a tfa field.
            val carriedTfa = body.containsKey("tfa")
            if (response.status == HttpStatusCode.Unauthorized) {
                if (carriedTfa) throw TfaInvalidException("Invalid 2FA code: $bodyText")
                throw BadCredentialsException("Wrong password: $bodyText")
            }
            throw AuthenticationException("Access failed: $bodyText")
        }
        return response.bodyAsText()
    }

    suspend fun authenticateInteractive(): InternxtCredentials {
        val email = readLine("Internxt email: ")
        if (email.isBlank()) throw AuthenticationException("No email provided")

        val passwordChars = readPassword("Password: ")
        if (passwordChars.isEmpty()) throw AuthenticationException("No password provided")
        val password = String(passwordChars)

        // passwordChars stays populated during the readLine prompts between
        // retries. The outer finally guarantees zeroing on any exit (success,
        // BadCredentialsException, TfaInvalid exhaustion, or unexpected
        // throw). `password` (the immutable String above) is GC's problem
        // regardless; the CLI tool is short-lived enough that this is
        // acceptable. A longer-running daemon would route through
        // SecureCredentialStore.
        try {
            try {
                return authenticate(email, password, tfaCode = null)
            } catch (_: TfaRequiredException) {
                // First attempt told us 2FA is required; prompt and retry up to 3 times.
            }

            var attempts = 0
            while (attempts < 3) {
                val tfaCode = readLine("2FA code: ")
                try {
                    return authenticate(email, password, tfaCode = tfaCode)
                } catch (_: TfaInvalidException) {
                    attempts++
                    if (attempts >= 3) throw AuthenticationException("Too many invalid 2FA attempts")
                    System.err.println("Invalid 2FA code, please try again.")
                }
            }
            throw AuthenticationException("Too many invalid 2FA attempts")
        } finally {
            passwordChars.fill('\u0000')
        }
    }

    /**
     * Returns the stored credentials, proactively refreshing the JWT if it has
     * expired or is within [JWT_REFRESH_MARGIN_MS] of expiry.
     *
     * UD-308: pre-UD-308 this just returned `credentials` or threw, leaving the
     * refresh to happen reactively when a downstream request came back 401. That
     * cost every cold-start-after-long-idle call an extra HTTP round-trip
     * (request → 401 → refresh → retry). Checking [isJwtNearExpiry] up front
     * lets us refresh once and skip the doomed request entirely. The day-of
     * margin extends the same logic to long-running syncs: a token with five
     * minutes left should be rotated *now* rather than mid-stream.
     *
     * [forceRefresh] is the reactive 401-replay knob mirroring OneDrive's
     * `TokenManager.getValidToken(forceRefresh)`. When the API layer sees an
     * unexpected 401 mid-request (the server rotated the token earlier than
     * our pre-expiry margin predicted, e.g. tenant policy change), it replays
     * the call after asking for `forceRefresh = true`. Concurrent forced
     * callers still coalesce inside [refreshToken]'s [RefreshableTokenLatch],
     * so the second-and-Nth replay observe the post-refresh value without
     * issuing redundant HTTP refreshes.
     *
     * Refresh goes through [refreshToken], which serialises concurrent callers via
     * [RefreshableTokenLatch] (UD-338) — we deliberately do not duplicate any of
     * that machinery here.
     *
     * Absent credentials (never authenticated) still throw [AuthenticationException]
     * — the contract for that case is unchanged.
     */
    suspend fun getValidCredentials(forceRefresh: Boolean = false): InternxtCredentials {
        val current = credentials ?: throw AuthenticationException("Not authenticated")
        // Refresh both for already-expired tokens (UD-308 cold-start fast path)
        // and for tokens within the effective margin of expiry (see
        // [effectiveRefreshMarginMs]). The margin prevents the case where a
        // long-running sync starts with a 5-minute-remaining token, then trips
        // the un-replayed 401 path mid-stream and surfaces an interactive
        // re-auth prompt to the user.
        return if (forceRefresh || isJwtNearExpiry(effectiveRefreshMarginMs())) refreshToken() else current
    }

    /**
     * #334: the pre-expiry margin in effect for the stored JWT. The fixed
     * [JWT_REFRESH_MARGIN_MS] assumes a token lifetime well above 24 h; if the
     * vendor shortens the lifetime below it, every fresh token counts as near
     * expiry and each [getValidCredentials] burns a sequential /users/refresh
     * round-trip (the latch coalesces concurrent callers, not sequential ones)
     * — a permanent refresh storm doubling latency and inviting 429s.
     *
     * The margin therefore clamps to a quarter of the observed lifetime
     * (`exp - iat`, decoded from the JWT): min(24 h, lifetime / 4). Tokens
     * without those claims (or unparseable ones) keep the fixed margin —
     * today's behaviour. A quarter keeps the UD-308 shape: a long clean runway
     * followed by a wide proactive-rotation window, scaled to the actual token.
     */
    private fun effectiveRefreshMarginMs(): Long {
        val jwt = credentials?.jwt ?: return JWT_REFRESH_MARGIN_MS
        val body = JwtExtractor.decodeBody(jwt) ?: return JWT_REFRESH_MARGIN_MS
        val exp = body["exp"]?.jsonPrimitive?.longOrNull ?: return JWT_REFRESH_MARGIN_MS
        val iat = body["iat"]?.jsonPrimitive?.longOrNull ?: return JWT_REFRESH_MARGIN_MS
        val lifetimeMs = (exp - iat).coerceAtLeast(0L) * 1000L
        return minOf(JWT_REFRESH_MARGIN_MS, lifetimeMs / 4)
    }

    /**
     * Network seam for the refresh roundtrip. Protected + open so tests can override
     * with a counting fake without pulling a full HTTP mock into the test classpath.
     * Production callers MUST go through [refreshToken], never this directly — it is
     * the serialization boundary that makes the double-checked locking work.
     */
    protected open suspend fun fetchRefreshedJwt(currentJwt: String): String {
        val response =
            httpClient.get("${InternxtConfig.API_BASE_URL}/users/refresh") {
                bearerAuth(currentJwt)
                applyInternxtHeaders(config)
            }
        if (!response.status.isSuccess()) {
            val status = response.status.value
            val body = response.bodyAsText()
            val requestId = response.headers["x-request-id"]
            val retryHint =
                response.headers[HttpHeaders.RetryAfter]?.trim()?.takeIf { it.isNotEmpty() }?.let { "Retry-After: $it" }
                    ?: RETRY_AFTER_FIELD.find(body)?.let { "retry_after: ${it.groupValues[1]}" }
            val detail =
                "Token refresh failed (HTTP $status)" +
                    (retryHint?.let { ", $it" } ?: "") +
                    ": " + body.take(REFRESH_ERROR_BODY_CHARS).replace(WHITESPACE_RUN, " ").trim()
            // 408 / 429 / 5xx say nothing about the token: a gateway hiccup (live: a
            // Cloudflare 502 `retryable:true, retry_after:60`) must not read as
            // "expired, re-authenticate" — callers treat AuthenticationException as
            // fatal and a watch daemon would stop. 401/403 (and any other 4xx) really
            // are a rejected token. The retry hint is information only; the caller's
            // own backoff decides when to try again.
            if (status == 408 || status == 429 || status in 500..599) {
                throw TransientNetworkException(detail, requestId = requestId)
            }
            throw AuthenticationException(detail, requestId = requestId)
        }
        return json.decodeFromString<TokenRefreshResponse>(response.bodyAsText()).newToken
    }

    /**
     * Refresh the stored JWT. Concurrent callers are serialized through [refreshMutex]
     * and double-check the in-memory jwt after acquiring the lock: if another coroutine
     * has already rotated the token while we were waiting, we return the fresh value
     * instead of making a redundant (and potentially invalidating) second HTTP call.
     */
    suspend fun refreshToken(): InternxtCredentials {
        val stale = credentials ?: throw AuthenticationException("Not authenticated")
        // UD-338: serialisation + NonCancellable wrap delegated to
        // RefreshableTokenLatch (replaces the inline `refreshMutex.withLock {
        // ... withContext(NonCancellable) { ... } }` pattern that mirrored
        // UD-310/UD-331 by hand). Double-check predicate (`jwt != stale.jwt`)
        // stays here because it's the Internxt-specific "did someone else
        // already refresh?" answer.
        return refreshLatch.withRefresh(
            isAlreadyFresh = {
                val current = credentials ?: throw AuthenticationException("Not authenticated")
                if (current.jwt != stale.jwt) current else null
            },
            body = {
                val current = credentials ?: throw AuthenticationException("Not authenticated")
                val newJwt = fetchRefreshedJwt(current.jwt)
                val updated = current.copy(jwt = newJwt)
                saveCredentials(updated)
                credentials = updated
                updated
            },
        )
    }

    suspend fun logout() {
        credentials = null
        credentialStore.delete()
    }

    // UD-344: load / save / delete go through the shared `CredentialStore`.
    // Pre-UD-344 this was a non-atomic `Files.writeString` — UD-312's atomic-move
    // race-window fix only existed in OneDrive's copy. Lifting closes the gap
    // for Internxt without copy-pasting the logic.
    //
    // Note: credentials include mnemonic in plaintext (file is chmod 600).
    // For additional protection, use: unidrive vault encrypt
    private val credentialStore: CredentialStore<InternxtCredentials> =
        CredentialStore(
            dir = config.tokenPath,
            fileName = CREDENTIALS_FILE,
            serializer = InternxtCredentials.serializer(),
        )

    private fun loadCredentials(): InternxtCredentials? = credentialStore.load()

    private fun saveCredentials(creds: InternxtCredentials) {
        credentialStore.save(creds)
    }

    override fun close() {
        httpClient.close()
    }
}
