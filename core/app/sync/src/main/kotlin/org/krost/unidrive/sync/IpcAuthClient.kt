package org.krost.unidrive.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/** Where a profile's daemon listens and where its IPC token files are (the profile's config folder). */
data class IpcEndpoint(
    val socketPath: Path,
    val tokenDir: Path,
    val profileName: String,
)

/** The IPC handshake failed or the daemon could not be authenticated; the connection is closed. */
class IpcAuthException(
    message: String,
) : IOException(message)

/**
 * The client side of the IPC handshake (docs/dev/specs/ipc-authentication.md) for the engine's own
 * commands (`daemon status`, `daemon stop`, `ls`, `refresh`).
 *
 * [connect] reads the token file of the requested scope afresh at every call (a restarted daemon has
 * new tokens), runs `hello` / `hello.proof`, checks the daemon's proof with a constant-time comparison
 * and returns the authenticated connection in blocking mode. It fails closed with
 * [IpcAuthException]: a refused handshake is terminal (no retry), a closed connection fails at once,
 * and a daemon that cannot show the proof is not talked to.
 *
 * Without a token file the daemon must be a protocol-1 daemon (no authentication): it is asked
 * `daemon.status`, and only when that says `protocol_version` 1 is the connection used
 * unauthenticated, with one warning per process. That fallback is temporary (a tracker issue removes
 * it with the next minimum engine version).
 */
object IpcAuthClient {
    const val CLI_CLIENT: String = "unidrive-cli"
    const val HANDSHAKE_TIMEOUT_MS: Long = 5_000

    private val log = LoggerFactory.getLogger(IpcAuthClient::class.java)
    private val protocol1Warned = AtomicBoolean(false)

    fun connect(
        endpoint: IpcEndpoint,
        scope: IpcAuth.Scope,
        client: String = CLI_CLIENT,
        timeoutMs: Long = HANDSHAKE_TIMEOUT_MS,
        // Test seam: the client nonce (the fixed test vectors use 00..0f).
        clientNonce: () -> ByteArray = { IpcAuth.randomBytes(IpcAuth.NONCE_BYTES) },
    ): SocketChannel {
        val tokenFile = IpcAuth.tokenFile(endpoint.tokenDir, scope)
        val key = readKeyOrNull(tokenFile)
        val channel = SocketChannel.open(UnixDomainSocketAddress.of(endpoint.socketPath))
        try {
            channel.configureBlocking(false)
            val deadline = System.currentTimeMillis() + timeoutMs
            if (key == null) {
                protocol1Fallback(channel, tokenFile, deadline)
            } else {
                handshake(channel, key, scope, endpoint.profileName, client, IpcAuth.encode(clientNonce()), tokenFile, deadline)
            }
            channel.configureBlocking(true)
            return channel
        } catch (e: Throwable) {
            runCatching { channel.close() }
            if (e is IOException && e !is IpcAuthException) throw IpcAuthException("the IPC handshake failed: ${e.message}")
            throw e
        }
    }

    /** The token, or null when the file does not exist; any other problem fails closed. */
    private fun readKeyOrNull(tokenFile: Path): ByteArray? =
        try {
            IpcAuth.readToken(tokenFile)
        } catch (_: NoSuchFileException) {
            if (Files.exists(tokenFile)) throw IpcAuthException("cannot read the IPC token file $tokenFile")
            null
        } catch (e: IOException) {
            throw IpcAuthException("cannot read the IPC token file $tokenFile: ${e.message}")
        }

    private fun handshake(
        channel: SocketChannel,
        key: ByteArray,
        scope: IpcAuth.Scope,
        profile: String,
        client: String,
        clientNonce: String,
        tokenFile: Path,
        deadline: Long,
    ) {
        val hello =
            """{"verb":"hello","protocol":${IpcAuth.PROTOCOL_VERSION},"scope":"${scope.wire}","client":${JsonPrimitive(client)},"nonce":"$clientNonce"}"""
        val step1 = request(channel, hello, deadline)
        val serverNonce = step1.string("snonce")
        if (step1.isOk() && step1.int("step") == 1 && serverNonce != null && IpcAuth.decode(serverNonce)?.size == IpcAuth.NONCE_BYTES) {
            val proof = IpcAuth.clientProof(key, scope.wire, profile, clientNonce, serverNonce)
            val step2 = request(channel, """{"verb":"hello.proof","proof":"$proof"}""", deadline)
            if (!step2.isOk()) throw refused(step2)
            val expected = IpcAuth.serverProof(key, scope.wire, profile, clientNonce, serverNonce, proof)
            val serverProof = step2.string("proof") ?: ""
            if (step2.string("scope") != scope.wire || !IpcAuth.constantTimeEquals(expected, serverProof)) {
                throw IpcAuthException(
                    "the daemon's proof does not match the IPC token; the socket may not belong to the unidrive daemon of this profile",
                )
            }
            return
        }
        if (step1.string("error") == "unknown_verb") {
            throw IpcAuthException(
                "the daemon does not support IPC authentication, but $tokenFile exists; restart the daemon",
            )
        }
        throw refused(step1)
    }

    private fun refused(reply: JsonObject): IpcAuthException {
        val error = reply.string("error")
        return if (error == "auth_failed") {
            IpcAuthException("the daemon rejected the IPC token (auth_failed); restart the daemon or the client that reads its token")
        } else {
            IpcAuthException("unexpected reply to the IPC handshake: ${error ?: "no error token"}")
        }
    }

    private fun protocol1Fallback(
        channel: SocketChannel,
        tokenFile: Path,
        deadline: Long,
    ) {
        val status = request(channel, """{"verb":"daemon.status"}""", deadline)
        val version = status.int("protocol_version") ?: 1
        if (version >= IpcAuth.PROTOCOL_VERSION) {
            throw IpcAuthException("cannot authenticate to this daemon (IPC protocol $version): no IPC token at $tokenFile")
        }
        if (protocol1Warned.compareAndSet(false, true)) {
            log.warn("IPC: no token at {} and the daemon speaks IPC protocol {}: connecting without authentication", tokenFile, version)
        }
    }

    // One request line out, one reply line back, within [deadline]. The daemon answers nothing else on a
    // connection before it is authenticated, so no bytes may follow the reply line.
    private fun request(
        channel: SocketChannel,
        line: String,
        deadline: Long,
    ): JsonObject {
        val out = ByteBuffer.wrap((line + "\n").toByteArray(Charsets.UTF_8))
        while (out.hasRemaining()) {
            if (channel.write(out) == 0) {
                if (System.currentTimeMillis() >= deadline) throw IpcAuthException("the daemon did not take the IPC handshake in time")
                Thread.sleep(5)
            }
        }
        val reply = ByteArrayOutputStream()
        val buf = ByteBuffer.allocate(1024)
        while (true) {
            buf.clear()
            val n = channel.read(buf)
            if (n < 0) throw IpcAuthException("the daemon closed the connection during the IPC handshake")
            if (n == 0) {
                if (System.currentTimeMillis() >= deadline) throw IpcAuthException("no reply to the IPC handshake in time")
                Thread.sleep(5)
                continue
            }
            buf.flip()
            val bytes = ByteArray(buf.remaining()).also { buf.get(it) }
            val newline = bytes.indexOf('\n'.code.toByte())
            if (newline < 0) {
                reply.write(bytes)
                if (reply.size() > MAX_REPLY_BYTES) throw IpcAuthException("the reply to the IPC handshake is too long")
                continue
            }
            if (newline != bytes.size - 1) throw IpcAuthException("unexpected data after the reply to the IPC handshake")
            reply.write(bytes, 0, newline)
            break
        }
        return runCatching { Json.parseToJsonElement(reply.toString(Charsets.UTF_8)) as? JsonObject }.getOrNull()
            ?: throw IpcAuthException("the reply to the IPC handshake is not a JSON object")
    }

    private const val MAX_REPLY_BYTES = 64 * 1024

    private fun JsonObject.isOk(): Boolean = (this["ok"] as? JsonPrimitive)?.content == "true"

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
}
