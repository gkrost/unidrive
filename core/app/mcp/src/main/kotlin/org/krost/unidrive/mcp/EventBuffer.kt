package org.krost.unidrive.mcp

import kotlinx.coroutines.*
import org.krost.unidrive.sync.IpcServer
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ConnectException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class EventBuffer(
    private val profileName: String,
    private val capacity: Int = 500,
) {
    private val log = LoggerFactory.getLogger(EventBuffer::class.java)
    private val lock = ReentrantLock()
    private val buffer = ArrayDeque<Pair<Long, String>>(capacity) // (seq, json line)
    private var seq = 0L

    @Volatile var connected = false
        private set
    private var overflowCount = 0

    private var scope: CoroutineScope? = null

    fun start() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        scope!!.launch { readLoop() }
    }

    fun close() {
        scope?.cancel()
    }

    fun drain(
        sinceSeq: Long,
        limit: Int = 50,
    ): Pair<List<String>, Long> =
        lock.withLock {
            val events =
                buffer
                    .filter { it.first > sinceSeq }
                    .takeLast(limit)
                    .map { it.second }
            val highWater = buffer.lastOrNull()?.first ?: sinceSeq
            events to highWater
        }

    private suspend fun readLoop() {
        while (currentCoroutineContext().isActive) {
            val socketPath = IpcServer.defaultSocketPath(profileName)
            if (!Files.exists(socketPath)) {
                connected = false
                delay(5000)
                continue
            }
            try {
                SocketChannel.open(StandardProtocolFamily.UNIX).use { ch ->
                    ch.connect(UnixDomainSocketAddress.of(socketPath))
                    connected = true
                    val reader = BufferedReader(InputStreamReader(Channels.newInputStream(ch), Charsets.UTF_8))
                    while (currentCoroutineContext().isActive) {
                        val line = reader.readLine() ?: break // EOF = daemon closed
                        if (line.isBlank()) continue
                        lock.withLock {
                            seq++
                            buffer.addLast(seq to line)
                            while (buffer.size > capacity) {
                                buffer.removeFirst()
                                overflowCount++
                                if (overflowCount % 10 == 1) {
                                    log.warn(
                                        "Event buffer overflow (capacity $capacity), oldest events discarded (total discards: $overflowCount)",
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (_: ConnectException) {
                // Daemon not running
            } catch (_: java.io.IOException) {
                // Socket closed / daemon restarted
            }
            connected = false
            delay(5000)
        }
    }
}
