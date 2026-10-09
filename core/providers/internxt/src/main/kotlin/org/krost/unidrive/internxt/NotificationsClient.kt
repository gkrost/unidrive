package org.krost.unidrive.internxt

import io.socket.client.Socket
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
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
 * ## Which `connect_error` is an auth problem
 *
 * Only an error that looks like the server rejecting the token — an HTTP
 * 401/403 on the websocket upgrade (visible in the [Throwable] cause chain of
 * the `EngineIOException`) or an authentication message in the namespace-level
 * `connect_error` payload — triggers a forced token refresh and one socket
 * rebuild. Transport errors (timeout, connection refused, DNS, "websocket
 * error" without a 401/403 behind it) do NOT: rebuilding the socket restarts
 * socket.io's exponential backoff from 1 s, so treating every error as an
 * auth expiry used to turn an unreachable endpoint into a socket rebuild every
 * connect timeout (~10 s), forever, and hid the backoff that socket.io would
 * otherwise run (1 s doubling to 60 s).
 *
 * Forced refreshes are rate limited: at most one per window, 60 s at first,
 * doubling (cap 15 min) while a refresh fails or fails to cure the rejection,
 * back to 60 s after a successful connect. If the refresh fails the old socket
 * is kept; a socket that socket.io has already destroyed (namespace-level
 * rejection) is retried on a timer, since it would otherwise never report
 * again. The periodic poll is the safety net throughout.
 *
 * ## Which `connect_error` is permanent
 *
 * An endpoint that cannot be the notifications server stops the client for the
 * rest of the process: the TLS identity fails (hostname not verified, untrusted
 * or invalid certificate — e.g. 2026-10-09 `notifications.internxt.com` served a
 * certificate for `*.coolabor.de`), or the websocket upgrade is answered with an
 * HTTP status that is not an upgrade, an auth rejection or a temporary error
 * (2xx, 3xx, 404, 410). Retrying those every ≤ 60 s for days only repeats the
 * handshake against the wrong server; the poll is the safety net. One WARN says
 * so; restarting the process tries again. Transport errors (offline at boot,
 * timeout, refused, 5xx) are not permanent and keep socket.io's backoff.
 *
 * ## Logging
 *
 * One WARN per outage (the first `connect_error`, with the first error and its
 * causes), DEBUG for the rest; one INFO when the connection comes back. A
 * failed refresh logs one WARN per outage with the HTTP status only, no stack
 * trace unless DEBUG is on.
 */
internal class NotificationsClient(
    private val notificationsUrl: String,
    private val ownClientName: String,
    private val tokenSupplier: suspend (forceRefresh: Boolean) -> String,
    private val onRemoteChange: () -> Unit,
    private val socketFactory: NotificationsSocketFactory = SocketIoNotificationsSocketFactory,
    private val clock: () -> Long = System::currentTimeMillis,
    private val scheduler: NotificationsScheduler = ThreadNotificationsScheduler(),
) {
    private val log = LoggerFactory.getLogger(NotificationsClient::class.java)
    private val running = AtomicBoolean(false)
    private val socketRef = AtomicReference<NotificationsSocket?>(null)

    // Outage and refresh bookkeeping, all guarded by [lock]. socket.io calls the
    // listeners on its own event thread; refreshes run on [scheduler]'s thread.
    private val lock = Any()
    private var failedAttempts = 0
    private var outageStartedMs = 0L
    private var outageWarned = false
    private var refreshFailureWarned = false
    private var refreshInProgress = false
    private var refreshedSinceConnect = false
    private var retryScheduled = false
    private var refreshIntervalMs = REFRESH_MIN_INTERVAL_MS
    private var nextRefreshAtMs = 0L

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
        scheduler.shutdown()
        socketRef.getAndSet(null)?.close()
    }

    private fun buildSocket(token: String): NotificationsSocket {
        val socket = socketFactory.create(notificationsUrl, token)

        socket.on(Socket.EVENT_CONNECT) { onConnected(socket) }

        socket.on(Socket.EVENT_DISCONNECT) { args ->
            log.debug("Internxt notifications WS disconnected: {}", args.firstOrNull())
        }

        socket.on(Socket.EVENT_CONNECT_ERROR) { args -> onConnectError(socket, args.firstOrNull()) }

        socket.on("event") { args ->
            val raw = args.firstOrNull()
            handleIncomingFrame(raw)
        }
        return socket
    }

    private fun onConnected(socket: NotificationsSocket) {
        if (socketRef.get() !== socket) return // superseded by a rebuild
        val recovery =
            synchronized(lock) {
                val summary =
                    if (failedAttempts > 0) {
                        "recovered after $failedAttempts failed attempts over ${(clock() - outageStartedMs) / 1000} s"
                    } else {
                        null
                    }
                failedAttempts = 0
                outageWarned = false
                refreshFailureWarned = false
                refreshedSinceConnect = false
                refreshIntervalMs = REFRESH_MIN_INTERVAL_MS
                nextRefreshAtMs = 0L
                summary
            }
        // Every (re)connect fires one wake hint — events that arrived
        // while we were disconnected are gone from the server's
        // perspective, so the next delta walk has to catch up.
        if (recovery != null) {
            log.info("Internxt notifications WS connected ({})", recovery)
        } else {
            log.info("Internxt notifications WS connected")
        }
        safeInvokeCallback()
    }

    private fun onConnectError(
        socket: NotificationsSocket,
        error: Any?,
    ) {
        if (!running.get() || socketRef.get() !== socket) return // disconnected, or superseded by a rebuild
        val description = describe(error)
        var attempt = 0
        var firstOfOutage = false
        synchronized(lock) {
            if (failedAttempts == 0) outageStartedMs = clock()
            attempt = ++failedAttempts
            firstOfOutage = !outageWarned
            outageWarned = true
        }
        val permanent = if (looksLikeAuthRejection(error)) null else permanentFailure(error)
        if (permanent != null) {
            log.warn(
                "Internxt notifications endpoint {} is not usable ({}); notifications are off for this process " +
                    "and sync polls only. Restart the process to try again. {}",
                notificationsUrl,
                permanent,
                description,
            )
            disconnect()
            return
        }
        if (firstOfOutage) {
            log.warn(
                "Internxt notifications unreachable, polling only; reconnecting in the background with backoff up to {} s: {}",
                SocketIoNotificationsSocketFactory.RECONNECT_MAX_DELAY_MS / 1000,
                description,
            )
        } else {
            log.debug("Internxt notifications WS connect_error (attempt {}): {}", attempt, description)
        }
        if (looksLikeAuthRejection(error)) {
            // A namespace-level rejection arrives as a packet payload, not an exception;
            // socket.io destroys the socket on it and never retries by itself.
            requestRefresh(socket, socketIsDead = error !is Throwable)
        }
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
     * Ask for a forced token refresh after an auth-looking rejection, unless
     * one is running or the rate limit says it is too early. Coalesces the
     * burst of `connect_error`s socket.io fires while it retries.
     */
    private fun requestRefresh(
        socket: NotificationsSocket,
        socketIsDead: Boolean,
    ) {
        var allowed = false
        var waitMs = 0L
        synchronized(lock) {
            val now = clock()
            when {
                refreshInProgress -> {}
                now < nextRefreshAtMs -> {
                    waitMs = nextRefreshAtMs - now
                    if (socketIsDead) scheduleRetryLocked(socket)
                }
                else -> {
                    // The previous refresh worked but the server rejected us again:
                    // asking more often will not help.
                    if (refreshedSinceConnect) {
                        refreshIntervalMs = minOf(refreshIntervalMs * 2, REFRESH_MAX_INTERVAL_MS)
                    }
                    refreshInProgress = true
                    nextRefreshAtMs = now + refreshIntervalMs
                    allowed = true
                }
            }
        }
        if (!allowed) {
            log.debug("Internxt notifications auth rejection; forced token refresh not due for {} s", waitMs / 1000)
            return
        }
        // socket.io invokes listeners on its own thread; the token supplier is
        // suspend and does an HTTP round-trip, so it runs on the scheduler's
        // thread, bridged with runBlocking. At most one per window, not a hot path.
        scheduler.execute { refreshAndRebuild(socket, socketIsDead) }
    }

    /** Re-run [requestRefresh] once the window opens; needed for a destroyed socket, which stays silent. Caller holds [lock]. */
    private fun scheduleRetryLocked(socket: NotificationsSocket) {
        if (retryScheduled) return
        retryScheduled = true
        scheduler.schedule((nextRefreshAtMs - clock()).coerceAtLeast(0L)) {
            synchronized(lock) { retryScheduled = false }
            if (running.get() && socketRef.get() === socket) requestRefresh(socket, socketIsDead = true)
        }
    }

    private fun refreshAndRebuild(
        stale: NotificationsSocket,
        socketIsDead: Boolean,
    ) {
        try {
            if (!running.get()) return
            val freshToken = runBlocking { tokenSupplier(true) }
            if (!running.get() || socketRef.get() !== stale) return // disconnected or superseded meanwhile
            val rebuilt = buildSocket(freshToken)
            socketRef.set(rebuilt)
            stale.close()
            rebuilt.connect()
            synchronized(lock) { refreshedSinceConnect = true }
            log.info("Internxt notifications WS rebuilt with a refreshed token after an authentication rejection")
        } catch (e: Exception) {
            if (!running.get()) {
                // disconnect() interrupted us; not a refresh failure worth a WARN.
                log.debug("Internxt notifications refresh abandoned by disconnect()", e)
                return
            }
            var intervalMs = 0L
            var firstFailure = false
            synchronized(lock) {
                refreshIntervalMs = minOf(refreshIntervalMs * 2, REFRESH_MAX_INTERVAL_MS)
                intervalMs = refreshIntervalMs
                nextRefreshAtMs = clock() + refreshIntervalMs
                refreshedSinceConnect = false
                firstFailure = !refreshFailureWarned
                refreshFailureWarned = true
                if (socketIsDead) scheduleRetryLocked(stale)
            }
            // The old socket is kept (socket.io keeps retrying it; a destroyed one is
            // retried by the timer above), the periodic poll covers sync. Status only
            // at WARN, the stack trace at DEBUG.
            if (firstFailure) {
                log.warn(
                    "Internxt notifications token refresh failed ({}); next attempt in {} s, polling only meanwhile",
                    statusOnly(e),
                    intervalMs / 1000,
                )
            }
            log.debug("Internxt notifications token refresh failure detail", e)
        } finally {
            synchronized(lock) { refreshInProgress = false }
        }
    }

    companion object {
        /** First wait between forced refreshes; also the value after a successful connect. */
        internal const val REFRESH_MIN_INTERVAL_MS = 60_000L

        /** Ceiling of the doubling wait between forced refreshes. */
        internal const val REFRESH_MAX_INTERVAL_MS = 15L * 60_000L

        // What an authentication rejection looks like. OkHttp reports a refused websocket
        // upgrade as "Expected HTTP 101 response but was '401 Unauthorized'"; a socket.io
        // middleware rejection arrives as {"message": "..."}. The 401/403 must not be part
        // of a host:port, an address or a longer number.
        private val AUTH_REJECTION =
            Regex(
                "(?i)(?<![\\d.:/-])(401|403)(?!\\d|[.:]\\d)" +
                    "|unauthori[sz]ed|forbidden|authentication (error|failed)" +
                    "|invalid[ _-]?(token|jwt)|(token|jwt)\\W+(is\\W+)?(expired|invalid)|expired\\W+(token|jwt)",
            )

        private val HTTP_STATUS = Regex("\\(HTTP \\d{3}\\)")

        private const val MAX_CAUSE_DEPTH = 8

        /**
         * Does a `connect_error` payload look like the server rejecting our token?
         * Looks through the whole cause chain of a [Throwable] (the HTTP status of
         * a refused upgrade sits in the OkHttp exception below the EngineIOException)
         * and at the text of any other payload.
         */
        internal fun looksLikeAuthRejection(error: Any?): Boolean {
            if (error == null) return false
            if (error !is Throwable) return AUTH_REJECTION.containsMatchIn(error.toString())
            var t: Throwable? = error
            var depth = 0
            while (t != null && depth < MAX_CAUSE_DEPTH) {
                if (t.message?.let { AUTH_REJECTION.containsMatchIn(it) } == true) return true
                t = t.cause?.takeIf { it !== t }
                depth++
            }
            return false
        }

        // OkHttp reports a refused websocket upgrade as "Expected HTTP 101 response but was '404 Not Found'".
        private val UPGRADE_STATUS = Regex("Expected HTTP 101 response but was '(\\d{3})")

        /**
         * Why a `connect_error` means this endpoint can never be the notifications server, or null
         * when it may be temporary (see "Which `connect_error` is permanent"). Auth rejections are
         * classified by [looksLikeAuthRejection] first and never reach this.
         */
        internal fun permanentFailure(error: Any?): String? {
            if (error !is Throwable) return null
            var t: Throwable? = error
            var depth = 0
            while (t != null && depth < MAX_CAUSE_DEPTH) {
                when {
                    t is javax.net.ssl.SSLPeerUnverifiedException ->
                        return "TLS: the server's certificate is not valid for this host name"
                    t is java.security.cert.CertificateException || t is java.security.cert.CertPathValidatorException ||
                        t.javaClass.name == "sun.security.validator.ValidatorException" ->
                        return "TLS: the server's certificate is not trusted"
                }
                val status = t.message?.let { UPGRADE_STATUS.find(it) }?.groupValues?.get(1)?.toInt()
                if (status != null && (status in 200..399 || status == 404 || status == 410)) {
                    return "the websocket upgrade was answered with HTTP $status"
                }
                t = t.cause?.takeIf { it !== t }
                depth++
            }
            return null
        }

        /** One line for the log: the exception chain, so "websocket error" comes with its real cause. */
        internal fun describe(error: Any?): String {
            if (error !is Throwable) return error?.toString().orEmpty().take(300)
            val parts = mutableListOf<String>()
            var t: Throwable? = error
            while (t != null && parts.size < 4) {
                val name = t.javaClass.simpleName
                parts += t.message?.let { "$name: $it" } ?: name
                t = t.cause?.takeIf { it !== t }
            }
            return parts.joinToString(" <- ").take(400)
        }

        /** Exception class and, when the message carries one, only the HTTP status part of it (never the body). */
        private fun statusOnly(e: Throwable): String {
            val message = e.message.orEmpty()
            val status = HTTP_STATUS.find(message)
            val text = if (status != null) message.substring(0, status.range.last + 1) else message.take(120)
            return "${e.javaClass.simpleName}: $text"
        }

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
