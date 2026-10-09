package org.krost.unidrive.internxt

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.socket.engineio.client.EngineIOException
import org.json.JSONObject
import org.krost.unidrive.TransientNetworkException
import org.slf4j.LoggerFactory
import java.net.ConnectException
import java.net.ProtocolException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reconnect / refresh / logging behaviour of [NotificationsClient] against a fake
 * socket, a hand-driven clock and a manual scheduler: no network, no sleeping.
 *
 * Field data (2026-10-01, maintainer's daemon): the notifications host never
 * accepted a connection, yet the client rebuilt its socket every ~10 s for the
 * whole process lifetime (1,264 `connect_error` lines in one day, each followed by
 * a bogus "reconnected after token refresh"), because every `connect_error` was
 * treated as a possible JWT expiry. A rebuilt socket restarts socket.io's backoff,
 * so the library's own exponential reconnect (1 s .. 60 s) never ran.
 *
 * Invariants, one named test each:
 *  - transport errors: no token supplier call, no rebuild, one WARN per outage;
 *  - auth-looking errors: at most one forced refresh per rate-limit window, the
 *    window doubles when a refresh fails or does not help, one rebuild per success;
 *  - one INFO on recovery, and the warning is armed again for the next outage.
 */
class NotificationsClientBackoffTest {
    private class FakeSocket(
        val token: String,
    ) : NotificationsSocket {
        private val listeners = mutableMapOf<String, MutableList<(List<Any?>) -> Unit>>()
        var connectCalls = 0
        var closed = false

        override fun on(
            event: String,
            listener: (List<Any?>) -> Unit,
        ) {
            listeners.getOrPut(event) { mutableListOf() } += listener
        }

        override fun connect() {
            connectCalls++
        }

        // Listeners deliberately survive close(): a late event already queued on
        // socket.io's thread can still reach a superseded socket's handlers.
        override fun close() {
            closed = true
        }

        fun emit(
            event: String,
            vararg args: Any?,
        ) {
            listeners[event].orEmpty().toList().forEach { it(args.toList()) }
        }
    }

    private class FakeFactory : NotificationsSocketFactory {
        val sockets = mutableListOf<FakeSocket>()

        override fun create(
            url: String,
            token: String,
        ): NotificationsSocket = FakeSocket(token).also { sockets += it }
    }

    private class ManualScheduler : NotificationsScheduler {
        val delayed = mutableListOf<Pair<Long, Runnable>>()
        var shutdownCalls = 0

        override fun execute(task: Runnable) = task.run()

        override fun schedule(
            delayMs: Long,
            task: Runnable,
        ) {
            delayed += delayMs to task
        }

        override fun shutdown() {
            shutdownCalls++
        }
    }

    private class Harness {
        var now = 1_000_000L
        val factory = FakeFactory()
        val scheduler = ManualScheduler()
        val supplierCalls = mutableListOf<Boolean>()
        var refreshFailure: Exception? = null
        val wakeHints = AtomicInteger()
        val client =
            NotificationsClient(
                notificationsUrl = "https://example.invalid",
                ownClientName = "unidrive",
                tokenSupplier = { force ->
                    supplierCalls += force
                    refreshFailure?.let { throw it }
                    "fresh-${supplierCalls.size}"
                },
                onRemoteChange = { wakeHints.incrementAndGet() },
                socketFactory = factory,
                clock = { now },
                scheduler = scheduler,
            )
        val socket get() = factory.sockets.last()

        fun advanceSeconds(seconds: Long) {
            now += seconds * 1000
        }
    }

    private val logger = LoggerFactory.getLogger(NotificationsClient::class.java) as ch.qos.logback.classic.Logger
    private lateinit var appender: ListAppender<ILoggingEvent>

    @BeforeTest
    fun attachAppender() {
        appender = ListAppender<ILoggingEvent>().also { it.start() }
        logger.level = Level.DEBUG
        logger.addAppender(appender)
    }

    @AfterTest
    fun detachAppender() {
        logger.detachAppender(appender)
        logger.level = null
    }

    private fun events(level: Level) = appender.list.filter { it.level == level }

    private fun messages() = appender.list.map { it.formattedMessage }

    private fun transportError(cause: Exception = ConnectException("Failed to connect to notifications.internxt.com/162.19.128.4:443")) =
        EngineIOException("websocket error", cause)

    private fun unauthorizedUpgrade(status: String = "401 Unauthorized") =
        EngineIOException("websocket error", ProtocolException("Expected HTTP 101 response but was '$status'"))

    private fun namespaceRejection(message: String = "Authentication error") = JSONObject().put("message", message)

    @Test
    fun `transport errors never call the token supplier or rebuild the socket and warn once`() {
        val h = Harness()
        h.client.connect("token-0")

        repeat(25) {
            h.advanceSeconds(10)
            h.socket.emit("connect_error", transportError())
        }

        assertEquals(0, h.supplierCalls.size, "a transport error says nothing about the token")
        assertEquals(1, h.factory.sockets.size, "the socket must not be rebuilt: socket.io's own backoff has to run")
        assertEquals(0, h.scheduler.delayed.size)
        val warns = events(Level.WARN)
        assertEquals(1, warns.size, "exactly one WARN per outage, got: ${warns.map { it.formattedMessage }}")
        val warn = warns.single().formattedMessage
        assertTrue(warn.contains("Internxt notifications unreachable, polling only"), warn)
        assertTrue(warn.contains("backoff up to 60 s"), warn)
        assertTrue(warn.contains("EngineIOException: websocket error"), "the first error must be in the WARN: $warn")
        assertTrue(warn.contains("ConnectException: Failed to connect to notifications.internxt.com"), "its cause too: $warn")
        assertEquals(24, messages().count { it.contains("connect_error (attempt") }, "the other 24 go to DEBUG")
        assertTrue(events(Level.INFO).isEmpty(), "no INFO without a connect: ${messages()}")
        assertFalse(messages().any { it.contains("after token refresh") }, "no refresh happened, so no such INFO")
    }

    // 2026-10-09: notifications.internxt.com resolved (public DNS too) to a host serving a
    // certificate for *.coolabor.de. Every attempt failed hostname verification and the client
    // retried every <= 60 s for the whole process lifetime. A wrong-server endpoint is permanent:
    // stop after the first error. If this test is removed or loosened, the retry storm returns.
    @Test
    fun `a certificate for another host stops the client after the first error and warns once`() {
        val h = Harness()
        h.client.connect("token-0")

        h.socket.emit(
            "connect_error",
            EngineIOException(
                "websocket error",
                javax.net.ssl.SSLPeerUnverifiedException("Hostname notifications.internxt.com not verified"),
            ),
        )
        repeat(5) { h.socket.emit("connect_error", transportError()) }

        assertTrue(h.socket.closed, "the socket must be closed, so socket.io stops reconnecting")
        assertEquals(1, h.scheduler.shutdownCalls, "the client is stopped")
        assertEquals(1, h.factory.sockets.size, "no rebuild")
        assertEquals(0, h.supplierCalls.size, "a certificate problem says nothing about the token")
        val warns = events(Level.WARN).map { it.formattedMessage }
        assertEquals(1, warns.size, "one WARN, and later errors are ignored: $warns")
        assertTrue(warns.single().contains("is not usable (TLS: the server's certificate is not valid for this host name)"), warns.single())
        assertTrue(warns.single().contains("Restart the process to try again"), warns.single())
        assertFalse(messages().any { it.contains("connect_error (attempt") }, "nothing is handled after the stop: ${messages()}")
    }

    @Test
    fun `an untrusted certificate is permanent too`() {
        val handshake =
            javax.net.ssl.SSLHandshakeException("PKIX path building failed").apply {
                initCause(java.security.cert.CertificateException("unable to find valid certification path"))
            }
        assertEquals(
            "TLS: the server's certificate is not trusted",
            NotificationsClient.permanentFailure(EngineIOException("websocket error", handshake)),
        )
    }

    // The endpoint must answer with a websocket upgrade. A page (200), a redirect or 404/410 means
    // it is not the notifications server; 5xx, 429 and 408 may pass and keep the backoff.
    @Test
    fun `an upgrade answered with a non-upgrade status is permanent but temporary statuses are not`() {
        for (status in listOf("200 OK", "301 Moved Permanently", "404 Not Found", "410 Gone")) {
            val reason = NotificationsClient.permanentFailure(unauthorizedUpgrade(status))
            assertEquals("the websocket upgrade was answered with HTTP ${status.take(3)}", reason, status)
        }
        for (status in listOf("500 Internal Server Error", "502 Bad Gateway", "503 Service Unavailable", "429 Too Many Requests", "408 Request Timeout")) {
            assertNull(NotificationsClient.permanentFailure(unauthorizedUpgrade(status)), "$status must keep the backoff")
        }
    }

    @Test
    fun `offline and refused connections are not permanent and keep the backoff`() {
        assertNull(NotificationsClient.permanentFailure(transportError()))
        assertNull(NotificationsClient.permanentFailure(transportError(java.net.UnknownHostException("notifications.internxt.com"))))
        assertNull(NotificationsClient.permanentFailure(transportError(java.net.SocketTimeoutException("connect timed out"))))
        val h = Harness()
        h.client.connect("token-0")
        h.socket.emit("connect_error", transportError())
        assertFalse(h.socket.closed, "a transport error must not stop the client")
        assertEquals(0, h.scheduler.shutdownCalls)
    }

    @Test
    fun `an auth rejection is never classified permanent`() {
        val h = Harness()
        h.client.connect("token-0")
        h.socket.emit("connect_error", unauthorizedUpgrade("401 Unauthorized"))
        assertFalse(h.socket.closed, "401 goes to the refresh path, not the permanent stop")
        assertEquals(listOf(true), h.supplierCalls, "one forced refresh")
    }

    @Test
    fun `transport errors that merely mention 401 403 or proxy auth are not auth rejections`() {
        val h = Harness()
        h.client.connect("token-0")
        val benign =
            listOf(
                ConnectException("Failed to connect to notifications.internxt.com/10.1.2.3:403"),
                ConnectException("Failed to connect to host/1.2.3.4:401"),
                ProtocolException("Expected HTTP 101 response but was '502 Bad Gateway'"),
                ProtocolException("Expected HTTP 101 response but was '407 Proxy Authentication Required'"),
                java.net.UnknownHostException("Unable to resolve host \"notifications.internxt.com\""),
                java.net.SocketTimeoutException("timeout"),
            )
        benign.forEach {
            h.advanceSeconds(120)
            h.socket.emit("connect_error", transportError(it))
        }
        assertEquals(0, h.supplierCalls.size)
        assertEquals(1, h.factory.sockets.size)
    }

    @Test
    fun `looksLikeAuthRejection reads the whole cause chain and text payloads`() {
        assertTrue(NotificationsClient.looksLikeAuthRejection(unauthorizedUpgrade()))
        assertTrue(NotificationsClient.looksLikeAuthRejection(unauthorizedUpgrade("403 Forbidden")))
        assertTrue(NotificationsClient.looksLikeAuthRejection(namespaceRejection("Invalid token")))
        assertTrue(NotificationsClient.looksLikeAuthRejection(namespaceRejection("jwt expired")))
        assertTrue(NotificationsClient.looksLikeAuthRejection(RuntimeException("outer", RuntimeException("mid", RuntimeException("HTTP 401")))))
        assertFalse(NotificationsClient.looksLikeAuthRejection(null))
        assertFalse(NotificationsClient.looksLikeAuthRejection(transportError()))
        assertFalse(NotificationsClient.looksLikeAuthRejection("websocket error"))
    }

    @Test
    fun `auth rejection on the upgrade forces exactly one refresh then the rate limit holds`() {
        val h = Harness()
        h.client.connect("token-0")
        val first = h.socket

        first.emit("connect_error", unauthorizedUpgrade())

        assertEquals(listOf(true), h.supplierCalls, "one forced refresh")
        assertEquals(2, h.factory.sockets.size, "one rebuild after the successful refresh")
        assertTrue(first.closed, "the stale socket is released")
        assertEquals("fresh-1", h.socket.token, "the rebuilt socket carries the refreshed token")
        assertEquals(1, h.socket.connectCalls)
        assertEquals(1, messages().count { it.contains("rebuilt with a refreshed token") })

        // The rebuilt socket is rejected again: inside the 60 s window nothing happens.
        h.advanceSeconds(10)
        h.socket.emit("connect_error", unauthorizedUpgrade())
        h.advanceSeconds(20)
        h.socket.emit("connect_error", unauthorizedUpgrade())
        assertEquals(1, h.supplierCalls.size, "rate limited")
        assertEquals(2, h.factory.sockets.size)

        // Window open (61 s after the first refresh): one more.
        h.advanceSeconds(31)
        h.socket.emit("connect_error", unauthorizedUpgrade())
        assertEquals(2, h.supplierCalls.size)
        assertEquals(3, h.factory.sockets.size)
    }

    @Test
    fun `403 on the upgrade and a namespace level auth message are auth rejections too`() {
        val forbidden = Harness()
        forbidden.client.connect("token-0")
        forbidden.socket.emit("connect_error", unauthorizedUpgrade("403 Forbidden"))
        assertEquals(1, forbidden.supplierCalls.size)
        assertEquals(2, forbidden.factory.sockets.size)

        val namespace = Harness()
        namespace.client.connect("token-0")
        namespace.socket.emit("connect_error", namespaceRejection("Authentication error"))
        assertEquals(1, namespace.supplierCalls.size)
        assertEquals(2, namespace.factory.sockets.size)
        assertEquals("fresh-1", namespace.socket.token)
    }

    @Test
    fun `a failed refresh doubles the wait up to fifteen minutes and warns once with the status only`() {
        val h = Harness()
        h.refreshFailure =
            TransientNetworkException("Token refresh failed (HTTP 502), retry_after: 60: <html>SECRET-BODY-OF-A-CLOUDFLARE-PAGE</html>")
        h.client.connect("token-0")
        var attemptAt = h.now
        h.socket.emit("connect_error", unauthorizedUpgrade())
        assertEquals(1, h.supplierCalls.size)

        for (windowSeconds in listOf(120L, 240L, 480L, 900L, 900L)) {
            val callsBefore = h.supplierCalls.size
            h.now = attemptAt + (windowSeconds - 1) * 1000
            h.socket.emit("connect_error", unauthorizedUpgrade())
            assertEquals(callsBefore, h.supplierCalls.size, "still inside the ${windowSeconds}s window")
            h.now = attemptAt + (windowSeconds + 1) * 1000
            h.socket.emit("connect_error", unauthorizedUpgrade())
            assertEquals(callsBefore + 1, h.supplierCalls.size, "window of ${windowSeconds}s is over")
            attemptAt = h.now
        }

        assertEquals(1, h.factory.sockets.size, "a failed refresh keeps the old socket")
        val warns = events(Level.WARN)
        assertEquals(2, warns.size, "one 'unreachable' + one refresh failure, got: ${warns.map { it.formattedMessage }}")
        val refreshWarn = warns.single { it.formattedMessage.contains("token refresh failed") }
        assertTrue(refreshWarn.formattedMessage.contains("HTTP 502"), refreshWarn.formattedMessage)
        assertFalse(refreshWarn.formattedMessage.contains("SECRET-BODY"), "status only, never the body")
        assertNull(refreshWarn.throwableProxy, "no stack trace at WARN")
        assertTrue(
            appender.list.any { it.level == Level.DEBUG && it.throwableProxy != null },
            "the stack trace is available at DEBUG",
        )
    }

    @Test
    fun `a destroyed socket whose refresh failed is retried on a timer and a rejection inside the window schedules one retry`() {
        val h = Harness()
        h.refreshFailure = TransientNetworkException("Token refresh failed (HTTP 503)")
        h.client.connect("token-0")
        val first = h.socket

        first.emit("connect_error", namespaceRejection())
        assertEquals(1, h.supplierCalls.size)
        assertEquals(1, h.scheduler.delayed.size, "nothing else will ever wake a destroyed socket")
        assertEquals(120_000L, h.scheduler.delayed.single().first)

        h.refreshFailure = null
        h.advanceSeconds(120)
        h.scheduler.delayed.single().second.run()
        assertEquals(2, h.supplierCalls.size)
        assertEquals(2, h.factory.sockets.size)
        assertEquals("fresh-2", h.socket.token)

        // The rebuilt socket is destroyed again right away: refresh not due, one retry queued, not many.
        h.advanceSeconds(5)
        h.socket.emit("connect_error", namespaceRejection())
        h.advanceSeconds(5)
        h.socket.emit("connect_error", namespaceRejection())
        assertEquals(2, h.supplierCalls.size)
        assertEquals(2, h.scheduler.delayed.size, "exactly one new retry")
    }

    @Test
    fun `a refresh that does not cure the rejection backs off further`() {
        val h = Harness()
        h.client.connect("token-0")
        h.socket.emit("connect_error", unauthorizedUpgrade())
        assertEquals(1, h.supplierCalls.size)

        h.advanceSeconds(61)
        h.socket.emit("connect_error", unauthorizedUpgrade())
        assertEquals(2, h.supplierCalls.size, "second window (60 s) is open")

        h.advanceSeconds(61)
        h.socket.emit("connect_error", unauthorizedUpgrade())
        assertEquals(2, h.supplierCalls.size, "the wait doubled to 120 s")

        h.advanceSeconds(60)
        h.socket.emit("connect_error", unauthorizedUpgrade())
        assertEquals(3, h.supplierCalls.size)
    }

    @Test
    fun `a successful connect resets the refresh window`() {
        val h = Harness()
        h.client.connect("token-0")
        h.socket.emit("connect_error", unauthorizedUpgrade())
        assertEquals(1, h.supplierCalls.size)

        h.advanceSeconds(5)
        h.socket.emit("connect") // the fresh token was accepted
        h.advanceSeconds(1)
        h.socket.emit("connect_error", unauthorizedUpgrade()) // token expires mid-session
        assertEquals(2, h.supplierCalls.size, "no 60 s penalty after a healthy connect")
    }

    @Test
    fun `recovery logs one INFO with the outage summary and the warning is armed again`() {
        val h = Harness()
        h.client.connect("token-0")
        repeat(3) {
            h.advanceSeconds(10)
            h.socket.emit("connect_error", transportError())
        }
        h.advanceSeconds(10)
        h.socket.emit("connect")

        val infos = events(Level.INFO)
        assertEquals(1, infos.size, "one INFO on recovery: ${messages()}")
        assertTrue(infos.single().formattedMessage.contains("WS connected"), infos.single().formattedMessage)
        assertTrue(infos.single().formattedMessage.contains("recovered after 3 failed attempts over 30 s"), infos.single().formattedMessage)
        assertEquals(1, h.wakeHints.get(), "the engine still gets its catch-up wake hint")

        // A new outage warns again, and its recovery is announced again.
        h.advanceSeconds(10)
        h.socket.emit("connect_error", transportError())
        h.advanceSeconds(10)
        h.socket.emit("connect_error", transportError())
        assertEquals(2, events(Level.WARN).size)
        h.socket.emit("connect")
        assertEquals(2, events(Level.INFO).size)
        assertEquals(0, h.supplierCalls.size)
    }

    @Test
    fun `a clean first connect logs a plain INFO`() {
        val h = Harness()
        h.client.connect("token-0")
        h.socket.emit("connect")
        val info = events(Level.INFO).single().formattedMessage
        assertEquals("Internxt notifications WS connected", info)
        assertTrue(events(Level.WARN).isEmpty())
    }

    @Test
    fun `events from a superseded socket are ignored`() {
        val h = Harness()
        h.client.connect("token-0")
        val old = h.socket
        old.emit("connect_error", unauthorizedUpgrade())
        assertEquals(2, h.factory.sockets.size)
        val warnsBefore = events(Level.WARN).size

        h.advanceSeconds(120)
        old.emit("connect_error", unauthorizedUpgrade())
        old.emit("connect_error", transportError())
        old.emit("connect")

        assertEquals(1, h.supplierCalls.size)
        assertEquals(2, h.factory.sockets.size)
        assertEquals(warnsBefore, events(Level.WARN).size)
        assertEquals(0, h.wakeHints.get())
    }

    @Test
    fun `disconnect releases the scheduler and later errors are ignored`() {
        val h = Harness()
        h.client.connect("token-0")
        val socket = h.socket
        h.client.disconnect()
        h.client.disconnect() // idempotent

        assertTrue(socket.closed)
        assertEquals(1, h.scheduler.shutdownCalls)
        socket.emit("connect_error", unauthorizedUpgrade())
        assertEquals(0, h.supplierCalls.size)
        assertTrue(events(Level.WARN).isEmpty())
    }
}
