package org.krost.unidrive.internxt

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.plugin
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.URLProtocol
import kotlinx.coroutines.runBlocking
import org.krost.unidrive.http.HttpRetryBudget
import org.krost.unidrive.internxt.model.InternxtCredentials
import java.io.BufferedInputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

// The address of the servers and of the rewritten URLs. Not getLoopbackAddress(), which is ::1 where IPv6 is preferred.
private val loopback: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

/**
 * A throwaway HTTP/1.1 server on 127.0.0.1, plain or TLS, for the tests that need the real client engine and its real timers: a
 * MockEngine never waits, so it cannot tell a socket timeout from an answer that took long.
 *
 * Every accepted connection runs [handler] on a thread of its own and is closed when the handler returns, so a
 * response ends the connection (it says `Connection: close`) and one request is one [connections].
 */
internal class LoopbackServer(
    val tls: Boolean,
    private val handler: (Exchange) -> Unit,
) : AutoCloseable {
    private val server: ServerSocket =
        if (tls) {
            LoopbackTls.serverSocketFactory.createServerSocket(0, 50, loopback)
        } else {
            ServerSocket(0, 50, loopback)
        }
    private val accepted = AtomicInteger(0)

    val port: Int = server.localPort

    /** Connections accepted so far. */
    val connections: Int get() = accepted.get()

    init {
        thread(isDaemon = true, name = "loopback-accept-$port") {
            while (!server.isClosed) {
                val socket =
                    try {
                        server.accept()
                    } catch (_: Exception) {
                        break
                    }
                accepted.incrementAndGet()
                thread(isDaemon = true, name = "loopback-connection-$port") {
                    try {
                        handler(Exchange(socket))
                    } catch (_: Exception) {
                        // The client went away first (a timer fired on its side): nothing to report.
                    } finally {
                        runCatching { socket.close() }
                    }
                }
            }
        }
    }

    override fun close() {
        runCatching { server.close() }
    }

    /** The server's side of one connection. */
    class Exchange(
        private val socket: Socket,
    ) {
        private val input = BufferedInputStream(socket.getInputStream())

        /** Reads the request line and the headers (this is also what runs the TLS handshake). Returns the request line. */
        fun readRequestLine(): String {
            val text = StringBuilder()
            while (!text.endsWith("\r\n\r\n")) {
                val b = input.read()
                if (b < 0) throw EOFException("the client closed the connection before its request head ended")
                text.append(b.toChar())
            }
            return text.lines().first()
        }

        fun respond(
            status: Int,
            reason: String,
            body: String,
        ) {
            val bytes = body.toByteArray()
            val head =
                "HTTP/1.1 $status $reason\r\nContent-Type: application/json\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
            val out = socket.getOutputStream()
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            out.write(bytes)
            out.flush()
        }
    }
}

/**
 * A real client on the engine production uses (CIO, with HttpTimeout), whose default socket timeout is [socketTimeoutMs]
 * and whose requests all go to [server], whatever host they were built on: the service under test builds its URLs on the
 * gateway's host, and this is how they reach a loopback server.
 */
internal fun loopbackClient(
    server: LoopbackServer,
    socketTimeoutMs: Long,
): HttpClient =
    HttpClient(CIO) {
        engine { https { trustManager = LoopbackTls.trustAll } }
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = socketTimeoutMs
            requestTimeoutMillis = 600_000
        }
    }.also { client ->
        check(!server.tls || tlsWarmUp)
        client.plugin(HttpSend).intercept { request ->
            request.url.protocol = if (server.tls) URLProtocol.HTTPS else URLProtocol.HTTP
            request.url.host = "127.0.0.1"
            request.url.port = server.port
            execute(request)
        }
    }

/** The service on [client], with a listing socket timeout of [listingSocketMs] instead of the production 330 s. */
internal fun loopbackService(
    client: HttpClient,
    listingSocketMs: Long,
): InternxtApiService =
    InternxtApiService(
        InternxtConfig(),
        credentialsProvider = { _ ->
            InternxtCredentials(jwt = "test-jwt", mnemonic = "test-mnemonic", rootFolderId = "test-root", email = "test@example.invalid")
        },
        driveBudget = HttpRetryBudget(maxConcurrency = 2, minSpacingMs = 0, stormSpacingMs = 0),
        bridgeBudget = HttpRetryBudget(maxConcurrency = 4, minSpacingMs = 0, stormSpacingMs = 0),
        listingSocketTimeoutMs = listingSocketMs,
        httpClient = client,
    )

// The first TLS request of a JVM pays for class loading and key generation. Done once, before any test timer runs, so that
// a timer of half a second measures the server and not the cold start.
private val tlsWarmUp: Boolean by lazy {
    LoopbackServer(tls = true) { exchange ->
        exchange.readRequestLine()
        exchange.respond(200, "OK", "{}")
    }.use { server ->
        HttpClient(CIO) {
            engine { https { trustManager = LoopbackTls.trustAll } }
            install(HttpTimeout) {
                connectTimeoutMillis = 30_000
                socketTimeoutMillis = 30_000
            }
        }.use { client -> runBlocking { client.get("https://127.0.0.1:${server.port}/warm-up").bodyAsText() } }
    }
    true
}
