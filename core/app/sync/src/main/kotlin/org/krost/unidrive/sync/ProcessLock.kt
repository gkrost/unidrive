package org.krost.unidrive.sync

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
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
     * Release the lock and close the underlying file channel.
     * Idempotent.
     */
    fun unlock() {
        lock?.close()
        lock = null
        channel?.close()
        channel = null
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
