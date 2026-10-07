package org.krost.unidrive.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The JSON the IPC server writes itself (the `sync.subscribe` state dump, the `handler_threw`
 * reply) and how it reads the verb of a request (private tracker, items F9/F10).
 *
 * Every reply and event is one NDJSON line, so a value that carries a line break, a quote or a
 * control character must come out escaped: exactly one line that parses back to the same value.
 * The verb is the top-level string member `verb` of the request object, nothing else.
 */
class IpcServerJsonTest {
    private lateinit var socketDir: Path
    private lateinit var socketPath: Path
    private var server: IpcServer? = null
    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @BeforeTest
    fun setUp() {
        socketDir = Files.createTempDirectory("unidrive-ipc-json")
        socketPath = socketDir.resolve("json.sock")
    }

    @AfterTest
    fun tearDown() {
        serverScope.cancel()
        server?.close()
        Files.deleteIfExists(socketPath)
        Files.deleteIfExists(socketDir)
    }

    // A value with every kind of character a quote-and-backslash-only escaper lets through: line
    // breaks, a tab, other C0 controls, DEL, and the three characters some line readers also end
    // a line at (NEL, LINE SEPARATOR, PARAGRAPH SEPARATOR).
    private val awkward: String =
        buildString {
            append("dir/na\"me\\with")
            append('\n')
            append("second line")
            append('\r')
            append('\t')
            append(Char(0x01))
            append(Char(0x1f))
            append(Char(0x7f))
            append(Char(0x85))
            append(Char(0x2028))
            append(Char(0x2029))
            append(" end")
        }

    private val lineBreakers = setOf('\n', '\r', Char(0x85), Char(0x2028), Char(0x2029))

    // One NDJSON line: no raw control character and no character a line reader could split at.
    private fun assertOneCleanLine(line: String) {
        val bad = line.filter { it < ' ' || it in lineBreakers }
        assertTrue(bad.isEmpty(), "line carries raw line-breaking/control characters ${bad.map { it.code }}: $line")
    }

    @Test
    fun `jsonString escapes every character that can break or end a line and round-trips`() {
        val quoted = IpcServer.jsonString(awkward)
        assertOneCleanLine(quoted)
        assertEquals(awkward, Json.parseToJsonElement(quoted).jsonPrimitive.content)
        assertEquals("\"plain\"", IpcServer.jsonString("plain"))
        assertEquals("\"\"", IpcServer.jsonString(""))
    }

    @Test
    fun `state dump of sync subscribe is one valid line per event whatever the names carry`() =
        runBlocking(Dispatchers.IO) {
            val srv = IpcServer(socketPath).also { server = it }
            srv.registerHandler("sync.subscribe") { connId, _ ->
                srv.scheduleAfterReply(connId) {
                    srv.flushStateDumpTo(connId)
                    srv.registerSyncSubscriber(connId)
                }
                """{"ok":true}"""
            }
            val profile = "pro\"file\\" + awkward
            srv.updateState(
                IpcServer.SyncState(
                    profile = profile,
                    phase = awkward,
                    scanCount = 7,
                    actionTotal = 3,
                    actionIndex = 1,
                    lastAction = awkward,
                    lastPath = "/folder/$awkward.txt",
                ),
            )
            srv.start(serverScope)

            connect().use { client ->
                send(client, """{"verb":"sync.subscribe"}""")
                val lines = readLines(client, expected = 5)

                assertEquals(5, lines.size, "the reply plus four state-dump events, one line each; got:\n${lines.joinToString("\n")}")
                lines.forEach { assertOneCleanLine(it) }
                val events = lines.drop(1).map { Json.parseToJsonElement(it).jsonObject }
                assertEquals(
                    listOf("sync_started", "scan_progress", "action_count", "action_progress"),
                    events.map { it.getValue("event").jsonPrimitive.content },
                )
                events.forEach { assertEquals(profile, it.getValue("profile").jsonPrimitive.content) }
                assertEquals(awkward, events[1].getValue("phase").jsonPrimitive.content)
                assertEquals(awkward, events[3].getValue("action").jsonPrimitive.content)
                assertEquals("/folder/$awkward.txt", events[3].getValue("path").jsonPrimitive.content)
            }
        }

    @Test
    fun `handler_threw reply is one valid line whatever the exception message carries`() =
        runBlocking(Dispatchers.IO) {
            val srv = IpcServer(socketPath).also { server = it }
            srv.registerHandler("boom") { _, _ -> throw IllegalStateException(awkward) }
            srv.start(serverScope)

            connect().use { client ->
                send(client, """{"verb":"boom"}""")
                val lines = readLines(client, expected = 1)

                assertEquals(1, lines.size, "exactly one reply line; got:\n${lines.joinToString("\n")}")
                assertOneCleanLine(lines[0])
                val reply = Json.parseToJsonElement(lines[0]).jsonObject
                assertEquals("handler_threw", reply.getValue("error").jsonPrimitive.content)
                assertEquals("boom", reply.getValue("verb").jsonPrimitive.content)
                assertEquals(awkward, reply.getValue("message").jsonPrimitive.content)
            }
        }

    @Test
    fun `the verb is the top-level string member of the request object`() =
        runBlocking(Dispatchers.IO) {
            val srv = IpcServer(socketPath).also { server = it }
            for (verb in listOf("a", "b")) {
                srv.registerHandler(verb) { _, _ -> """{"ok":true,"handled":"$verb"}""" }
            }
            srv.start(serverScope)

            val bs = '\\'
            val nested = "[".repeat(32) + "]".repeat(32)
            val deepArrays = "[".repeat(20_000) + "]".repeat(20_000)
            val deepObjects = """{"x":""".repeat(5_000) + "1" + "}".repeat(5_000)
            // request line -> expected outcome ("handled:<verb>", or the error token)
            val cases =
                listOf(
                    """{"verb":"b"}""" to "handled:b",
                    """  { "verb" : "b" }  """ to "handled:b",
                    "{\"verb\":\"b\"}\r" to "handled:b",
                    """{"verb":"b","x":{"verb":"a"}}""" to "handled:b",
                    // a nested member named verb is not the verb
                    """{"x":{"verb":"a"},"verb":"b"}""" to "handled:b",
                    """{"x":["verb","a"],"verb":"b"}""" to "handled:b",
                    // a string VALUE that spells verb is not a member name
                    """{"note":"verb","verb":"b"}""" to "handled:b",
                    """{"note":"{${bs}"verb${bs}":${bs}"a${bs}"}","verb":"b"}""" to "handled:b",
                    // the value is decoded like any JSON string
                    """{"verb":"${bs}u0062"}""" to "handled:b",
                    // some nesting elsewhere in the request is fine
                    """{"x":$nested,"verb":"b"}""" to "handled:b",
                    // a raw tab inside a string value, as a sloppy client may send it
                    "{\"verb\":\"b\",\"prefix\":\"a\tb\"}" to "handled:b",
                    // two top-level verb members are ambiguous: refused, whichever order or spelling
                    """{"verb":"a","verb":"b"}""" to "missing_verb",
                    """{"verb":"b","verb":"a"}""" to "missing_verb",
                    """{"verb":"b","${bs}u0076erb":"a"}""" to "missing_verb",
                    // verb present but not a string
                    """{"verb":1}""" to "missing_verb",
                    """{"verb":null}""" to "missing_verb",
                    """{"verb":true}""" to "missing_verb",
                    """{"verb":["b"]}""" to "missing_verb",
                    """{"verb":{"verb":"b"}}""" to "missing_verb",
                    // not an object
                    """[{"verb":"b"}]""" to "missing_verb",
                    """"{\"verb\":\"b\"}"""" to "missing_verb",
                    """{"note":"{${bs}"verb${bs}":${bs}"b${bs}"}"}""" to "missing_verb",
                    // malformed
                    """{"verb":"b"""" to "missing_verb",
                    """{"verb":"b"} trailing""" to "missing_verb",
                    """{"verb":"b"}{"verb":"a"}""" to "missing_verb",
                    """{verb:"b"}""" to "missing_verb",
                    // nesting far deeper than any request needs is refused, and the connection
                    // keeps working (the next cases run on the same connection)
                    """{"x":$deepArrays,"verb":"b"}""" to "missing_verb",
                    """{"x":$deepObjects,"verb":"b"}""" to "missing_verb",
                    // a verb nobody registered stays unknown
                    """{"verb":"c"}""" to "unknown_verb",
                    """{"verb":"b"}""" to "handled:b",
                )

            val mismatches = mutableListOf<String>()
            connect().use { client ->
                for ((request, expected) in cases) {
                    send(client, request)
                    val lines = readLines(client, expected = 1, quietMs = 100)
                    val actual =
                        if (lines.size != 1) {
                            "lines=${lines.size}"
                        } else {
                            val reply = Json.parseToJsonElement(lines[0]) as JsonObject
                            reply["handled"]?.let { "handled:${it.jsonPrimitive.content}" }
                                ?: reply["error"]?.jsonPrimitive?.content
                                ?: lines[0]
                        }
                    if (actual != expected) mismatches += "${request.take(60)} -> $actual (expected $expected)"
                }
            }
            assertTrue(mismatches.isEmpty(), "verb parsing differs:\n${mismatches.joinToString("\n")}")
        }

    // ── helpers ──────────────────────────────────────────────────────────────

    private suspend fun connect(): SocketChannel {
        val deadline = System.currentTimeMillis() + 3_000
        while (true) {
            try {
                val ch = SocketChannel.open(StandardProtocolFamily.UNIX)
                ch.connect(UnixDomainSocketAddress.of(socketPath))
                ch.configureBlocking(false)
                return ch
            } catch (e: java.io.IOException) {
                if (System.currentTimeMillis() > deadline) throw e
                delay(20)
            }
        }
    }

    private suspend fun send(client: SocketChannel, request: String) {
        val buf = ByteBuffer.wrap((request + "\n").toByteArray(Charsets.UTF_8))
        val deadline = System.currentTimeMillis() + 5_000
        while (buf.hasRemaining()) {
            client.write(buf)
            if (buf.hasRemaining()) {
                check(System.currentTimeMillis() < deadline) { "write timeout" }
                delay(5)
            }
        }
    }

    // Reads newline-terminated lines until [expected] have arrived, then keeps reading for a short
    // quiet window so a value that split its line into several shows up as extra lines.
    private suspend fun readLines(client: SocketChannel, expected: Int, quietMs: Long = 300): List<String> {
        val buf = ByteBuffer.allocate(16 * 1024)
        val bytes = java.io.ByteArrayOutputStream()
        var newlines = 0
        // Generous: under a full parallel build the first reply (class loading, the logged stack
        // trace of handler_threw) can take seconds. A passing read ends at the quiet window.
        val deadline = System.currentTimeMillis() + 15_000
        var quietUntil = Long.MAX_VALUE
        while (System.currentTimeMillis() < minOf(deadline, quietUntil)) {
            buf.clear()
            val n = client.read(buf)
            if (n < 0) break
            if (n > 0) {
                bytes.write(buf.array(), 0, n)
                // UTF-8 never encodes another character with the byte 0x0A.
                for (i in 0 until n) if (buf.array()[i] == '\n'.code.toByte()) newlines++
                if (quietUntil == Long.MAX_VALUE && newlines >= expected) {
                    quietUntil = System.currentTimeMillis() + quietMs
                }
            } else {
                delay(10)
            }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8).split('\n').dropLast(1)
    }
}
