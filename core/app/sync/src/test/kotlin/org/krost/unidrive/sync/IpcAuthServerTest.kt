package org.krost.unidrive.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The IPC handshake over a real socket: [IpcServer] with an [IpcAuth] against raw connections and the
 * engine's own client ([IpcAuthClient]), and that client against [ReferenceServer], a server side
 * written from the spec text (with its own HMAC), so the client is tested against the spec rather than
 * against the engine's server.
 */
class IpcAuthServerTest {
    private lateinit var dir: Path
    private lateinit var profileDir: Path
    private lateinit var socketPath: Path
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val servers = CopyOnWriteArrayList<IpcServer>()

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("ipc-auth-sock")
        profileDir = Files.createDirectories(dir.resolve("p"))
        socketPath = dir.resolve("d.sock")
    }

    @AfterTest
    fun tearDown() {
        servers.forEach { runCatching { it.close() } }
        scope.cancel()
        dir.toFile().deleteRecursively()
    }

    private val endpoint get() = IpcEndpoint(socketPath, profileDir, "p1")

    private fun startServer(auth: IpcAuth): IpcServer {
        val server = IpcServer(socketPath, auth = auth)
        server.registerHandler("daemon.status") { _, _ -> """{"ok":true,"detail":"full status"}""" }
        server.registerHandler("hydration.list") { _, _ -> """{"ok":true,"entries":[]}""" }
        server.registerHandler("hydration.unlink") { _, _ -> """{"ok":true}""" }
        server.registerHandler("daemon.shutdown") { _, _ -> """{"ok":true}""" }
        server.start(scope)
        servers.add(server)
        return server
    }

    private fun issueAndStart(timeoutMs: Long = IpcAuth.UNAUTHENTICATED_TIMEOUT_MS): IpcAuth {
        val issued = IpcAuth.issue(profileDir, "p1", "test-engine")
        // Same tokens, a shorter unauthenticated timeout where a test needs one.
        val auth =
            IpcAuth(
                "p1",
                IpcAuth.readToken(IpcAuth.tokenFile(profileDir, IpcAuth.Scope.FULL)),
                IpcAuth.readToken(IpcAuth.tokenFile(profileDir, IpcAuth.Scope.READ)),
                "test-engine",
                unauthenticatedTimeoutMs = timeoutMs,
            )
        check(issued !== auth)
        startServer(auth)
        return auth
    }

    // ── raw connection helpers ───────────────────────────────────────────────────────────────────

    private fun raw(): SocketChannel = SocketChannel.open(UnixDomainSocketAddress.of(socketPath)).also { it.configureBlocking(false) }

    private fun SocketChannel.send(line: String) {
        val b = ByteBuffer.wrap((line + "\n").toByteArray(Charsets.UTF_8))
        while (b.hasRemaining()) write(b)
    }

    /** The next reply line, or null when the server closed the connection first. */
    private fun SocketChannel.line(timeoutMs: Long = 5_000): String? {
        val out = java.io.ByteArrayOutputStream()
        val deadline = System.currentTimeMillis() + timeoutMs
        val one = ByteBuffer.allocate(1)
        while (System.currentTimeMillis() < deadline) {
            one.clear()
            when (read(one)) {
                -1 -> return null
                0 -> Thread.sleep(5)
                else -> {
                    if (one.get(0) == '\n'.code.toByte()) return out.toString(Charsets.UTF_8)
                    out.write(one.get(0).toInt())
                }
            }
        }
        error("no reply within ${timeoutMs}ms; got so far: $out")
    }

    private fun SocketChannel.ask(line: String): String = send(line).let { line() ?: error("closed after $line") }

    private fun obj(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    private val clientNonce = "AAECAwQFBgcICQoLDA0ODw"

    private fun hello(scope: String = "full") =
        """{"verb":"hello","protocol":2,"scope":"$scope","client":"raw-test","nonce":"$clientNonce"}"""

    private fun proofFor(
        key: ByteArray,
        snonce: String,
        scope: String = "full",
    ) = IpcAuth.clientProof(key, scope, "p1", clientNonce, snonce)

    private fun fullKey() = IpcAuth.readToken(IpcAuth.tokenFile(profileDir, IpcAuth.Scope.FULL))

    // ── server ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the engine client authenticates against a real server and reaches the handlers`() {
        issueAndStart()
        IpcAuthClient.connect(endpoint, IpcAuth.Scope.FULL).use { ch ->
            ch.configureBlocking(false)
            assertEquals("""{"ok":true,"detail":"full status"}""", ch.ask("""{"verb":"daemon.status"}"""))
            assertEquals("""{"ok":true}""", ch.ask("""{"verb":"daemon.shutdown"}"""))
        }
        IpcAuthClient.connect(endpoint, IpcAuth.Scope.READ).use { ch ->
            ch.configureBlocking(false)
            assertEquals("""{"ok":true,"entries":[]}""", ch.ask("""{"verb":"hydration.list","prefix":"/"}"""))
        }
    }

    @Test
    fun `scopeOf names the scope the handshake granted`() {
        IpcAuth.issue(profileDir, "p1", "test-engine")
        val auth =
            IpcAuth(
                "p1",
                IpcAuth.readToken(IpcAuth.tokenFile(profileDir, IpcAuth.Scope.FULL)),
                IpcAuth.readToken(IpcAuth.tokenFile(profileDir, IpcAuth.Scope.READ)),
                "test-engine",
            )
        val server = IpcServer(socketPath, auth = auth)
        // READ-class verb: reachable with either scope; it reports what the server knows of its connection.
        server.registerHandler("hydration.list") { connId, _ -> """{"ok":true,"scope":${server.scopeOf(connId)?.let { "\"$it\"" }}}""" }
        server.start(scope)
        servers.add(server)
        assertTrue(server.authEnabled)

        IpcAuthClient.connect(endpoint, IpcAuth.Scope.READ).use { ch ->
            ch.configureBlocking(false)
            assertEquals("""{"ok":true,"scope":"read"}""", ch.ask("""{"verb":"hydration.list"}"""))
        }
        IpcAuthClient.connect(endpoint, IpcAuth.Scope.FULL).use { ch ->
            ch.configureBlocking(false)
            assertEquals("""{"ok":true,"scope":"full"}""", ch.ask("""{"verb":"hydration.list"}"""))
        }
        assertEquals(null, server.scopeOf("no-such-connection"))
    }

    @Test
    fun `a server without authentication reports no scope`() {
        val server = IpcServer(socketPath)
        server.registerHandler("hydration.list") { connId, _ -> """{"ok":true,"scope":${server.scopeOf(connId)}}""" }
        server.start(scope)
        servers.add(server)
        assertTrue(!server.authEnabled)
        raw().use { ch -> assertEquals("""{"ok":true,"scope":null}""", ch.ask("""{"verb":"hydration.list"}""")) }
    }

    @Test
    fun `requests before the handshake get auth_required and daemon status the minimal reply`() {
        issueAndStart()
        raw().use { ch ->
            assertEquals("""{"ok":false,"error":"auth_required"}""", ch.ask("""{"verb":"hydration.list","prefix":"/"}"""))
            assertEquals("""{"ok":false,"error":"auth_required"}""", ch.ask("""{"verb":"daemon.shutdown"}"""))
            assertEquals("""{"ok":false,"error":"auth_required"}""", ch.ask("""{"no":"verb"}"""))
            assertEquals(
                """{"ok":true,"protocol_version":2,"engine_version":"test-engine","auth_required":true}""",
                ch.ask("""{"verb":"daemon.status"}"""),
            )
        }
    }

    @Test
    fun `a client holding another token is refused`() {
        issueAndStart()
        val otherDir = Files.createDirectories(dir.resolve("other"))
        IpcAuth.issue(otherDir, "p1", "test-engine") // the same file names, different tokens
        val e = assertFailsWith<IpcAuthException> { IpcAuthClient.connect(IpcEndpoint(socketPath, otherDir, "p1"), IpcAuth.Scope.FULL) }
        assertTrue("rejected the IPC token" in e.message!!, e.message)
    }

    @Test
    fun `a client naming another profile is refused`() {
        issueAndStart()
        assertFailsWith<IpcAuthException> { IpcAuthClient.connect(IpcEndpoint(socketPath, profileDir, "p2"), IpcAuth.Scope.FULL) }
    }

    @Test
    fun `a recorded proof does not authenticate another connection`() {
        issueAndStart()
        val key = fullKey()
        val (snonce1, proof1) =
            raw().use { ch ->
                val s = obj(ch.ask(hello()))["snonce"]!!.jsonPrimitive.content
                val p = proofFor(key, s)
                assertEquals(JsonPrimitive(true), obj(ch.ask("""{"verb":"hello.proof","proof":"$p"}"""))["ok"])
                s to p
            }
        raw().use { ch ->
            val snonce2 = obj(ch.ask(hello()))["snonce"]!!.jsonPrimitive.content
            assertNotEquals(snonce1, snonce2, "every hello gets a fresh server nonce")
            assertEquals("""{"ok":false,"error":"auth_failed"}""", ch.ask("""{"verb":"hello.proof","proof":"$proof1"}"""))
        }
    }

    @Test
    fun `an unauthenticated connection is closed after the timeout, an authenticated one stays`() {
        issueAndStart(timeoutMs = 300)
        val idle = raw()
        val done = IpcAuthClient.connect(endpoint, IpcAuth.Scope.READ).also { it.configureBlocking(false) }
        try {
            val t0 = System.currentTimeMillis()
            assertEquals(null, idle.line(timeoutMs = 5_000), "closed by the server")
            assertTrue(System.currentTimeMillis() - t0 < 4_000, "within the timeout, not at the test's deadline")
            Thread.sleep(400)
            assertEquals("""{"ok":true,"entries":[]}""", done.ask("""{"verb":"hydration.list","prefix":"/"}"""))
        } finally {
            idle.close()
            done.close()
        }
    }

    @Test
    fun `the third failed handshake closes the connection`() {
        issueAndStart()
        raw().use { ch ->
            repeat(2) {
                ch.ask(hello())
                assertEquals("""{"ok":false,"error":"auth_failed"}""", ch.ask("""{"verb":"hello.proof","proof":"AAAA"}"""))
            }
            ch.ask(hello())
            assertEquals("""{"ok":false,"error":"auth_failed"}""", ch.ask("""{"verb":"hello.proof","proof":"AAAA"}"""))
            assertEquals(null, ch.line(), "the server closes after the third failure")
        }
    }

    @Test
    fun `a read client is refused write and admin verbs`() {
        issueAndStart()
        IpcAuthClient.connect(endpoint, IpcAuth.Scope.READ).use { ch ->
            ch.configureBlocking(false)
            val forbidden = """{"ok":false,"error":"forbidden","scope":"read"}"""
            assertEquals(forbidden, ch.ask("""{"verb":"hydration.unlink","path":"/a"}"""))
            assertEquals(forbidden, ch.ask("""{"verb":"daemon.shutdown"}"""))
            assertEquals(forbidden, ch.ask("""{"a":{"verb":"hydration.list"},"verb":"hydration.unlink","path":"/a"}"""))
            assertEquals("""{"ok":true,"entries":[]}""", ch.ask("""{"verb":"hydration.list","prefix":"/"}"""), "the connection stays usable")
        }
    }

    @Test
    fun `after a restart with new tokens the old token no longer authenticates`() {
        issueAndStart()
        val oldKey = fullKey()
        servers.removeAt(0).close()

        issueAndStart() // a new daemon start: new tokens, same socket path
        raw().use { ch ->
            val s = obj(ch.ask(hello()))["snonce"]!!.jsonPrimitive.content
            assertEquals("""{"ok":false,"error":"auth_failed"}""", ch.ask("""{"verb":"hello.proof","proof":"${proofFor(oldKey, s)}"}"""))
        }
        // The engine client reads the token file at every connect, so it follows the rotation.
        IpcAuthClient.connect(endpoint, IpcAuth.Scope.FULL).close()
    }

    @Test
    fun `a request the gate answers never makes a connection idle-exempt`() {
        // Idle time on a manual clock; the handshake timeout far away, so only the idle rule closes.
        val now = java.util.concurrent.atomic.AtomicLong(1_000_000L)
        IpcAuth.issue(profileDir, "p1", "test-engine")
        val auth =
            IpcAuth(
                "p1",
                IpcAuth.readToken(IpcAuth.tokenFile(profileDir, IpcAuth.Scope.FULL)),
                IpcAuth.readToken(IpcAuth.tokenFile(profileDir, IpcAuth.Scope.READ)),
                "test-engine",
                unauthenticatedTimeoutMs = 3_600_000,
            )
        val server = IpcServer(socketPath, auth = auth, idleTimeoutMs = 60_000, clock = { now.get() })
        server.registerHandler("hydration.list") { _, _ -> """{"ok":true,"entries":[]}""" }
        server.registerHandler("hydration.unlink") { _, _ -> """{"ok":true}""" }
        server.registerIdleExemptVerbs(listOf("hydration.list", "hydration.unlink"))
        server.start(scope)
        servers.add(server)

        // Answered auth_required (not authenticated) and forbidden (read scope): neither reached a handler.
        val unauthenticated = raw().also { assertEquals("""{"ok":false,"error":"auth_required"}""", it.ask("""{"verb":"hydration.list","prefix":"/"}""")) }
        val reader =
            IpcAuthClient.connect(endpoint, IpcAuth.Scope.READ).also {
                it.configureBlocking(false)
                assertEquals("""{"ok":false,"error":"forbidden","scope":"read"}""", it.ask("""{"verb":"hydration.unlink","path":"/a"}"""))
            }
        // Control: a full connection whose exempt request reached its handler stays.
        val writer =
            IpcAuthClient.connect(endpoint, IpcAuth.Scope.FULL).also {
                it.configureBlocking(false)
                assertEquals("""{"ok":true}""", it.ask("""{"verb":"hydration.unlink","path":"/a"}"""))
            }
        try {
            now.addAndGet(120_000)
            assertEquals(null, unauthenticated.line(), "closed for being idle")
            assertEquals(null, reader.line(), "closed for being idle")
            Thread.sleep(300)
            assertEquals("""{"ok":true,"entries":[]}""", writer.ask("""{"verb":"hydration.list","prefix":"/"}"""), "the exempt connection stays")
        } finally {
            listOf(unauthenticated, reader, writer).forEach { runCatching { it.close() } }
        }
    }

    @Test
    fun `every IpcServer the engine builds outside tests authenticates its connections`() {
        // auth = null exists for the unit tests of the bare server only; `daemon run` and `sync` must
        // never serve without the handshake. The tests run in this module's folder: ../ is core/app.
        val appDir = Path.of("..").toAbsolutePath().normalize()
        val constructions =
            Files.walk(appDir).use { paths ->
                paths
                    .filter { it.toString().endsWith(".kt") && it.toString().replace('\\', '/').contains("/src/main/") }
                    .toList()
                    .flatMap { file ->
                        // A construction, not the class declaration itself.
                        Regex("""(?<!class )\bIpcServer\(([^)]*)\)""").findAll(Files.readString(file)).map { file.fileName.toString() to it.groupValues[1] }.toList()
                    }
            }
        assertTrue(constructions.isNotEmpty(), "found no IpcServer construction under $appDir")
        for ((file, args) in constructions) {
            assertTrue(Regex("""\bauth\s*=""").containsMatchIn(args), "$file builds IpcServer($args) without auth =")
        }
    }

    // ── the client against the reference server ──────────────────────────────────────────────────

    private val vectorToken = ByteArray(32) { it.toByte() }
    private val vectorClientNonce = ByteArray(16) { it.toByte() }
    private val vectorServerNonce = ByteArray(16) { (0x10 + it).toByte() }

    private fun writeTokens(token: ByteArray) {
        for (scope in IpcAuth.Scope.entries) Files.writeString(IpcAuth.tokenFile(profileDir, scope), b64(token))
    }

    @Test
    fun `the client completes the handshake of the spec with the fixed vectors`() {
        writeTokens(vectorToken)
        ReferenceServer(socketPath, vectorToken, "p1", serverNonce = vectorServerNonce).use { ref ->
            IpcAuthClient.connect(endpoint, IpcAuth.Scope.FULL, clientNonce = { vectorClientNonce }).use { ch ->
                ch.configureBlocking(false)
                assertEquals("""{"ok":true,"echo":"daemon.status"}""", ch.ask("""{"verb":"daemon.status"}"""))
            }
            assertEquals(listOf("2TL-rOrEbuDB9pZ34fpxlSRakSFB9X49f3Kn7_plriA"), ref.clientProofs)
            assertEquals(listOf("full"), ref.scopes)
        }
    }

    @Test
    fun `the client asks for the read scope with the read token`() {
        Files.writeString(IpcAuth.tokenFile(profileDir, IpcAuth.Scope.READ), b64(vectorToken))
        ReferenceServer(socketPath, vectorToken, "p1", serverNonce = vectorServerNonce).use { ref ->
            IpcAuthClient.connect(endpoint, IpcAuth.Scope.READ, clientNonce = { vectorClientNonce }).close()
            assertEquals(listOf("GtoBU2BmkePKA90_tQJ5YMN4BNI6dGrDqeOSG04Mz7U"), ref.clientProofs)
        }
    }

    @Test
    fun `the client refuses a server whose proof does not match`() {
        writeTokens(vectorToken)
        ReferenceServer(socketPath, vectorToken, "p1", corruptServerProof = true).use {
            val e = assertFailsWith<IpcAuthException> { IpcAuthClient.connect(endpoint, IpcAuth.Scope.FULL) }
            assertTrue("proof" in e.message!!, e.message)
        }
    }

    @Test
    fun `a rejected token is terminal and fast`() {
        writeTokens(ByteArray(32) { 7 })
        ReferenceServer(socketPath, vectorToken, "p1").use {
            val t0 = System.currentTimeMillis()
            val e = assertFailsWith<IpcAuthException> { IpcAuthClient.connect(endpoint, IpcAuth.Scope.FULL) }
            assertTrue("auth_failed" in e.message!!, e.message)
            assertTrue(System.currentTimeMillis() - t0 < 3_000, "no retry loop, no long timeout")
        }
    }

    @Test
    fun `a server that closes during the handshake fails at once`() {
        writeTokens(vectorToken)
        ReferenceServer(socketPath, vectorToken, "p1", closeImmediately = true).use {
            val t0 = System.currentTimeMillis()
            assertFailsWith<IpcAuthException> { IpcAuthClient.connect(endpoint, IpcAuth.Scope.FULL) }
            assertTrue(System.currentTimeMillis() - t0 < 3_000, "a closed connection is not waited out")
        }
    }

    @Test
    fun `without a token file the client talks to a protocol-1 daemon unauthenticated`() {
        ReferenceServer(socketPath, token = null, profile = "p1").use {
            IpcAuthClient.connect(endpoint, IpcAuth.Scope.FULL).use { ch ->
                ch.configureBlocking(false)
                assertEquals("""{"ok":true,"echo":"hydration.list"}""", ch.ask("""{"verb":"hydration.list","prefix":"/"}"""))
            }
        }
    }

    @Test
    fun `without a token file the client refuses a protocol-2 daemon`() {
        ReferenceServer(socketPath, vectorToken, "p1").use {
            val e = assertFailsWith<IpcAuthException> { IpcAuthClient.connect(endpoint, IpcAuth.Scope.FULL) }
            assertTrue("cannot authenticate" in e.message!!, e.message)
        }
    }

    @Test
    fun `with a token file the client refuses a daemon that does not know the handshake`() {
        writeTokens(vectorToken)
        ReferenceServer(socketPath, token = null, profile = "p1").use {
            assertFailsWith<IpcAuthException> { IpcAuthClient.connect(endpoint, IpcAuth.Scope.FULL) }
        }
    }

    @Test
    fun `a malformed token file fails closed`() {
        for (scope in IpcAuth.Scope.entries) Files.writeString(IpcAuth.tokenFile(profileDir, scope), "short")
        ReferenceServer(socketPath, vectorToken, "p1").use {
            assertFailsWith<IpcAuthException> { IpcAuthClient.connect(endpoint, IpcAuth.Scope.FULL) }
        }
    }

    private fun b64(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

    /**
     * The server side of the handshake as the spec words it, independent of [IpcAuth]: one connection,
     * `token == null` behaves like a protocol-1 daemon (no handshake, `hello` is an unknown verb).
     * After authentication every request is echoed as `{"ok":true,"echo":"<verb>"}`.
     */
    private class ReferenceServer(
        socketPath: Path,
        private val token: ByteArray?,
        private val profile: String,
        private val serverNonce: ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) },
        private val corruptServerProof: Boolean = false,
        private val closeImmediately: Boolean = false,
    ) : AutoCloseable {
        val clientProofs = CopyOnWriteArrayList<String>()
        val scopes = CopyOnWriteArrayList<String>()
        private val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).apply { bind(UnixDomainSocketAddress.of(socketPath)) }
        private val path = socketPath
        private val worker =
            thread(isDaemon = true, name = "reference-ipc-server") {
                runCatching { server.accept() }.getOrNull()?.use { serve(it) }
            }

        private fun serve(ch: SocketChannel) {
            if (closeImmediately) return
            val reader = java.io.BufferedReader(java.nio.channels.Channels.newReader(ch, Charsets.UTF_8))
            fun reply(s: String) {
                val b = ByteBuffer.wrap((s + "\n").toByteArray(Charsets.UTF_8))
                while (b.hasRemaining()) ch.write(b)
            }
            var authenticated = token == null
            var pending: Triple<String, String, String>? = null // scope, client nonce, server nonce
            while (true) {
                val line = reader.readLine() ?: return
                val req = Json.parseToJsonElement(line).jsonObject
                val verb = req["verb"]?.jsonPrimitive?.content
                if (token == null) {
                    when (verb) {
                        "daemon.status" -> reply("""{"ok":true,"protocol_version":1}""")
                        "hello", "hello.proof" -> reply("""{"ok":false,"error":"unknown_verb"}""")
                        else -> reply("""{"ok":true,"echo":"$verb"}""")
                    }
                    continue
                }
                when {
                    authenticated -> reply("""{"ok":true,"echo":"$verb"}""")
                    verb == "daemon.status" -> reply("""{"ok":true,"protocol_version":2,"engine_version":"reference","auth_required":true}""")
                    verb == "hello" -> {
                        val sn = Base64.getUrlEncoder().withoutPadding().encodeToString(serverNonce)
                        pending = Triple(req["scope"]!!.jsonPrimitive.content, req["nonce"]!!.jsonPrimitive.content, sn)
                        reply("""{"ok":true,"step":1,"snonce":"$sn"}""")
                    }
                    verb == "hello.proof" -> {
                        val (scope, cn, sn) = pending ?: return
                        pending = null
                        val proof = req["proof"]!!.jsonPrimitive.content
                        clientProofs.add(proof)
                        scopes.add(scope)
                        val p = profile.toByteArray(Charsets.UTF_8)
                        val prefix = "|$scope|${p.size}:$profile|$cn|$sn"
                        val expected = mac("unidrive-ipc-v2|client$prefix")
                        if (!MessageDigest.isEqual(expected.toByteArray(), proof.toByteArray())) {
                            Thread.sleep(250)
                            reply("""{"ok":false,"error":"auth_failed"}""")
                            continue
                        }
                        authenticated = true
                        val serverProof = mac("unidrive-ipc-v2|server$prefix|$proof").let { if (corruptServerProof) it.reversed() else it }
                        reply("""{"ok":true,"scope":"$scope","protocol_version":2,"proof":"$serverProof"}""")
                    }
                    else -> reply("""{"ok":false,"error":"auth_required"}""")
                }
            }
        }

        private fun mac(message: String): String {
            val m = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(token, "HmacSHA256")) }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(m.doFinal(message.toByteArray(Charsets.UTF_8)))
        }

        override fun close() {
            runCatching { server.close() }
            worker.join(2_000)
            runCatching { Files.deleteIfExists(path) }
        }
    }
}
