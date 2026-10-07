package org.krost.unidrive.sync

import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.krost.unidrive.io.OwnerOnly
import org.krost.unidrive.io.grantProblem
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Authentication of the daemon's IPC connections and the scope of what each may call: IPC protocol
 * version 2, specified in docs/dev/specs/ipc-authentication.md.
 *
 * At every start the daemon writes two random tokens into the profile's config folder ([issue]):
 * `ipc.token` (scope `full`) and `ipc.read.token` (scope `read`). A connection proves that it read
 * one of them with a two-step handshake (`hello`, `hello.proof`) to which both sides contribute a
 * nonce, and the daemon proves the same back; the token itself never travels on the wire. Until then
 * the connection may only ask `daemon.status`, which gets a minimal reply. After it, a `read`
 * connection may call the read class of verbs only ([VERB_CLASSES], unlisted verbs are `admin`).
 *
 * [IpcServer] keeps one [Session] per connection and asks it before every handler lookup.
 */
class IpcAuth(
    private val profileName: String,
    fullToken: ByteArray,
    readToken: ByteArray,
    private val engineVersion: String,
    // Test seams: the server nonce source, the unauthenticated timeout, the failure delay, the clock.
    private val serverNonces: () -> ByteArray = { randomBytes(NONCE_BYTES) },
    private val unauthenticatedTimeoutMs: Long = UNAUTHENTICATED_TIMEOUT_MS,
    private val failureDelayMs: Long = FAILURE_DELAY_MS,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    enum class Scope(
        val wire: String,
    ) {
        FULL("full"),
        READ("read"),
        ;

        companion object {
            fun ofWire(wire: String?): Scope? = entries.firstOrNull { it.wire == wire }
        }
    }

    enum class VerbClass { READ, WRITE, ADMIN }

    /** What the server writes instead of calling a handler, and whether it closes the connection after it. */
    data class Reply(
        val json: String,
        val close: Boolean = false,
    )

    /** The token files cannot be created or verified; the daemon does not open its socket. */
    class StartupRefused(
        reason: String,
        path: Path,
    ) : IOException("$STARTUP_REFUSED_PREFIX $reason: $path")

    private val keys: Map<Scope, ByteArray> = mapOf(Scope.FULL to fullToken.copyOf(), Scope.READ to readToken.copyOf())

    init {
        require(keys.values.all { it.size == TOKEN_BYTES }) { "an IPC token is $TOKEN_BYTES bytes" }
    }

    private val minimalStatus =
        """{"ok":true,"protocol_version":$PROTOCOL_VERSION,"engine_version":${JsonPrimitive(engineVersion)},"auth_required":true}"""

    fun newSession(): Session = Session()

    /**
     * The gate for one request of a connection; [session] is the one the server keeps for it. A
     * connection without a session (it is being closed) is refused and closed, never let through.
     */
    suspend fun gate(
        session: Session?,
        verb: String?,
        line: String,
    ): Reply? = if (session == null) Reply(AUTH_REQUIRED, close = true) else session.gate(verb, line)

    /** The authentication state of one connection. Used by that connection's reader only (no locking). */
    inner class Session internal constructor() {
        private val openedAt = nanoTime()
        private var pending: Pending? = null
        private var failures = 0

        /** Set once the handshake succeeded; null before. */
        var scope: Scope? = null
            private set

        /** True when the connection is still unauthenticated after the timeout; the server then closes it. */
        fun timedOut(): Boolean = scope == null && nanoTime() - openedAt >= unauthenticatedTimeoutMs * 1_000_000L

        /**
         * Null when the request goes on to its handler; otherwise the reply to write instead. [verb] is
         * what the server dispatches on, [line] the whole request.
         */
        suspend fun gate(
            verb: String?,
            line: String,
        ): Reply? {
            val granted = scope
            if (granted != null) return checkScope(granted, verb, line)
            return when (verb) {
                HELLO -> hello(line)
                HELLO_PROOF -> proof(line)
                "daemon.status" -> Reply(minimalStatus)
                else -> Reply(AUTH_REQUIRED)
            }
        }

        private fun checkScope(
            granted: Scope,
            verb: String?,
            line: String,
        ): Reply? {
            if (granted == Scope.FULL || verb == null) return null
            // The dispatcher's verb and a structured reading of the request must name the same verb:
            // a handler that reads the verb itself must never act on another one than was checked.
            if (classOf(verb) == VerbClass.READ && singleTopLevelVerb(line) == verb) return null
            return Reply("""{"ok":false,"error":"forbidden","scope":"${granted.wire}"}""")
        }

        private suspend fun hello(line: String): Reply {
            pending = null
            val request = parseObject(line) ?: return fail("hello is not a JSON object")
            val protocol = (request["protocol"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            if (protocol != PROTOCOL_VERSION) return fail("hello asks for protocol ${request["protocol"]}")
            val scope = Scope.ofWire(request.string("scope")) ?: return fail("hello names no known scope")
            val client = request.string("client")
            if (client == null || !validClientName(client)) return fail("hello carries no valid client name")
            val clientNonce = request.string("nonce")
            if (clientNonce == null || decode(clientNonce)?.size != NONCE_BYTES) return fail("hello carries no valid nonce")
            val serverNonce = encode(serverNonces())
            pending = Pending(scope, client, clientNonce, serverNonce)
            return Reply("""{"ok":true,"step":1,"snonce":"$serverNonce"}""")
        }

        private suspend fun proof(line: String): Reply {
            // The server nonce is single use: whatever happens next, this hello is spent.
            val hello = pending ?: return fail("hello.proof without a hello")
            pending = null
            val proof = parseObject(line)?.string("proof") ?: return fail("hello.proof carries no proof")
            // The key is the token of the scope the hello asked for.
            val key = keys.getValue(hello.scope)
            val expected = clientProof(key, hello.scope.wire, profileName, hello.clientNonce, hello.serverNonce)
            if (!constantTimeEquals(expected, proof)) return fail("the proof does not match the ${hello.scope.wire} token")
            scope = hello.scope
            log.debug("IPC: client '{}' authenticated, scope {}", hello.client, hello.scope.wire)
            val serverProof = serverProof(key, hello.scope.wire, profileName, hello.clientNonce, hello.serverNonce, expected)
            return Reply("""{"ok":true,"scope":"${hello.scope.wire}","protocol_version":$PROTOCOL_VERSION,"proof":"$serverProof"}""")
        }

        private suspend fun fail(reason: String): Reply {
            failures++
            log.warn("IPC: handshake refused ({}), failure {} of {}", reason, failures, MAX_FAILURES)
            delay(failureDelayMs)
            return Reply(AUTH_FAILED, close = failures >= MAX_FAILURES)
        }
    }

    private class Pending(
        val scope: Scope,
        val client: String,
        val clientNonce: String,
        val serverNonce: String,
    )

    companion object {
        private val log = LoggerFactory.getLogger(IpcAuth::class.java)

        /** The IPC wire protocol version; 2 is the first with authentication. */
        const val PROTOCOL_VERSION: Int = 2
        const val TOKEN_FILE: String = "ipc.token"
        const val READ_TOKEN_FILE: String = "ipc.read.token"
        const val TOKEN_BYTES: Int = 32
        const val NONCE_BYTES: Int = 16
        const val UNAUTHENTICATED_TIMEOUT_MS: Long = 5_000
        const val FAILURE_DELAY_MS: Long = 250
        const val MAX_FAILURES: Int = 3
        const val MAX_CLIENT_NAME: Int = 64

        /** `daemon run` and `sync` exit with this code when they refuse to start without their IPC (EX_CONFIG). */
        const val STARTUP_REFUSED_EXIT_CODE: Int = 78
        const val STARTUP_REFUSED_PREFIX: String = "IPC startup refused:"
        const val HELLO: String = "hello"
        const val HELLO_PROOF: String = "hello.proof"

        private const val AUTH_REQUIRED = """{"ok":false,"error":"auth_required"}"""
        private const val AUTH_FAILED = """{"ok":false,"error":"auth_failed"}"""
        private const val MOVE_ATTEMPTS = 20
        private const val MOVE_RETRY_MS = 50L

        /**
         * The verb class of every verb the daemon registers, keyed by the exact wire string. Scope `read`
         * may call the read class only, scope `full` everything. A verb missing here is `admin`
         * ([classOf]); IpcContractCorpusTest keeps this table equal to the contract corpus.
         */
        val VERB_CLASSES: Map<String, VerbClass> =
            buildMap {
                listOf(
                    "daemon.status", "hydration.list", "hydration.last_synced", "hydration.subscribe",
                    "sync.subscribe", "hydration.open_read",
                ).forEach { put(it, VerbClass.READ) }
                listOf(
                    "hydration.hydrate", "hydration.dehydrate", "hydration.create", "hydration.open_write",
                    "hydration.open_write_begin", "hydration.close_handle", "hydration.mkdir", "hydration.unlink",
                    "hydration.rmdir", "hydration.rename", "hydration.cancel", "sync.enumerate",
                ).forEach { put(it, VerbClass.WRITE) }
                listOf("daemon.shutdown", "refresh.run").forEach { put(it, VerbClass.ADMIN) }
            }

        fun classOf(verb: String): VerbClass = VERB_CLASSES[verb] ?: VerbClass.ADMIN

        fun tokenFile(
            profileDir: Path,
            scope: Scope,
        ): Path = profileDir.resolve(if (scope == Scope.FULL) TOKEN_FILE else READ_TOKEN_FILE)

        /**
         * Writes new tokens for both scopes into [profileDir] (the folder that holds credentials.json)
         * and returns the server side that checks them. Called at every daemon start, before the socket
         * listens. The folder is made owner-only first; each token goes into a temporary file in that
         * folder, which is restricted and checked before the token is written into it, then renamed
         * over the target atomically, so a reader never sees half a token or a token with wider
         * permissions. Throws [StartupRefused] when any step fails; [grantProblem] is the permission
         * check (a seam for tests).
         */
        fun issue(
            profileDir: Path,
            profileName: String,
            engineVersion: String,
            random: SecureRandom = secureRandom,
            grantProblem: (Path) -> String? = { OwnerOnly.grantProblem(it) },
        ): IpcAuth {
            if (!Files.isDirectory(profileDir)) throw StartupRefused("the profile folder does not exist or is not a folder", profileDir)
            val folder = OwnerOnly.restrictDirectory(profileDir)
            if (!folder.restricted) throw StartupRefused("the profile folder cannot be restricted to the current user ($folder)", profileDir)
            grantProblem(profileDir)?.let { throw StartupRefused("the profile folder is not owner-only, $it", profileDir) }
            val full = ByteArray(TOKEN_BYTES).also(random::nextBytes)
            val read = ByteArray(TOKEN_BYTES).also(random::nextBytes)
            writeToken(tokenFile(profileDir, Scope.FULL), full, grantProblem)
            writeToken(tokenFile(profileDir, Scope.READ), read, grantProblem)
            return IpcAuth(profileName, full, read, engineVersion)
        }

        /**
         * [issue] for `daemon run` and `sync`: a refusal is reported as ONE error line starting with
         * [STARTUP_REFUSED_PREFIX] in the log and the same line on stderr, then rethrown; the caller exits
         * with [STARTUP_REFUSED_EXIT_CODE] without opening its socket.
         */
        fun issueOrReport(
            profileDir: Path,
            profileName: String,
            engineVersion: String,
        ): IpcAuth =
            try {
                issue(profileDir, profileName, engineVersion)
            } catch (e: StartupRefused) {
                log.error(e.message)
                System.err.println(e.message)
                throw e
            }

        private fun writeToken(
            target: Path,
            token: ByteArray,
            grantProblem: (Path) -> String?,
        ) {
            val dir = target.parent
            val temp =
                try {
                    Files.createTempFile(dir, ".ipc-token-", ".tmp")
                } catch (e: IOException) {
                    throw StartupRefused("a token file cannot be created (${e.message})", dir)
                }
            try {
                val restricted = OwnerOnly.restrictFile(temp)
                if (!restricted.restricted) throw StartupRefused("a token file cannot be restricted to the current user ($restricted)", temp)
                grantProblem(temp)?.let { throw StartupRefused("a new token file is not owner-only, $it", temp) }
                Files.write(temp, encode(token).toByteArray(Charsets.US_ASCII))
                moveOver(temp, target)
            } finally {
                runCatching { Files.deleteIfExists(temp) }
            }
            grantProblem(target)?.let { throw StartupRefused("the token file is not owner-only, $it", target) }
        }

        // A reader that holds the old file open can make the replace fail on Windows for a moment.
        private fun moveOver(
            temp: Path,
            target: Path,
        ) {
            var attempt = 1
            while (true) {
                try {
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    return
                } catch (e: IOException) {
                    if (attempt++ >= MOVE_ATTEMPTS) throw StartupRefused("the token file cannot be replaced (${e.message})", target)
                    Thread.sleep(MOVE_RETRY_MS)
                }
            }
        }

        /** The decoded token in [file] (one line of base64url; surrounding whitespace is ignored). */
        fun readToken(file: Path): ByteArray {
            val text = Files.readString(file, Charsets.US_ASCII).trim()
            return decode(text)?.takeIf { it.size == TOKEN_BYTES } ?: throw IOException("$file does not hold an IPC token")
        }

        fun clientProof(
            key: ByteArray,
            scope: String,
            profile: String,
            clientNonce: String,
            serverNonce: String,
        ): String = hmac(key, "unidrive-ipc-v2|client|$scope|${boundProfile(profile)}|$clientNonce|$serverNonce")

        fun serverProof(
            key: ByteArray,
            scope: String,
            profile: String,
            clientNonce: String,
            serverNonce: String,
            clientProof: String,
        ): String = hmac(key, "unidrive-ipc-v2|server|$scope|${boundProfile(profile)}|$clientNonce|$serverNonce|$clientProof")

        // The UTF-8 byte length makes the message unambiguous for any profile name (also one with '|').
        private fun boundProfile(profile: String): String = "${profile.toByteArray(Charsets.UTF_8).size}:$profile"

        private fun hmac(
            key: ByteArray,
            message: String,
        ): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return encode(mac.doFinal(message.toByteArray(Charsets.UTF_8)))
        }

        /** Constant-time comparison of two base64url texts. */
        fun constantTimeEquals(
            a: String,
            b: String,
        ): Boolean = MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

        /** base64url (RFC 4648 section 5) without padding. */
        fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

        /** The bytes of an unpadded base64url text, or null unless [text] is exactly the encoding of them. */
        fun decode(text: String): ByteArray? {
            if (text.isEmpty() || text.any { !(it.isLetterOrDigit() && it.code < 128) && it != '-' && it != '_' }) return null
            val bytes = runCatching { Base64.getUrlDecoder().decode(text) }.getOrNull() ?: return null
            return bytes.takeIf { encode(it) == text }
        }

        private val secureRandom = SecureRandom()

        fun randomBytes(n: Int): ByteArray = ByteArray(n).also { secureRandom.nextBytes(it) }

        private fun validClientName(name: String): Boolean = name.length in 1..MAX_CLIENT_NAME && name.all { it.code in 0x20..0x7E }

        private fun parseObject(line: String): JsonObject? = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull()

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        /**
         * The value of the one top-level `verb` member of the JSON object [line], or null when the line
         * is not a well-formed object, has no such member or more than one, or its value is not a string.
         */
        internal fun singleTopLevelVerb(line: String): String? {
            val obj = parseObject(line) ?: return null
            if (topLevelNames(line).count { it == "verb" } != 1) return null
            return obj.string("verb")
        }

        // Member names of the top-level object, decoded, in order (duplicates kept). Only called on a
        // line that parsed as a JSON object, so the structure is well-formed.
        private fun topLevelNames(line: String): List<String> {
            val names = mutableListOf<String>()
            var depth = 0
            var i = 0
            while (i < line.length) {
                when (line[i]) {
                    '"' -> {
                        var end = i + 1
                        while (line[end] != '"') end += if (line[end] == '\\') 2 else 1
                        if (depth == 1) {
                            var next = end + 1
                            while (next < line.length && line[next] in " \t\r\n") next++
                            if (next < line.length && line[next] == ':') {
                                names.add((Json.parseToJsonElement(line.substring(i, end + 1)) as JsonPrimitive).content)
                            }
                        }
                        i = end
                    }
                    '{', '[' -> depth++
                    '}', ']' -> depth--
                }
                i++
            }
            return names
        }
    }
}
