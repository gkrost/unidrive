package org.krost.unidrive.internxt

import io.socket.client.IO
import io.socket.client.Socket
import org.slf4j.LoggerFactory
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Manages the socket.io connection to Internxt's `NOTIFICATIONS_URL` change
 * feed. Each remote-origin frame triggers the supplied [callback] — a coarse
 * "something changed, walk the delta" wake-signal, not authoritative payload.
 * Loopback frames (those carrying our own [InternxtConfig.clientName] as the
 * `clientId`) are dropped silently — they're our own mutations echoing back.
 *
 * Lifecycle is owned by [InternxtProvider]: constructed (lazily) on
 * authenticate success when a JWT is available, disconnected on `close()`
 * and `logout()`. Reconnection (network drops, ingress failover) is handled
 * by socket.io itself — we configure exponential backoff with a cap and
 * fire one wake hint on each successful (re)connect so the engine catches
 * up on any events that fell in the window.
 *
 * Token expiry mid-session: socket.io sets `auth` once at connect time. If
 * the server emits `connect_error` with an auth-related message, we fetch
 * a fresh JWT via [tokenRefresh] and rebuild the socket. If refresh itself
 * fails we log once and stay disconnected — the periodic poll is the
 * safety net.
 */
internal class NotificationsClient(
    private val notificationsUrl: String,
    private val ownClientName: String,
    private val tokenSupplier: suspend (forceRefresh: Boolean) -> String,
    private val onRemoteChange: () -> Unit,
) {
    private val log = LoggerFactory.getLogger(NotificationsClient::class.java)
    private val running = AtomicBoolean(false)
    private val socketRef = AtomicReference<Socket?>(null)
    private val refreshInProgress = AtomicBoolean(false)

    /**
     * Connect to the notifications endpoint with the supplied JWT. Idempotent
     * — a second call after a successful connect is a no-op. On failure,
     * logs once at WARN and lets the socket.io reconnect loop continue
     * quietly at DEBUG.
     *
     * Caller is responsible for ensuring [token] is fresh enough not to be
     * rejected on the handshake (typically by calling
     * `AuthService.getValidCredentials()` immediately beforehand).
     */
    fun connect(token: String) {
        if (!running.compareAndSet(false, true)) {
            log.debug("connect() called while already running; ignoring")
            return
        }
        try {
            val socket = buildSocket(token)
            socketRef.set(socket)
            socket.connect()
        } catch (e: Exception) {
            running.set(false)
            // Single WARN, not a spammed reconnect log. The audit's failure
            // model is "WS is a latency win, polling is the safety net" —
            // a connect failure mustn't crash the daemon.
            log.warn("Internxt notifications WS connect failed; falling back to poll-only sync", e)
        }
    }

    /**
     * Disconnect and release the socket.io background threads. Idempotent.
     * MUST be called from `InternxtProvider.close()` / `logout()` — the
     * socket.io client owns a non-daemon worker thread that otherwise
     * keeps the JVM alive past daemon shutdown.
     */
    fun disconnect() {
        if (!running.compareAndSet(true, false)) return
        val socket = socketRef.getAndSet(null) ?: return
        try {
            socket.disconnect()
            socket.off()
        } catch (e: Exception) {
            log.debug("disconnect() raised (ignored)", e)
        }
    }

    private fun buildSocket(token: String): Socket {
        val options =
            IO.Options.builder()
                .setTransports(arrayOf("websocket"))
                .setAuth(mapOf("token" to token))
                .setReconnection(true)
                .setReconnectionDelay(RECONNECT_INITIAL_DELAY_MS)
                .setReconnectionDelayMax(RECONNECT_MAX_DELAY_MS)
                .setReconnectionAttempts(Integer.MAX_VALUE)
                .build()

        val socket = IO.socket(URI.create(notificationsUrl), options)

        socket.on(Socket.EVENT_CONNECT) {
            // Every (re)connect fires one wake hint — events that arrived
            // while we were disconnected are gone from the server's
            // perspective, so the next delta walk has to catch up.
            log.info("Internxt notifications WS connected")
            safeInvokeCallback()
        }

        socket.on(Socket.EVENT_DISCONNECT) { args ->
            log.debug("Internxt notifications WS disconnected: {}", args.firstOrNull())
        }

        socket.on(Socket.EVENT_CONNECT_ERROR) { args ->
            val message = args.firstOrNull()?.toString().orEmpty()
            // Treat any connect_error as a potential auth-expiry signal.
            // The audit calls out this gap explicitly — we can't reliably
            // distinguish "JWT expired" from "DNS flake" from the message,
            // so we conservatively try a forced refresh + reconnect once
            // per refresh-in-flight window. If the refresh itself fails,
            // the catch in tryRefreshAndReconnect logs once and lets
            // socket.io continue its own reconnect loop at DEBUG.
            log.debug("Internxt notifications WS connect_error: {}", message)
            tryRefreshAndReconnect()
        }

        socket.on("event") { args ->
            val raw = args.firstOrNull()
            handleIncomingFrame(raw)
        }
        return socket
    }

    /**
     * Examine the incoming socket.io frame's `clientId`. Three outcomes:
     *
     * 1. Parseable JSON object with `clientId == ownClientName`: drop
     *    silently — this is our own mutation looping back.
     * 2. Parseable JSON object with any other `clientId`: emit a wake hint.
     * 3. Anything else (null, non-object, missing `clientId`, parse error):
     *    treat as a remote event and emit a wake hint — false positives
     *    just trigger a no-op delta walk, mirroring drive-desktop's
     *    "anything unrecognised triggers re-sync" policy.
     */
    internal fun handleIncomingFrame(raw: Any?) {
        val clientId = extractClientId(raw)
        if (clientId != null && clientId == ownClientName) {
            log.debug("Dropping loopback notification (clientId={})", clientId)
            return
        }
        safeInvokeCallback()
    }

    private fun safeInvokeCallback() {
        try {
            onRemoteChange()
        } catch (e: Exception) {
            // The callback is engine-supplied; a misbehaving callback must
            // not kill the socket.io worker thread.
            log.warn("Remote-change callback threw", e)
        }
    }

    /**
     * On connect_error, refresh the JWT and rebuild the socket. Coalesces
     * concurrent refresh attempts — socket.io fires connect_error per
     * reconnect attempt, but we only need one refresh per failure cluster.
     */
    private fun tryRefreshAndReconnect() {
        if (!refreshInProgress.compareAndSet(false, true)) return
        // socket.io invokes listeners on its own thread; the token supplier
        // is suspend, so we hop onto a short-lived Java thread that bridges
        // to runBlocking. This is the simplest dependency-free option and
        // the work is one HTTP round-trip per cluster, not a hot path.
        Thread {
            try {
                if (!running.get()) return@Thread
                val freshToken =
                    kotlinx.coroutines.runBlocking {
                        tokenSupplier(true)
                    }
                val old = socketRef.getAndSet(null)
                try {
                    old?.disconnect()
                    old?.off()
                } catch (_: Exception) { /* best-effort */ }
                val rebuilt = buildSocket(freshToken)
                socketRef.set(rebuilt)
                rebuilt.connect()
                log.info("Internxt notifications WS reconnected after token refresh")
            } catch (e: Exception) {
                // Refresh failed — log once, stay disconnected, let the
                // periodic poll do its job. Don't crash the daemon.
                log.warn(
                    "Internxt notifications WS token refresh failed; staying disconnected",
                    e,
                )
            } finally {
                refreshInProgress.set(false)
            }
        }.apply {
            isDaemon = true
            name = "unidrive-internxt-notifications-refresh"
            start()
        }
    }

    companion object {
        private const val RECONNECT_INITIAL_DELAY_MS = 1_000L
        private const val RECONNECT_MAX_DELAY_MS = 60_000L

        /**
         * Pull the `clientId` field out of a socket.io frame payload.
         * Frames arrive as `org.json.JSONObject` (the socket.io-client
         * library's wire type). We avoid hard-binding to JSONObject in
         * the signature so unit tests can feed in `Map<String,Any?>` or
         * arbitrary stand-ins without pulling org.json into the test
         * surface.
         */
        internal fun extractClientId(raw: Any?): String? {
            if (raw == null) return null
            // org.json.JSONObject path (the production wire shape).
            if (raw is org.json.JSONObject) {
                return if (raw.has("clientId") && !raw.isNull("clientId")) {
                    raw.optString("clientId").takeIf { it.isNotEmpty() }
                } else {
                    null
                }
            }
            // Map fallback for tests + future-proofing if socket.io's
            // payload type ever changes.
            if (raw is Map<*, *>) {
                val v = raw["clientId"] ?: return null
                return v.toString().takeIf { it.isNotEmpty() }
            }
            return null
        }
    }
}
