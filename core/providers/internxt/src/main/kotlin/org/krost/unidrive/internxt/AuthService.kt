package org.krost.unidrive.internxt

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.auth.CredentialStore
import org.krost.unidrive.internxt.model.*
import java.io.BufferedReader
import java.io.InputStreamReader

open class AuthService(
    private val config: InternxtConfig,
    private val crypto: InternxtCrypto = InternxtCrypto(),
) : AutoCloseable {
    private val log = org.slf4j.LoggerFactory.getLogger(AuthService::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = HttpClient()
    private var credentials: InternxtCredentials? = null
    private val refreshMutex = Mutex()

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
            val payload =
                String(
                    java.util.Base64
                        .getUrlDecoder()
                        .decode(parts[1] + "=="),
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

    suspend fun authenticateInteractive(): InternxtCredentials {
        val email = readLine("Internxt email: ")
        if (email.isBlank()) throw AuthenticationException("No email provided")

        val passwordChars = readPassword("Password: ")
        if (passwordChars.isEmpty()) throw AuthenticationException("No password provided")
        val password = String(passwordChars)

        // Step 1: Login challenge
        val challengeResponse =
            httpClient.post("${InternxtConfig.API_BASE_URL}/auth/login") {
                contentType(ContentType.Application.Json)
                applyInternxtHeaders()
                setBody(
                    json.encodeToString(
                        kotlinx.serialization.json.JsonObject
                            .serializer(),
                        kotlinx.serialization.json.buildJsonObject { put("email", kotlinx.serialization.json.JsonPrimitive(email)) },
                    ),
                )
            }
        if (!challengeResponse.status.isSuccess()) {
            throw AuthenticationException("Login failed: ${challengeResponse.bodyAsText()}")
        }
        val challenge = json.decodeFromString<LoginChallengeResponse>(challengeResponse.bodyAsText())

        // Step 2: Check for 2FA
        var tfa: String? = null
        if (challenge.tfa) {
            tfa = readLine("2FA code: ")
        }

        // Step 3: Hash password with sKey
        val sKey = challenge.sKey ?: throw AuthenticationException("No sKey in login challenge")
        val hashedPassword = crypto.hashPassword(password, sKey, InternxtConfig.CRYPTO_KEY)

        // Step 4: Access token
        val accessBody =
            kotlinx.serialization.json.buildJsonObject {
                put("email", kotlinx.serialization.json.JsonPrimitive(email))
                put("password", kotlinx.serialization.json.JsonPrimitive(hashedPassword))
                if (tfa != null) put("tfa", kotlinx.serialization.json.JsonPrimitive(tfa))
            }
        val accessResponse =
            httpClient.post("${InternxtConfig.API_BASE_URL}/auth/login/access") {
                contentType(ContentType.Application.Json)
                applyInternxtHeaders()
                setBody(
                    json.encodeToString(
                        kotlinx.serialization.json.JsonObject
                            .serializer(),
                        accessBody,
                    ),
                )
            }
        if (!accessResponse.status.isSuccess()) {
            throw AuthenticationException("Authentication failed: ${accessResponse.bodyAsText()}")
        }
        val loginResult = json.decodeFromString<LoginAccessResponse>(accessResponse.bodyAsText())

        val rootFolderId =
            loginResult.user.rootFolderId
                ?: loginResult.user.rootFolderIdNum?.toString()
                ?: throw AuthenticationException("No root folder ID in login response")

        // Step 5: Decrypt mnemonic (API returns it encrypted with the plaintext password)
        val mnemonic = crypto.decryptMnemonic(loginResult.user.mnemonic, password)

        // Zero the password from memory
        passwordChars.fill('\u0000')

        credentials =
            InternxtCredentials(
                jwt = loginResult.newToken,
                mnemonic = mnemonic,
                rootFolderId = rootFolderId,
                email = email,
                bridgeUser = loginResult.user.bridgeUser ?: email,
                bridgeUserId = loginResult.user.userId ?: "",
                bucket = loginResult.user.bucket ?: "",
            )
        saveCredentials(credentials!!)
        return credentials!!
    }

    suspend fun getValidCredentials(): InternxtCredentials = credentials ?: throw AuthenticationException("Not authenticated")

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
        return refreshMutex.withLock {
            val current = credentials ?: throw AuthenticationException("Not authenticated")
            // Double-check: another coroutine may have already refreshed while we waited.
            if (current.jwt != stale.jwt) {
                return@withLock current
            }
            // UD-331: mirror the UD-310 OneDrive fix — wrap the network call AND
            // the saveCredentials in NonCancellable so a Pass-2 scope cancel on
            // a sibling's 401 can't abort the refresh between "got new JWT" and
            // "wrote it to disk." Without this, in-memory `credentials` disagrees
            // with what's persisted across crashes / process restart.
            val rotated =
                withContext(NonCancellable) {
                    val newJwt = fetchRefreshedJwt(current.jwt)
                    val updated = current.copy(jwt = newJwt)
                    saveCredentials(updated)
                    updated
                }
            credentials = rotated
            rotated
        }
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
