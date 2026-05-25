package org.krost.unidrive.cli

import org.krost.unidrive.sync.IpcServer
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.ParentCommand
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files

/**
 * `unidrive refresh` — thin client of the daemon's `refresh.run` IPC verb.
 *
 * Per spec docs/dev/specs/unidrive-daemon-design.md §3.3 + NG8: the pre-spec
 * standalone-JVM refresh path is removed. The daemon (`unidrive daemon run`)
 * must be running; this command connects to its socket, issues sync.subscribe,
 * then refresh.run, then streams progress events until the refresh.done
 * terminal event.
 *
 * Migration for cron jobs that called `unidrive refresh` standalone:
 *   unidrive daemon run <profile> & sleep 2 && unidrive refresh <profile> && unidrive daemon stop <profile>
 * Or move to a systemd-user-unit when phase-3 (BACKLOG follow-up) ships.
 */
@Command(
    name = "refresh",
    description = ["Update state.db with remote changes via the running daemon's refresh.run RPC."],
    mixinStandardHelpOptions = true,
)
class RefreshCommand : Runnable {
    @ParentCommand
    lateinit var parent: Main

    @Option(
        names = ["--reset"],
        description = [
            "Clear state.db on the daemon and re-enumerate the full cloud tree. " +
                "Recovers from poisoned delta cursors (e.g. when the cloud was modified " +
                "while sync was offline and the cursor returned only the recent delta).",
        ],
    )
    var reset: Boolean = false

    override fun run() {
        val profile = parent.resolveCurrentProfile()
        val socketPath = IpcServer.defaultSocketPath(profile.name)

        if (!Files.exists(socketPath)) {
            System.err.println(
                "unidrive refresh: daemon for profile '${profile.name}' is not running.",
            )
            System.err.println(
                "Start it first: `unidrive daemon run ${profile.name}` (in another terminal).",
            )
            System.exit(1)
            return
        }

        try {
            SocketChannel.open(UnixDomainSocketAddress.of(socketPath)).use { channel ->
                // Subscribe to progress events
                channel.write(ByteBuffer.wrap(("""{"verb":"sync.subscribe"}""" + "\n").toByteArray()))
                readOneJsonReply(channel)  // discard sync.subscribe reply

                // Issue refresh.run (with optional reset flag — F9 fix).
                val refreshReq = if (reset) {
                    """{"verb":"refresh.run","reset":true}"""
                } else {
                    """{"verb":"refresh.run"}"""
                }
                channel.write(ByteBuffer.wrap((refreshReq + "\n").toByteArray()))
                val runReply = readOneJsonReply(channel)
                if (!runReply.contains("\"ok\":true")) {
                    System.err.println("unidrive refresh: daemon rejected refresh.run: $runReply")
                    System.exit(1)
                    return
                }

                // Stream events until refresh.done
                val collected = StringBuilder()
                while (!collected.contains("\"event\":\"refresh.done\"")) {
                    val buf = ByteBuffer.allocate(4096)
                    val n = channel.read(buf)
                    if (n <= 0) {
                        // EOF — daemon shut down per spec F9
                        System.err.println("unidrive refresh: daemon shut down before refresh completed")
                        System.exit(1)
                        return
                    }
                    buf.flip()
                    val chunk = String(buf.array(), 0, buf.limit())
                    collected.append(chunk)
                    // Print progress events as they arrive (one per line)
                    chunk.lineSequence().filter { it.isNotBlank() }.forEach { println(it) }
                }

                // Check terminal event for failure
                if (collected.contains("\"event\":\"refresh.done\"") && !collected.contains("\"ok\":true")) {
                    System.exit(1)
                }
            }
        } catch (e: java.io.IOException) {
            System.err.println("unidrive refresh: failed to communicate with daemon: ${e.message}")
            System.err.println("Daemon may have crashed. Restart with: `unidrive daemon run ${profile.name}`.")
            System.exit(1)
        }
    }

    /** Read a single JSON reply terminated by newline. Returns the line without the newline. */
    private fun readOneJsonReply(channel: SocketChannel): String {
        val collected = StringBuilder()
        while (!collected.contains('\n')) {
            val buf = ByteBuffer.allocate(1024)
            val n = channel.read(buf)
            if (n <= 0) return collected.toString()
            buf.flip()
            collected.append(String(buf.array(), 0, buf.limit()))
        }
        return collected.toString().substringBefore('\n')
    }
}
