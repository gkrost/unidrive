package org.krost.unidrive.internxt

import io.ktor.client.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.HttpDefaults
import org.krost.unidrive.auth.CredentialStore
import org.krost.unidrive.auth.RefreshableTokenLatch
import org.krost.unidrive.internxt.model.*
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

open class AuthService(
    private val config: InternxtConfig,
    private val crypto: InternxtCrypto = InternxtCrypto(),
) : AutoCloseable {
    private val log = org.slf4j.LoggerFactory.getLogger(AuthService::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    // UD-204: install HttpTimeout so a slow-loris auth endpoint (the named
    // vector from the source ticket — internxt/sdk axios setup omits the
    // timeout) can't hang the whole sync indefinitely. Uses the same
    // HttpDefaults values as the other Ktor clients in the tree; the
    // four-class metadata/upload/download/auth matrix proposed in the
    // ticket body is deferred to a follow-up that touches all providers
    // together.
    private val httpClient = HttpClient {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = HttpDefaults.CONNECT_TIMEOUT_MS
            socketTimeoutMillis = HttpDefaults.SOCKET_TIMEOUT_MS
            requestTimeoutMillis = HttpDefaults.REQUEST_TIMEOUT_MS
        }
    }
    private var credentials: InternxtCredentials? = null

    // UD-338: shared mutex + NonCancellable wrap lifted to :app:core/auth.
    private val refreshLatch = RefreshableTokenLatch()

    companion object {
        private const val CREDENTIALS_FILE = "credentials.json"
    }

    val isAuthenticated: Boolean get() = credentials != null

    val currentCredentials: InternxtCredentials?
        get() = credentials

    /** Check if the stored JWT is expired by decoding its payload. No network call. */
    fun isJwtExpired(): Boolean {
        val jwt = credentials?.jwt ?: return true
        return try {
            val parts = jwt.split(".")
            if (parts.size < 2) return true
            // UD-308 follow-up (PR #23 Codex P2): base64url payload segments
            // need padding so the total length is a multiple of 4. The previous
            // `+ "=="` only worked when `raw.length % 4 == 2`; for `% 4` in
            // {0, 3} the decode threw and the catch returned `true` ("treat
            // as expired"). Once UD-308 wired `isJwtExpired()` into
            // `getValidCredentials()`, that quirk would force a refresh
            // round-trip on every API call for JWTs with non-{4k+2} payload
            // lengths. Pad based on `length % 4` instead.
            val raw = parts[1]
            val padding = (4 - raw.length % 4) % 4
            val payload =
                String(
                    java.util.Base64
                        .getUrlDecoder()
                        .decode(raw + "=".repeat(padding)),
                )
            val exp =
                json
                    .parseToJsonElement(payload)
                    .jsonObject["exp"]
                    ?.jsonPrimitive
                    ?.longOrNull ?: return true
            System.currentTimeMillis() / 1000 > exp
        } catch (_: Exception) {
            true // can't decode → treat as expired
        }
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
            applyInternxtHeaders()
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
            applyInternxtHeaders()
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

        try {
            return authenticate(email, password, tfaCode = null)
                .also { passwordChars.fill('\u0000') }
        } catch (_: TfaRequiredException) {
            // First attempt told us 2FA is required; prompt and retry up to 3 times.
        }

        // Note: passwordChars stays populated during the readLine prompts
        // between retries. Best-effort zeroing on exit; `password` (the
        // immutable String built on line 530) is GC's problem regardless.
        // The CLI tool is short-lived enough that this is acceptable; a
        // longer-running daemon would route through SecureCredentialStore.
        var attempts = 0
        while (attempts < 3) {
            val tfaCode = readLine("2FA code: ")
            try {
                return authenticate(email, password, tfaCode = tfaCode)
                    .also { passwordChars.fill('\u0000') }
            } catch (_: TfaInvalidException) {
                attempts++
                if (attempts >= 3) {
                    passwordChars.fill('\u0000')
                    throw AuthenticationException("Too many invalid 2FA attempts")
                }
                System.err.println("Invalid 2FA code, please try again.")
            }
        }
        passwordChars.fill('\u0000')
        throw AuthenticationException("Too many invalid 2FA attempts")
    }

    /**
     * Returns the stored credentials, proactively refreshing the JWT if it has expired.
     *
     * UD-308: pre-UD-308 this just returned `credentials` or threw, leaving the
     * refresh to happen reactively when a downstream request came back 401. That
     * cost every cold-start-after-long-idle call an extra HTTP round-trip
     * (request → 401 → refresh → retry). Checking [isJwtExpired] up front lets us
     * refresh once and skip the doomed request entirely.
     *
     * Refresh goes through [refreshToken], which serialises concurrent callers via
     * [RefreshableTokenLatch] (UD-338) — we deliberately do not duplicate any of
     * that machinery here.
     *
     * Absent credentials (never authenticated) still throw [AuthenticationException]
     * — the contract for that case is unchanged.
     */
    suspend fun getValidCredentials(): InternxtCredentials {
        val current = credentials ?: throw AuthenticationException("Not authenticated")
        return if (isJwtExpired()) refreshToken() else current
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
                applyInternxtHeaders()
            }
        if (!response.status.isSuccess()) {
            throw AuthenticationException("Token refresh failed: ${response.bodyAsText()}")
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
