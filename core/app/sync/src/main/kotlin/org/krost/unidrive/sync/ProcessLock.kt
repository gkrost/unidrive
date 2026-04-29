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
     * Try to acquire the lock, waiting up to [timeout] for it to become available.
     * If [timeout] is zero, the method returns immediately.
     * @return true if the lock was acquired, false otherwise.
     */
    fun tryLock(timeout: Duration = 0.toDuration(DurationUnit.MILLISECONDS)): Boolean {
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
                    // UD-272: stamp the holder's PID into a sibling .pid file
                    // so a contending process can name the holder in its
                    // error message ("Stop it with `taskkill /PID 1234 /F`").
                    // Pre-fix the user had to grep `tasklist | findstr java`
                    // and pick by hand from ≥ 3 JVMs (gradle daemon, kotlin
                    // daemon, unidrive daemon, unrelated MCP tools).
                    //
                    // Write to a separate file because Windows blocks reads
                    // on a FileLock-held file — see [pidFile].
                    runCatching {
                        Files.writeString(pidFile, "${ProcessHandle.current().pid()}\n")
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
     */
    fun readHolderPid(): Long? =
        runCatching {
            Files.readString(pidFile).trim().toLongOrNull()
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
