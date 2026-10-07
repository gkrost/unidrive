package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.krost.unidrive.io.OwnerOnly
import org.krost.unidrive.io.grantProblem
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The server side of the IPC handshake (protocol version 2) without a socket: the fixed test vectors,
 * the per-connection state machine, the verb classes and the token files. The socket-level behaviour
 * (timeouts, closing, the client helper) is in IpcAuthServerTest.
 */
class IpcAuthTest {
    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("ipc-auth-test")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    // ── fixed test vectors (every implementation asserts exactly these) ──────────────────────────

    private val vectorToken = ByteArray(32) { it.toByte() }
    private val vectorClientNonce = "AAECAwQFBgcICQoLDA0ODw"
    private val vectorServerNonce = "EBESExQVFhcYGRobHB0eHw"
    private val otherToken = ByteArray(32) { (0x80 + it).toByte() }

    @Test
    fun `the fixed vectors`() {
        assertEquals("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8", IpcAuth.encode(vectorToken))
        assertEquals(vectorClientNonce, IpcAuth.encode(ByteArray(16) { it.toByte() }))
        assertEquals(vectorServerNonce, IpcAuth.encode(ByteArray(16) { (0x10 + it).toByte() }))

        val full = IpcAuth.clientProof(vectorToken, "full", "p1", vectorClientNonce, vectorServerNonce)
        assertEquals("2TL-rOrEbuDB9pZ34fpxlSRakSFB9X49f3Kn7_plriA", full)
        assertEquals(
            "qwZ4elXK7shenllL3frkDuObSaAhaSuBDra9Q94EuNo",
            IpcAuth.serverProof(vectorToken, "full", "p1", vectorClientNonce, vectorServerNonce, full),
        )
        assertEquals(
            "GtoBU2BmkePKA90_tQJ5YMN4BNI6dGrDqeOSG04Mz7U",
            IpcAuth.clientProof(vectorToken, "read", "p1", vectorClientNonce, vectorServerNonce),
        )
        // "caf" + U+00E9, built from the code point: 5 UTF-8 bytes, no normalisation.
        val cafe = "caf" + String(Character.toChars(0xE9))
        assertEquals(5, cafe.toByteArray(Charsets.UTF_8).size)
        assertEquals(
            "oVzRRJv7YFxwABUzPVE56lJmegMwKj_bEsfdATEzT7A",
            IpcAuth.clientProof(vectorToken, "full", cafe, vectorClientNonce, vectorServerNonce),
        )
    }

    @Test
    fun `the proof is HMAC-SHA256 over the length-prefixed message`() {
        // An independent computation of the spec text, so the vectors above are not the only check.
        val profile = "a|b"
        val message = "unidrive-ipc-v2|client|full|3:a|b|$vectorClientNonce|$vectorServerNonce"
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(otherToken, "HmacSHA256")) }
        val expected = IpcAuth.encode(mac.doFinal(message.toByteArray(Charsets.UTF_8)))
        assertEquals(expected, IpcAuth.clientProof(otherToken, "full", profile, vectorClientNonce, vectorServerNonce))
    }

    @Test
    fun `base64url is unpadded and decoding is strict`() {
        assertContentEquals(vectorToken, IpcAuth.decode("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"))
        assertNull(IpcAuth.decode("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="), "padding")
        assertNull(IpcAuth.decode("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh+"), "the standard alphabet")
        assertNull(IpcAuth.decode("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh9"), "non-zero trailing bits")
        assertNull(IpcAuth.decode("not base64!"))
    }

    // ── the per-connection handshake ─────────────────────────────────────────────────────────────

    private fun auth(
        profile: String = "p1",
        timeoutMs: Long = IpcAuth.UNAUTHENTICATED_TIMEOUT_MS,
        nanoTime: () -> Long = System::nanoTime,
    ) = IpcAuth(
        profileName = profile,
        fullToken = vectorToken,
        readToken = vectorToken,
        engineVersion = "test-engine",
        serverNonces = { ByteArray(16) { (0x10 + it).toByte() } },
        unauthenticatedTimeoutMs = timeoutMs,
        nanoTime = nanoTime,
    )

    private fun distinctTokens(serverNonces: () -> ByteArray = { ByteArray(16) { (0x10 + it).toByte() } }) =
        IpcAuth("p1", vectorToken, otherToken, "test-engine", serverNonces = serverNonces)

    private fun hello(
        scope: String = "full",
        nonce: String = vectorClientNonce,
        client: String = "test-client",
        protocol: Int = 2,
    ) = """{"verb":"hello","protocol":$protocol,"scope":"$scope","client":"$client","nonce":"$nonce"}"""

    private fun proof(p: String) = """{"verb":"hello.proof","proof":"$p"}"""

    private fun json(reply: IpcAuth.Reply?): JsonObject = Json.parseToJsonElement(assertNotNull(reply).json).jsonObject

    private suspend fun IpcAuth.Session.send(line: String): IpcAuth.Reply? =
        gate(Json.parseToJsonElement(line).jsonObject["verb"]?.jsonPrimitive?.content, line)

    @Test
    fun `a full handshake with the fixed vectors authenticates the connection`() =
        runTest {
            val session = auth().newSession()

            val step1 = json(session.send(hello()))
            assertEquals(JsonPrimitive(true), step1["ok"])
            assertEquals(JsonPrimitive(1), step1["step"])
            assertEquals(JsonPrimitive(vectorServerNonce), step1["snonce"])
            assertNull(session.scope, "not yet")

            val done = session.send(proof("2TL-rOrEbuDB9pZ34fpxlSRakSFB9X49f3Kn7_plriA"))
            assertEquals(
                """{"ok":true,"scope":"full","protocol_version":2,"proof":"qwZ4elXK7shenllL3frkDuObSaAhaSuBDra9Q94EuNo"}""",
                assertNotNull(done).json,
            )
            assertFalse(done.close)
            assertEquals(IpcAuth.Scope.FULL, session.scope)
            assertNull(session.send("""{"verb":"daemon.shutdown"}"""), "an authenticated full connection passes to the handler")
        }

    @Test
    fun `the read scope authenticates with its own vector`() =
        runTest {
            val session = auth().newSession()
            session.send(hello(scope = "read"))
            val done = json(session.send(proof("GtoBU2BmkePKA90_tQJ5YMN4BNI6dGrDqeOSG04Mz7U")))
            assertEquals(JsonPrimitive("read"), done["scope"])
            assertEquals(IpcAuth.Scope.READ, session.scope)
        }

    @Test
    fun `a non-ASCII profile name is bound into the proof by its UTF-8 bytes`() =
        runTest {
            val session = auth(profile = "caf" + String(Character.toChars(0xE9))).newSession()
            session.send(hello())
            assertEquals(JsonPrimitive(true), json(session.send(proof("oVzRRJv7YFxwABUzPVE56lJmegMwKj_bEsfdATEzT7A")))["ok"])
        }

    @Test
    fun `before authentication only the handshake and a minimal daemon status are answered`() =
        runTest {
            val session = auth().newSession()
            assertEquals(
                """{"ok":true,"protocol_version":2,"engine_version":"test-engine","auth_required":true}""",
                session.gate("daemon.status", """{"verb":"daemon.status"}""")?.json,
            )
            for (line in listOf(
                """{"verb":"hydration.list","prefix":"/"}""",
                """{"verb":"daemon.shutdown"}""",
                """{"verb":"refresh.run","reset":true}""",
                """{"verb":"no.such.verb"}""",
            )) {
                val reply = session.send(line)
                assertEquals("""{"ok":false,"error":"auth_required"}""", reply?.json, line)
                assertFalse(reply!!.close)
            }
            assertEquals("""{"ok":false,"error":"auth_required"}""", session.gate(null, """{"x":1}""")?.json, "no verb at all")
            assertNull(session.scope)
        }

    @Test
    fun `a request of a connection without a session is refused and closes it`() =
        runTest {
            // The server keeps one session per open connection; a request that arrives while the
            // connection is being closed (its session already dropped) must never reach a handler.
            val reply = auth().gate(null, "hydration.list", """{"verb":"hydration.list","prefix":"/"}""")
            assertEquals("""{"ok":false,"error":"auth_required"}""", reply?.json)
            assertTrue(reply!!.close)
            val authenticated = authenticated(IpcAuth.Scope.FULL)
            assertNull(auth().gate(authenticated, "daemon.shutdown", """{"verb":"daemon.shutdown"}"""), "a live session decides as before")
        }

    @Test
    fun `a proof made with another token fails`() =
        runTest {
            val session = auth().newSession()
            session.send(hello())
            val wrong = IpcAuth.clientProof(otherToken, "full", "p1", vectorClientNonce, vectorServerNonce)
            assertEquals("""{"ok":false,"error":"auth_failed"}""", session.send(proof(wrong))?.json)
            assertNull(session.scope)
        }

    @Test
    fun `a proof for scope full sent after a hello for read fails`() =
        runTest {
            // Distinct tokens per scope: the server picks the key by the scope the hello asked for.
            val session = distinctTokens().newSession()
            session.send(hello(scope = "read"))
            val fullProof = IpcAuth.clientProof(vectorToken, "full", "p1", vectorClientNonce, vectorServerNonce)
            assertEquals("""{"ok":false,"error":"auth_failed"}""", session.send(proof(fullProof))?.json)

            // Nor does the full token computed over the scope string "read".
            session.send(hello(scope = "read"))
            val fullKeyReadScope = IpcAuth.clientProof(vectorToken, "read", "p1", vectorClientNonce, vectorServerNonce)
            assertEquals("""{"ok":false,"error":"auth_failed"}""", session.send(proof(fullKeyReadScope))?.json)
            assertNull(session.scope)
        }

    @Test
    fun `the server nonce is single use`() =
        runTest {
            var n = 0
            val session = distinctTokens(serverNonces = { ByteArray(16) { (n + it).toByte() }.also { n += 16 } }).newSession()
            val first = json(session.send(hello()))["snonce"]!!.jsonPrimitive.content
            val good = IpcAuth.clientProof(vectorToken, "full", "p1", vectorClientNonce, first)
            // Spent by a failed attempt: the same proof afterwards no longer counts.
            session.send(proof("x$good"))
            assertEquals("""{"ok":false,"error":"auth_failed"}""", session.send(proof(good))?.json, "no hello pending")
            // A new hello gets a new server nonce, so the old proof does not match it.
            val second = json(session.send(hello()))["snonce"]!!.jsonPrimitive.content
            assertNotEquals(first, second)
            val reply = session.send(proof(good))
            assertEquals("""{"ok":false,"error":"auth_failed"}""", reply?.json)
            assertTrue(reply!!.close, "third failure")
        }

    @Test
    fun `hello proof without a hello fails`() =
        runTest {
            val session = auth().newSession()
            assertEquals(
                """{"ok":false,"error":"auth_failed"}""",
                session.send(proof("2TL-rOrEbuDB9pZ34fpxlSRakSFB9X49f3Kn7_plriA"))?.json,
            )
        }

    @Test
    fun `a malformed hello fails`() =
        runTest {
            val bad =
                listOf(
                    hello(protocol = 1),
                    hello(scope = "admin"),
                    hello(nonce = "AAECAwQFBgcICQoLDA0O"), // 14 bytes
                    hello(nonce = "AAECAwQFBgcICQoLDA0ODw=="), // padded
                    hello(client = ""),
                    hello(client = "x".repeat(65)),
                    """{"verb":"hello","protocol":2,"scope":"full","client":"a\u0007b","nonce":"$vectorClientNonce"}""",
                    """{"verb":"hello","protocol":"2","scope":"full","client":"c","nonce":"$vectorClientNonce"}""",
                    """{"verb":"hello"""",
                )
            for (line in bad) {
                val session = auth().newSession()
                assertEquals("""{"ok":false,"error":"auth_failed"}""", session.gate("hello", line)?.json, line)
            }
            // 64 printable characters is the limit, not over it.
            assertEquals(JsonPrimitive(true), json(auth().newSession().send(hello(client = "x".repeat(64))))["ok"])
        }

    @Test
    fun `the third failure closes the connection and each failure waits`() =
        runTest {
            val session = auth().newSession()
            val replies =
                (1..3).map {
                    session.send(hello())
                    val before = testScheduler.currentTime
                    session.send(proof("AAAA")).also {
                        assertEquals(IpcAuth.FAILURE_DELAY_MS, testScheduler.currentTime - before, "each failure waits before the reply")
                    }
                }
            assertEquals(listOf(false, false, true), replies.map { it!!.close })
            assertTrue(replies.all { it!!.json == """{"ok":false,"error":"auth_failed"}""" })
        }

    @Test
    fun `a connection that does not authenticate in time times out, an authenticated one does not`() =
        runTest {
            var now = 0L
            val auth = auth(timeoutMs = 5_000, nanoTime = { now })
            val idle = auth.newSession()
            val done = auth.newSession()
            done.send(hello())
            done.send(proof("2TL-rOrEbuDB9pZ34fpxlSRakSFB9X49f3Kn7_plriA"))

            now = 4_999_000_000L
            assertFalse(idle.timedOut())
            now = 5_000_000_000L
            assertTrue(idle.timedOut())
            assertFalse(done.timedOut())
        }

    // ── scopes and verb classes ──────────────────────────────────────────────────────────────────

    private suspend fun authenticated(scope: IpcAuth.Scope): IpcAuth.Session {
        val session = auth().newSession()
        session.send(hello(scope = scope.wire))
        val p = IpcAuth.clientProof(vectorToken, scope.wire, "p1", vectorClientNonce, vectorServerNonce)
        assertEquals(JsonPrimitive(true), json(session.send(proof(p)))["ok"])
        return session
    }

    @Test
    fun `the verb classes are the exact wire strings`() {
        val read = setOf("daemon.status", "hydration.list", "hydration.last_synced", "hydration.subscribe", "sync.subscribe", "hydration.open_read")
        val write =
            setOf(
                "hydration.hydrate", "hydration.dehydrate", "hydration.create", "hydration.open_write", "hydration.open_write_begin",
                "hydration.close_handle", "hydration.mkdir", "hydration.unlink", "hydration.rmdir", "hydration.rename",
                "hydration.cancel", "sync.enumerate",
            )
        val admin = setOf("daemon.shutdown", "refresh.run")
        assertEquals(read, IpcAuth.VERB_CLASSES.filterValues { it == IpcAuth.VerbClass.READ }.keys)
        assertEquals(write, IpcAuth.VERB_CLASSES.filterValues { it == IpcAuth.VerbClass.WRITE }.keys)
        assertEquals(admin, IpcAuth.VERB_CLASSES.filterValues { it == IpcAuth.VerbClass.ADMIN }.keys)
        assertEquals(IpcAuth.VerbClass.ADMIN, IpcAuth.classOf("hydration.something_new"), "an unlisted verb is admin")
        assertEquals(IpcAuth.VerbClass.ADMIN, IpcAuth.classOf("Hydration.List"), "exact strings, no case folding")
    }

    @Test
    fun `a read connection may call the read class only`() =
        runTest {
            val session = authenticated(IpcAuth.Scope.READ)
            for (verb in IpcAuth.VERB_CLASSES.filterValues { it == IpcAuth.VerbClass.READ }.keys) {
                assertNull(session.send("""{"verb":"$verb","path":"/a","prefix":"/"}"""), verb)
            }
            for (verb in IpcAuth.VERB_CLASSES.filterValues { it != IpcAuth.VerbClass.READ }.keys + "no.such.verb") {
                val reply = session.send("""{"verb":"$verb","path":"/a"}""")
                assertEquals("""{"ok":false,"error":"forbidden","scope":"read"}""", reply?.json, verb)
                assertFalse(reply!!.close)
            }
        }

    @Test
    fun `a full connection may call every verb`() =
        runTest {
            val session = authenticated(IpcAuth.Scope.FULL)
            for (verb in IpcAuth.VERB_CLASSES.keys + "no.such.verb") {
                assertNull(session.send("""{"verb":"$verb"}"""), verb)
            }
        }

    @Test
    fun `a read connection is refused a request whose structured verb differs from the dispatched one`() =
        runTest {
            val session = authenticated(IpcAuth.Scope.READ)
            // The dispatcher's verb probe and a structured reading of the line must agree; a handler
            // that reads the verb itself must never act on a different one than was checked.
            val nested = """{"a":{"verb":"hydration.list"},"verb":"hydration.unlink","path":"/x"}"""
            assertEquals("""{"ok":false,"error":"forbidden","scope":"read"}""", session.gate("hydration.list", nested)?.json)
            val duplicate = """{"verb":"hydration.list","verb":"hydration.unlink","path":"/x"}"""
            assertEquals("""{"ok":false,"error":"forbidden","scope":"read"}""", session.gate("hydration.list", duplicate)?.json)
            // The first member name is the JSON escape of "verb" (backslash u 0076 then "erb").
            val escapedVerbName = Char(92) + "u0076erb"
            val escaped = """{"$escapedVerbName":"hydration.unlink","verb":"hydration.list"}"""
            assertEquals("""{"ok":false,"error":"forbidden","scope":"read"}""", session.gate("hydration.list", escaped)?.json)
            assertNull(session.gate("hydration.list", """{"verb":"hydration.list","prefix":"verb"}"""))
        }

    @Test
    fun `the structured verb is the single top-level verb member`() {
        assertEquals("hydration.list", IpcAuth.singleTopLevelVerb("""{"verb":"hydration.list","x":{"verb":"y"}}"""))
        assertEquals("a", IpcAuth.singleTopLevelVerb(""" { "p" : [ {"verb":"z"} ] , "verb" : "a" } """))
        assertNull(IpcAuth.singleTopLevelVerb("""{"verb":"a","verb":"a"}"""), "duplicates")
        assertNull(IpcAuth.singleTopLevelVerb("""{"verb":1}"""), "not a string")
        assertNull(IpcAuth.singleTopLevelVerb("""["verb","a"]"""), "not an object")
        assertNull(IpcAuth.singleTopLevelVerb("""{"verb":"a""""), "malformed")
        assertNull(IpcAuth.singleTopLevelVerb("""{"x":"\"verb\":\"a\""}"""), "inside a string value")
    }

    // ── token files ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `issue writes both token files, readable back, owner-only`() {
        val auth = IpcAuth.issue(dir, "p1", "test-engine")
        assertNotNull(auth)
        for (scope in IpcAuth.Scope.entries) {
            val file = IpcAuth.tokenFile(dir, scope)
            val text = Files.readString(file)
            assertEquals(43, text.length, "base64url of 32 bytes, one line without padding")
            assertContentEquals(IpcAuth.decode(text), IpcAuth.readToken(file))
            assertNull(OwnerOnly.grantProblem(file), "owner-only: $file")
        }
        assertNull(OwnerOnly.grantProblem(dir), "the folder too")
        assertFalse(
            Files.readString(IpcAuth.tokenFile(dir, IpcAuth.Scope.FULL)) == Files.readString(IpcAuth.tokenFile(dir, IpcAuth.Scope.READ)),
            "two independent tokens",
        )
        assertEquals(
            listOf(IpcAuth.READ_TOKEN_FILE, IpcAuth.TOKEN_FILE),
            Files.list(dir).use { s -> s.map { it.fileName.toString() }.sorted().toList() },
            "no temporary file is left behind",
        )
    }

    @Test
    fun `issue rotates the tokens at every call`() {
        IpcAuth.issue(dir, "p1", "test-engine")
        val first = IpcAuth.readToken(IpcAuth.tokenFile(dir, IpcAuth.Scope.FULL))
        IpcAuth.issue(dir, "p1", "test-engine")
        val second = IpcAuth.readToken(IpcAuth.tokenFile(dir, IpcAuth.Scope.FULL))
        assertFalse(first.contentEquals(second))
    }

    @Test
    fun `the token files hold the keys the issued server checks`() =
        runTest {
            val auth = IpcAuth.issue(dir, "p1", "test-engine", random = SecureRandom())
            val key = IpcAuth.readToken(IpcAuth.tokenFile(dir, IpcAuth.Scope.READ))
            val session = auth.newSession()
            val snonce = json(session.send(hello(scope = "read")))["snonce"]!!.jsonPrimitive.content
            val reply = session.send(proof(IpcAuth.clientProof(key, "read", "p1", vectorClientNonce, snonce)))
            assertEquals(JsonPrimitive(true), json(reply)["ok"])
        }

    @Test
    fun `Windows token files grant nobody but the user, SYSTEM and Administrators`() {
        assumeTrue("Windows ACLs only", WindowsAclProbe.isWindows)
        // The folder starts out with an inheritable grant for another principal, as a temp folder can.
        WindowsAclProbe.grantInheritableRead(dir, WindowsAclProbe.USERS_SID)
        IpcAuth.issue(dir, "p1", "test-engine")
        for (scope in IpcAuth.Scope.entries) {
            val dacl = WindowsAclProbe.dacl(IpcAuth.tokenFile(dir, scope))
            // Canonical SDDL spellings: the user's SID can read back as an alias (LA for the built-in Administrator).
            val sids = WindowsAclProbe.aces(dacl).map { it.substringAfterLast(';') }.toSet()
            assertTrue(sids.isNotEmpty() && sids.all { it == WindowsAclProbe.userSddlSid || it == "SY" || it == "BA" }, "checked with icacls: $dacl")
        }
    }

    @Test
    fun `POSIX token files are 0600 in a 0700 folder`() {
        assumeTrue("POSIX only", FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"))
        IpcAuth.issue(dir, "p1", "test-engine")
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)))
        for (scope in IpcAuth.Scope.entries) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(IpcAuth.tokenFile(dir, scope))))
        }
    }

    @Test
    fun `issue refuses when the folder or a token file is reachable by others`() {
        val folder =
            assertFailsWith<IpcAuth.StartupRefused> {
                IpcAuth.issue(dir, "p1", "test-engine", grantProblem = { p -> if (p == dir) "granted to BU" else null })
            }
        assertTrue(folder.message!!.startsWith("IPC startup refused: "), folder.message)
        assertTrue(dir.toString() in folder.message!!, folder.message)
        assertFalse(Files.exists(IpcAuth.tokenFile(dir, IpcAuth.Scope.FULL)), "nothing is written into a folder others can reach")

        val file =
            assertFailsWith<IpcAuth.StartupRefused> {
                IpcAuth.issue(dir, "p1", "test-engine", grantProblem = { p -> if (p.fileName.toString().endsWith(".tmp")) "granted to WD" else null })
            }
        assertTrue(file.message!!.startsWith("IPC startup refused: "), file.message)
        assertFalse(Files.exists(IpcAuth.tokenFile(dir, IpcAuth.Scope.FULL)), "a token the check refused is never put in place")
        assertEquals(0L, Files.list(dir).use { it.count() }, "the refused temporary file is removed")
    }

    @Test
    fun `issue refuses a profile folder that is missing or not a folder`() {
        val missing = assertFailsWith<IpcAuth.StartupRefused> { IpcAuth.issue(dir.resolve("absent"), "p1", "v") }
        assertTrue(missing.message!!.startsWith("IPC startup refused: "), missing.message)
        val file = Files.writeString(dir.resolve("a-file"), "x")
        val notDir = assertFailsWith<IpcAuth.StartupRefused> { IpcAuth.issue(file, "p1", "v") }
        assertTrue(file.toString() in notDir.message!!, notDir.message)
    }

    @Test
    fun `readToken refuses a malformed token file`() {
        val file = Files.writeString(dir.resolve(IpcAuth.TOKEN_FILE), "AAECAwQFBgcICQoLDA0ODw")
        assertFailsWith<java.io.IOException> { IpcAuth.readToken(file) }
        Files.writeString(file, "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8\r\n")
        assertContentEquals(vectorToken, IpcAuth.readToken(file), "surrounding whitespace is ignored")
    }
}
