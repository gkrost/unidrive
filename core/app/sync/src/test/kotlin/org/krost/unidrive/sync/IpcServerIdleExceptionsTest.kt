package org.krost.unidrive.sync

import jdk.jfr.Recording
import jdk.jfr.consumer.RecordingFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An idle connection wakes its reader every second to re-run the idle-timeout check. That used to be a coroutine
 * timeout, which ends in a TimeoutCancellationException (and the stack trace built for it) each time: a recording of
 * a daemon with nothing to do counted 8,198 of them in 90 minutes. The recording here counts them for an idle
 * connection over two rechecks.
 */
class IpcServerIdleExceptionsTest {
    private fun timeoutExceptionsWhileIdle(): Int {
        val dir = Files.createTempDirectory("unidrive-ipc-idle-exceptions")
        val socketPath = dir.resolve("idle.sock")
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val server = IpcServer(socketPath, idleTimeoutMs = 3_600_000)
        val jfr = dir.resolve("idle.jfr")
        var client: SocketChannel? = null
        try {
            server.start(scope)
            client = SocketChannel.open(StandardProtocolFamily.UNIX).also { it.connect(UnixDomainSocketAddress.of(socketPath)) }
            Recording().use { recording ->
                recording.enable("jdk.JavaExceptionThrow")
                recording.start()
                // Two rechecks of the idle timeout (every second), and the margin of a slow runner.
                runBlocking { delay(2_500) }
                recording.stop()
                recording.dump(jfr)
            }
            return RecordingFile.readAllEvents(jfr).count { event ->
                event.eventType.name == "jdk.JavaExceptionThrow" && event.getClass("thrownClass").name.endsWith("TimeoutCancellationException")
            }
        } finally {
            runCatching { client?.close() }
            scope.cancel()
            server.close()
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `an idle connection rechecks its timeout without building a timeout exception`() {
        assertEquals(0, timeoutExceptionsWhileIdle())
    }
}
