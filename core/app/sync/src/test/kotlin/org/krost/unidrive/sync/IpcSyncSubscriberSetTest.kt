package org.krost.unidrive.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
 * Protects the invariants from
 * `docs/dev/specs/sync-progress-subscriber-set-design.md`.
 *
 * If any of these tests are deleted or loosened, the corresponding
 * invariant silently regresses and the Phase 2 co-daemon Broken-pipe
 * cycle (BACKLOG entry 1de0cb3, refined in 013da46) returns.
 */
class IpcSyncSubscriberSetTest {
    private lateinit var socketDir: Path
    private lateinit var socketPath: Path
    private var server: IpcServer? = null

    @BeforeTest
    fun setUp() {
        socketDir = Files.createTempDirectory("unidrive-ipc-sub-test")
        socketPath = socketDir.resolve("test.sock")
    }

    @AfterTest
    fun tearDown() {
        server?.close()
        Files.deleteIfExists(socketPath)
        Files.deleteIfExists(socketDir)
    }

    private fun connectClient(): SocketChannel {
        val client = SocketChannel.open(StandardProtocolFamily.UNIX)
        client.connect(UnixDomainSocketAddress.of(socketPath))
        client.configureBlocking(false)
        return client
    }

    private suspend fun readAvailableBytes(
        client: SocketChannel,
        windowMs: Long,
    ): ByteArray {
        val buf = ByteBuffer.allocate(8192)
        val acc = java.io.ByteArrayOutputStream()
        val deadline = System.currentTimeMillis() + windowMs
        while (System.currentTimeMillis() < deadline) {
            buf.clear()
            val n = client.read(buf)
            if (n > 0) {
                buf.flip()
                val bytes = ByteArray(buf.remaining()); buf.get(bytes)
                acc.write(bytes)
            }
            delay(20)
        }
        return acc.toByteArray()
    }

    /**
     * The Phase 2 contract test. A connected client that did NOT issue
     * `sync.subscribe` must receive zero bytes from `server.emit(...)`
     * fanout. Without the fix this fails — the broadcast loop fans to
     * all clients regardless. With the fix it passes deterministically.
     */
    @Test
    fun non_subscriber_receives_no_sync_progress_bytes() =
        runBlocking(Dispatchers.IO) {
            val serverScope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val s = IpcServer(socketPath)
                server = s
                s.start(serverScope)

                val client = connectClient()
                // Wait briefly so accept loop has registered the client.
                delay(100)

                // Fire several broadcast events.
                repeat(5) { i ->
                    s.emit("""{"event":"test","seq":$i}""")
                }

                // Read with a 500ms window. Without the fix, we receive at
                // least one of the five lines. With the fix, zero bytes.
                val received = readAvailableBytes(client, windowMs = 500)
                assertEquals(
                    0, received.size,
                    "Non-subscriber received ${received.size} bytes of " +
                        "unsolicited broadcast traffic: ${String(received).take(200)}",
                )
                client.close()
            } finally {
                serverScope.cancel()
            }
        }

    /**
     * T2 from spec §3.5: subscriber receives reply first, then state
     * dump in defined order, then live events. Pins the wire contract
     * from spec §3.1.
     */
    @Test
    fun subscriber_receives_reply_first_then_state_dump_then_live_events() =
        runBlocking(Dispatchers.IO) {
            val serverScope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val s = IpcServer(socketPath)
                server = s
                s.start(serverScope)
                s.updateState(
                    IpcServer.SyncState(
                        profile = "p",
                        phase = "reconcile",
                        scanCount = 42,
                        actionTotal = 10,
                        actionIndex = 3,
                        lastAction = "Upload",
                        lastPath = "/foo.txt",
                    ),
                )

                // Register sync.subscribe handler — production code lives in
                // SyncCommand, but the test only exercises IpcServer mechanics.
                s.registerHandler("sync.subscribe") { connId, _ ->
                    s.scheduleAfterReply(connId) {
                        s.flushStateDumpTo(connId)
                        s.registerSyncSubscriber(connId)
                    }
                    """{"ok":true}"""
                }

                val client = connectClient()
                delay(100)

                // Send sync.subscribe.
                val req = """{"verb":"sync.subscribe"}""" + "\n"
                val w = ByteBuffer.wrap(req.toByteArray(Charsets.UTF_8))
                while (w.hasRemaining()) client.write(w)

                // Read 5 lines: reply + 4 state-dump events.
                val first5 = drainLines(client, count = 5, timeoutMs = 2_000)
                assertTrue(first5[0].contains("\"ok\":true"),
                    "Line 1 must be the subscribe reply, got: ${first5[0]}")
                assertTrue(first5[1].contains("sync_started"),
                    "Line 2 must be sync_started, got: ${first5[1]}")
                assertTrue(first5[2].contains("scan_progress"),
                    "Line 3 must be scan_progress, got: ${first5[2]}")
                assertTrue(first5[3].contains("action_count"),
                    "Line 4 must be action_count, got: ${first5[3]}")
                assertTrue(first5[4].contains("action_progress"),
                    "Line 5 must be action_progress, got: ${first5[4]}")

                // Emit a live event; must arrive AFTER the state dump.
                s.emit("""{"event":"live","seq":1}""")
                val live = drainLines(client, count = 1, timeoutMs = 1_000)
                assertTrue(live[0].contains("\"event\":\"live\""),
                    "Live event must follow state dump, got: ${live[0]}")
                client.close()
            } finally {
                serverScope.cancel()
            }
        }

    /**
     * Read exactly `count` newline-terminated lines from `client` within
     * `timeoutMs`. Returns the lines (with newlines stripped). Fails the
     * test if fewer than `count` lines arrive within the deadline.
     */
    private suspend fun drainLines(
        client: SocketChannel,
        count: Int,
        timeoutMs: Long,
    ): List<String> {
        val buf = ByteBuffer.allocate(8192)
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            buf.clear()
            val n = client.read(buf)
            if (n > 0) {
                buf.flip()
                val bytes = ByteArray(buf.remaining()); buf.get(bytes)
                sb.append(String(bytes, Charsets.UTF_8))
                val lines = sb.toString().split("\n").filter { it.isNotEmpty() }
                if (lines.size >= count) return lines.take(count)
            }
            delay(20)
        }
        val partial = sb.toString().split("\n").filter { it.isNotEmpty() }
        kotlin.test.fail(
            "Expected $count lines within ${timeoutMs}ms; got ${partial.size}: $partial",
        )
    }

    /**
     * T3 from spec §3.5: state-dump JSON is parseable even when path/action
     * fields contain `"` or `\`. Pins the §3.2.4 escapeJson co-fix.
     */
    @Test
    fun state_dump_handles_special_chars_in_path_via_escape_json() =
        runBlocking(Dispatchers.IO) {
            val serverScope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val s = IpcServer(socketPath)
                server = s
                s.start(serverScope)
                s.updateState(
                    IpcServer.SyncState(
                        profile = "p",
                        actionTotal = 1,
                        actionIndex = 1,
                        lastAction = "Upload",
                        // Path containing both `"` and `\`. In a Kotlin
                        // triple-quoted string, " and \ are literal.
                        lastPath = """/docs/"hello"\bs.txt""",
                    ),
                )
                s.registerHandler("sync.subscribe") { connId, _ ->
                    s.scheduleAfterReply(connId) {
                        s.flushStateDumpTo(connId)
                        s.registerSyncSubscriber(connId)
                    }
                    """{"ok":true}"""
                }
                val client = connectClient()
                delay(100)
                val req = """{"verb":"sync.subscribe"}""" + "\n"
                val w = ByteBuffer.wrap(req.toByteArray(Charsets.UTF_8))
                while (w.hasRemaining()) client.write(w)

                // Read reply + sync_started + action_count + action_progress
                // (4 lines; no phase = no scan_progress line).
                val lines = drainLines(client, count = 4, timeoutMs = 2_000)
                // Every line must parse as valid JSON.
                val json = kotlinx.serialization.json.Json
                lines.forEach { line ->
                    runCatching { json.parseToJsonElement(line) }
                        .onFailure {
                            kotlin.test.fail(
                                "Line did not parse as JSON: $line\nCause: ${it.message}",
                            )
                        }
                }
                client.close()
            } finally {
                serverScope.cancel()
            }
        }

    /**
     * T4 from spec §3.5: when a subscriber disconnects, its connId is
     * removed from syncSubscribers within the close-listener cycle.
     * Pins the SyncCommand-side cleanup contract; this test exercises
     * IpcServer in isolation by registering a close-listener inline.
     */
    @Test
    fun subscriber_set_is_cleaned_up_on_disconnect() =
        runBlocking(Dispatchers.IO) {
            val serverScope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                val s = IpcServer(socketPath)
                server = s
                s.registerConnectionCloseListener { connId ->
                    s.unregisterSyncSubscriber(connId)
                }
                s.registerHandler("sync.subscribe") { connId, _ ->
                    s.scheduleAfterReply(connId) {
                        s.registerSyncSubscriber(connId)
                    }
                    """{"ok":true}"""
                }
                s.start(serverScope)

                val client = connectClient()
                delay(100)
                val req = """{"verb":"sync.subscribe"}""" + "\n"
                val w = ByteBuffer.wrap(req.toByteArray(Charsets.UTF_8))
                while (w.hasRemaining()) client.write(w)
                drainLines(client, count = 1, timeoutMs = 2_000)  // reply

                // Wait briefly so the post-reply hook registers the subscriber.
                val regDeadline = System.currentTimeMillis() + 1_000
                while (s.syncSubscribersSnapshot.isEmpty() &&
                    System.currentTimeMillis() < regDeadline) {
                    delay(20)
                }
                assertTrue(
                    s.syncSubscribersSnapshot.isNotEmpty(),
                    "Subscriber should be registered within 1s of receiving reply",
                )

                // Now close the client and wait for the close listener.
                client.close()
                val cleanupDeadline = System.currentTimeMillis() + 1_000
                while (s.syncSubscribersSnapshot.isNotEmpty() &&
                    System.currentTimeMillis() < cleanupDeadline) {
                    delay(20)
                }
                assertEquals(
                    emptySet<String>(), s.syncSubscribersSnapshot,
                    "Subscriber set should be empty within 1s of disconnect",
                )
            } finally {
                serverScope.cancel()
            }
        }
}
