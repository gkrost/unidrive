package org.krost.unidrive.cli

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.nio.ByteBuffer
import java.nio.channels.ReadableByteChannel
import java.nio.file.Files
import java.nio.file.Path

/**
 * #625: the shared daemon-startup wait for every test that binds a daemon socket. A cold or busy
 * runner gets [DAEMON_START_BUDGET_MS], and when the caller has the daemon's [daemonJob] a start that ENDED without
 * binding fails at once instead of running out the clock (a real regression then names itself).
 * The poll exits the moment the socket exists — the budget is only a ceiling.
 */
internal suspend fun awaitDaemonSocket(
    socketPath: Path,
    daemonJob: Job? = null,
) {
    withTimeout(DAEMON_START_BUDGET_MS) {
        while (!Files.exists(socketPath)) {
            if (daemonJob?.isCompleted == true) {
                error("the daemon start finished without binding its socket")
            }
            delay(50)
        }
    }
}

/**
 * The ceiling for the daemon to bind its socket. A runner that runs several modules' tests at once has stalled a
 * daemon's start for more than 30 s (#906), so the ceiling is four times that; a healthy start pays nothing for it.
 */
internal const val DAEMON_START_BUDGET_MS = 120_000L

/**
 * The ceiling for one wait on a daemon's reply or pushed event. Like [DAEMON_START_BUDGET_MS] it is only
 * a ceiling: the poll returns the moment the line arrives, so a healthy run pays nothing for it, and a
 * loaded Windows runner (cold JIT, a busy disk) gets room instead of a flake. A wait on a reply was seen to
 * run 36 s past a 30 s budget (#906).
 */
internal const val IPC_STEP_BUDGET_MS = 120_000L

/**
 * Polls [channel] (non-blocking) until a complete line satisfies [accept], and returns that line. Lines
 * that arrive before it (replies to earlier requests, pushed events) are skipped; what follows it in the
 * same read is dropped, so call this once per expected line and send the next request after it returns.
 * A reply and the event its request triggers may arrive in either order: wait for both with [awaitIpcLines].
 */
internal suspend fun awaitIpcLine(
    channel: ReadableByteChannel,
    step: String,
    budgetMs: Long = IPC_STEP_BUDGET_MS,
    accept: (String) -> Boolean,
): String = awaitIpcLines(channel, step, budgetMs, accept).single()

/**
 * Polls [channel] (non-blocking) until every predicate in [wanted] has matched a line, and returns the
 * matched lines in the order of [wanted]; each line goes to the first unmatched predicate that accepts it,
 * whatever order the lines arrive in. When [budgetMs] runs out it fails with the [step] it was waiting for,
 * the time actually spent, which predicates were still open and every line seen, so a red run names the
 * step that stalled instead of a bare assertion. A closed peer fails at once for the same reason, rather
 * than running out the clock.
 */
internal suspend fun awaitIpcLines(
    channel: ReadableByteChannel,
    step: String,
    budgetMs: Long = IPC_STEP_BUDGET_MS,
    vararg wanted: (String) -> Boolean,
): List<String> {
    val matched = arrayOfNulls<String>(wanted.size)
    val pending = StringBuilder()
    val seen = mutableListOf<String>()
    val started = System.nanoTime()
    val buf = ByteBuffer.allocate(4096)

    fun elapsedMs() = (System.nanoTime() - started) / 1_000_000

    fun open() = matched.indices.filter { matched[it] == null }
    while (true) {
        while (true) {
            val eol = pending.indexOf("\n")
            if (eol < 0) break
            val line = pending.substring(0, eol).trimEnd('\r')
            pending.delete(0, eol + 1)
            if (line.isEmpty()) continue
            seen += line
            val slot = open().firstOrNull { wanted[it](line) }
            if (slot != null) matched[slot] = line
        }
        if (open().isEmpty()) return matched.map { it!! }
        check(elapsedMs() < budgetMs) {
            "step '$step' got no matching line within its $budgetMs ms budget (waited ${elapsedMs()} ms; " +
                "expectations still open: ${open()}); lines seen: $seen"
        }
        buf.clear()
        val n = channel.read(buf)
        check(n >= 0) {
            "step '$step': the peer closed after ${elapsedMs()} ms (expectations still open: ${open()}); lines seen: $seen"
        }
        if (n > 0) pending.append(String(buf.array(), 0, n, Charsets.UTF_8)) else delay(20)
    }
}
