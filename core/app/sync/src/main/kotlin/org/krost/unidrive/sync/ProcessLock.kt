package org.krost.unidrive.sync

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

/**
 * Inter‑process lock based on a shared lock file.
 * The lock is exclusive and works across JVM instances.
 * Callers must ensure unlock() is called after use.
 */
class ProcessLock(
    @PublishedApi internal val lockFile: Path,
) {
    private var channel: FileChannel? = null
    private var lock: FileLock? = null

    /**
     * Sibling file holding the holder's PID. Separate from [lockFile] because
     * Windows blocks reads on a file that's currently FileLock-held — see
     * UD-272. Linux's advisory locks would let us read [lockFile] directly,
     * but cross-platform consistency wins.
     */
    @PublishedApi
    internal val pidFile: Path = lockFile.resolveSibling(lockFile.fileName.toString() + ".pid")

    /**
     * Mode of the process holding this lock. SYNC for SyncCommand,
     * DAEMON for DaemonCommand. Read alongside the PID from .lock.pid
     * so a contender can name the holder accurately.
     */
    enum class Mode(internal val wireToken: String) {
        SYNC("sync"),
        DAEMON("daemon"),
        ;

        companion object {
            internal fun fromWireToken(token: String): Mode? =
                values().firstOrNull { it.wireToken == token }
        }
    }

    /**
     * Pid + mode of a lock's current holder, returned by [readHolderInfo].
     *
     * [mode] is `null` when the sidecar carries a mode token this version
     * does not recognise (forward-compat path per spec: "readers
     * parse the second token literally and MountCommand/SyncCommand
     * refuse with a message identifying the unknown mode"). In that case
     * [rawMode] carries the raw token verbatim so the contention message
     * can name it.
     *
     * Legacy pid-only sidecars (no mode token, pre-fix daemons) yield
     * `mode = Mode.SYNC, rawMode = null` — intentional backwards-compat
     * default.
     */
    data class HolderInfo(val pid: Long, val mode: Mode?, val rawMode: String? = null)

    /**
     * Try to acquire the lock, waiting up to [timeout] for it to become available.
     * If [timeout] is zero, the method returns immediately.
     * Legacy overload — passes Mode.SYNC. New callers should use the explicit-mode variant.
     */
    fun tryLock(timeout: Duration = 0.toDuration(DurationUnit.MILLISECONDS)): Boolean =
        tryLock(Mode.SYNC, timeout)

    /**
     * Try to acquire the lock and stamp `<pid> <mode>` into the sibling .pid file.
     * @return true if the lock was acquired, false otherwise.
     */
    fun tryLock(
        mode: Mode,
        timeout: Duration = 0.toDuration(DurationUnit.MILLISECONDS),
    ): Boolean {
        if (lock != null) return true

        val start = System.currentTimeMillis()
        val timeoutMs = timeout.inWholeMilliseconds
        var acquired = false

        while (!acquired) {
            channel =
                FileChannel.open(
                    lockFile,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                )
            try {
                val candidate = channel!!.tryLock()
                if (candidate != null) {
                    lock = candidate
                    acquired = true
                    runCatching {
                        Files.writeString(
                            pidFile,
                            "${ProcessHandle.current().pid()} ${mode.wireToken}\n",
                        )
                    }
                    break
                }
            } catch (_: Exception) {
                // tryLock failed (e.g. OverlappingFileLockException)
            }
            channel!!.close()
            channel = null

            val elapsed = System.currentTimeMillis() - start
            if (elapsed >= timeoutMs) {
                break
            }
            Thread.sleep(100)
        }
        return acquired
    }

    /**
     * Read the PID stamped by the current holder. Returns `null` if the file
     * is empty (older lock, lock-file race, or the holder crashed before the
     * write completed) or contains non-numeric data.
     *
     * UD-272: contending processes call this on `tryLock()` failure to
     * surface "PID 1234" in the error message. Caller is responsible for
     * deciding whether the PID is still alive — see [ProcessHandle.of].
     *
     * Parses only the first whitespace-separated token so that the new
     * `<pid> <mode>` wire format still yields the correct PID.
     */
    fun readHolderPid(): Long? =
        runCatching {
            Files.readString(pidFile)
                .trim()
                .substringBefore(' ')
                .toLongOrNull()
        }.getOrNull()

    /**
     * Read the (pid, mode) tuple of the current holder. Returns `null` if the
     * file is empty / unreadable / malformed. Backwards-compatible with the
     * legacy pid-only format: a file containing just `<pid>\n` (no mode token)
     * yields `HolderInfo(pid, Mode.SYNC)`. Used by the contention error path
     * to render a mode-specific message.
     */
    fun readHolderInfo(): HolderInfo? =
        runCatching {
            val raw = Files.readString(pidFile).trim()
            if (raw.isEmpty()) return@runCatching null
            val parts = raw.split(Regex("\\s+"), limit = 2)
            val pid = parts.getOrNull(0)?.toLongOrNull() ?: return@runCatching null
            val modeToken = parts.getOrNull(1)
            when (modeToken) {
                null -> {
                    HolderInfo(pid, Mode.SYNC, rawMode = null)
                }
                else -> {
                    val parsed = Mode.fromWireToken(modeToken)
                    if (parsed != null) {
                        HolderInfo(pid, parsed, rawMode = null)
                    } else {
                        HolderInfo(pid, mode = null, rawMode = modeToken)
                    }
                }
            }
        }.getOrNull()

    /**
     * Release the lock and close the underlying file channel.
     * Idempotent.
     */
    fun unlock() {
        lock?.close()
        lock = null
        channel?.close()
        channel = null
        // UD-272: clean up the PID sibling so a future contender that races
        // between unlock + reacquire doesn't see a stale PID.
        runCatching { Files.deleteIfExists(pidFile) }
    }

    /**
     * Execute [block] while holding the lock.
     * @param timeout maximum wait time for the lock
     * @throws IllegalStateException if the lock cannot be acquired within [timeout]
     */
    inline fun <T> withLock(
        timeout: Duration = 0.toDuration(DurationUnit.MILLISECONDS),
        block: () -> T,
    ): T {
        if (!tryLock(timeout)) {
            throw IllegalStateException("Could not acquire lock on $lockFile within $timeout")
        }
        try {
            return block()
        } finally {
            unlock()
        }
    }

    companion object {
        /**
         * Create a lock file in the same directory as the given [stateDbPath],
         * named `.lock`.
         */
        fun fromStateDb(stateDbPath: Path): ProcessLock {
            val lockFile = stateDbPath.parent.resolve(".lock")
            return ProcessLock(lockFile)
        }
    }
}
