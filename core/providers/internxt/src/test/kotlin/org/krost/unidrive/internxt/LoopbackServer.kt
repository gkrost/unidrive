package org.krost.unidrive.internxt

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.plugin
import io.ktor.http.URLProtocol
import org.krost.unidrive.http.HttpRetryBudget
import org.krost.unidrive.internxt.model.InternxtCredentials
import org.slf4j.LoggerFactory
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

// The address of the servers and of the rewritten URLs. Not getLoopbackAddress(), which is ::1 where IPv6 is preferred.
private val loopback: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

/**
 * A throwaway HTTP/1.1 server on 127.0.0.1, plain or TLS, for the tests that need the real client engine and its real
 * timers: a MockEngine never waits, so it cannot tell a socket timeout from a server that closed the connection.
 *
 * Every accepted connection runs [handler] on a thread of its own and is closed when the handler returns, so a
 * response ends the connection (it says `Connection: close`) and one request is one [connections]. The handler decides
 * how the server behaves: stay silent, answer late, close at once, drain a body slowly.
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

        class Head(
            val requestLine: String,
            val contentLength: Long,
        )

        /** Reads the request line and the headers (this is also what runs the TLS handshake). */
        fun readHead(): Head {
            val text = StringBuilder()
            while (!text.endsWith("\r\n\r\n")) {
                val b = input.read()
                if (b < 0) throw EOFException("the client closed the connection before its request head ended")
                text.append(b.toChar())
            }
            val lines = text.lines()
            val length =
                lines
                    .firstNotNullOfOrNull { Regex("(?i)^content-length:\\s*(\\d+)").find(it)?.groupValues?.get(1)?.toLong() }
                    ?: 0L
            return Head(lines.first(), length)
        }

        /** Reads [length] body bytes, at [bytesPerSecond] when given. Returns the bytes read. */
        fun drain(
            length: Long,
            bytesPerSecond: Long? = null,
        ): Long {
            val buf = ByteArray(16 * 1024)
            var got = 0L
            while (got < length) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), length - got).toInt())
                if (n < 0) break
                got += n
                if (bytesPerSecond != null) Thread.sleep(maxOf(1L, n * 1000L / bytesPerSecond))
            }
            return got
        }

        fun respond(
            status: Int,
            reason: String,
            body: String,
            headers: Map<String, String> = emptyMap(),
        ) {
            val bytes = body.toByteArray()
            val head =
                buildString {
                    append("HTTP/1.1 $status $reason\r\n")
                    append("Content-Type: application/json\r\n")
                    headers.forEach { (name, value) -> append("$name: $value\r\n") }
                    append("Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
                }
            val out = socket.getOutputStream()
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            out.write(bytes)
            out.flush()
        }

        /** Stays silent until the client closes the connection (its timer fired), or for [maxMs]. */
        fun awaitClientClose(maxMs: Long = 20_000) {
            socket.soTimeout = maxMs.toInt()
            try {
                while (input.read() >= 0) {
                    // Nothing more is expected from the client.
                }
            } catch (_: Exception) {
                // Reset, or the wait ran out.
            }
        }
    }
}

/**
 * A real client on the engine production uses (CIO, with HttpTimeout), whose default socket timeout is [socketTimeoutMs] and
 * whose requests all go to [server], whatever host they were built on: the service under test builds its URLs on the
 * gateway's host, and this is how they reach a loopback server without a seam in the service.
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
        client.plugin(HttpSend).intercept { request ->
            request.url.protocol = if (server.tls) URLProtocol.HTTPS else URLProtocol.HTTP
            request.url.host = "127.0.0.1"
            request.url.port = server.port
            execute(request)
        }
    }

/** The WARN lines [InternxtApiService] logs while this is open. */
internal class ServiceWarnings : AutoCloseable {
    private val logger = LoggerFactory.getLogger(InternxtApiService::class.java) as ch.qos.logback.classic.Logger
    private val appender = ListAppender<ILoggingEvent>().also { it.start() }

    init {
        logger.addAppender(appender)
    }

    val messages: List<String> get() = appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }

    override fun close() {
        logger.detachAppender(appender)
    }
}

/** The service on [client], with a socket timeout of a few hundred milliseconds instead of the production minute. */
internal fun loopbackService(
    client: HttpClient,
    socketMs: Long,
): InternxtApiService =
    InternxtApiService(
        InternxtConfig(),
        credentialsProvider = { _ ->
            InternxtCredentials(jwt = "test-jwt", mnemonic = "test-mnemonic", rootFolderId = "test-root", email = "test@example.invalid")
        },
        driveBudget = HttpRetryBudget(maxConcurrency = 2, minSpacingMs = 0, stormSpacingMs = 0),
        bridgeBudget = HttpRetryBudget(maxConcurrency = 4, minSpacingMs = 0, stormSpacingMs = 0),
        socketTimeoutMs = socketMs,
        httpClient = client,
    )

/**
 * The TLS side of the loopback tests. The certificate is generated here, once per JVM, and never leaves it: no keystore is
 * committed. The client trusts every certificate; the server's is for 127.0.0.1 only because the client checks the name.
 */
internal object LoopbackTls {
    private val password = "changeit".toCharArray()

    val serverSocketFactory: SSLServerSocketFactory by lazy {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val store =
            KeyStore.getInstance("PKCS12").apply {
                load(null, null)
                setKeyEntry("loopback", keyPair.private, password, arrayOf(selfSigned(keyPair)))
            }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
        SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }.serverSocketFactory
    }

    val trustAll: X509TrustManager =
        object : X509TrustManager {
            override fun checkClientTrusted(
                chain: Array<X509Certificate>?,
                authType: String?,
            ) {}

            override fun checkServerTrusted(
                chain: Array<X509Certificate>?,
                authType: String?,
            ) {}

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

    // A self-signed X.509 v3 certificate for 127.0.0.1, valid for a day around now, written as DER by hand: the JDK can parse a
    // certificate but only its internal classes can build one, and a test is no reason to open them.
    private fun selfSigned(keyPair: KeyPair): X509Certificate {
        val signatureAlgorithm = der(0x30, der(0x06, 0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x01, 0x0B), byteArrayOf(0x05, 0x00)) // sha256WithRSA
        val name = der(0x30, der(0x31, der(0x30, der(0x06, 0x55, 0x04, 0x03), der(0x0C, "localhost".toByteArray())))) // CN=localhost
        val utc = DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'").withZone(ZoneOffset.UTC)
        val now = Instant.now()
        val validity =
            der(
                0x30,
                der(0x17, utc.format(now - Duration.ofDays(1)).toByteArray()),
                der(0x17, utc.format(now + Duration.ofDays(1)).toByteArray()),
            )
        val altNames = der(0x30, der(0x87, 127, 0, 0, 1)) // iPAddress 127.0.0.1
        val extensions = der(0xA3, der(0x30, der(0x30, der(0x06, 0x55, 0x1D, 0x11), der(0x04, altNames)))) // subjectAltName
        val tbs =
            der(
                0x30,
                der(0xA0, der(0x02, 2)), // version 3
                der(0x02, BigInteger(64, SecureRandom()).add(BigInteger.ONE).toByteArray()),
                signatureAlgorithm,
                name,
                validity,
                name,
                keyPair.public.encoded,
                extensions,
            )
        val signature =
            Signature.getInstance("SHA256withRSA").run {
                initSign(keyPair.private)
                update(tbs)
                sign()
            }
        val certificate = der(0x30, tbs, signatureAlgorithm, der(0x03, byteArrayOf(0) + signature))
        return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(certificate)) as X509Certificate
    }

    private fun der(
        tag: Int,
        vararg content: Int,
    ): ByteArray = der(tag, ByteArray(content.size) { content[it].toByte() })

    private fun der(
        tag: Int,
        vararg parts: ByteArray,
    ): ByteArray {
        val body = parts.fold(ByteArray(0)) { all, part -> all + part }
        val length =
            when {
                body.size < 0x80 -> byteArrayOf(body.size.toByte())
                body.size < 0x100 -> byteArrayOf(0x81.toByte(), body.size.toByte())
                else -> byteArrayOf(0x82.toByte(), (body.size shr 8).toByte(), body.size.toByte())
            }
        return byteArrayOf(tag.toByte()) + length + body
    }
}
