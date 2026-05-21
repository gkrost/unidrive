package org.krost.unidrive.sync

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

class ProcessLockTest {
    private lateinit var tmpDir: Path
    private lateinit var lockFile: Path

    @BeforeTest
    fun setUp() {
        tmpDir = Files.createTempDirectory("unidrive-processlock-test-")
        lockFile = tmpDir.resolve(".lock")
    }

    @AfterTest
    fun tearDown() {
        runCatching { tmpDir.toFile().deleteRecursively() }
    }

    @Test
    fun `tryLock returns true on first acquire`() {
        val lock = ProcessLock(lockFile)
        try {
            assertTrue(lock.tryLock(), "first tryLock should succeed")
        } finally {
            lock.unlock()
        }
    }

    // -- UD-272: lock file carries holder PID -----------------------------------

    @Test
    fun `UD-272 - tryLock writes own PID into sibling pid file`() {
        val lock = ProcessLock(lockFile)
        val ownPid = ProcessHandle.current().pid()
        val pidFile = lockFile.resolveSibling(lockFile.fileName.toString() + ".pid")
        try {
            assertTrue(lock.tryLock())
            // Sibling .pid file is written before tryLock returns; readable
            // by any peer (Windows would block reads on the FileLock-held
            // .lock itself — UD-272's "use a separate file" rationale).
            val readPid = Files.readString(pidFile).trim().toLongOrNull()
            assertEquals(ownPid, readPid, ".pid sibling should carry our PID")
        } finally {
            lock.unlock()
        }
    }

    @Test
    fun `UD-272 - unlock deletes sibling pid file`() {
        val lock = ProcessLock(lockFile)
        val pidFile = lockFile.resolveSibling(lockFile.fileName.toString() + ".pid")
        assertTrue(lock.tryLock())
        assertTrue(Files.exists(pidFile), "pid file should exist after acquire")
        lock.unlock()
        assertEquals(false, Files.exists(pidFile), "pid file should be removed after unlock")
    }

    @Test
    fun `UD-272 - readHolderPid returns the PID written on tryLock`() {
        val lock = ProcessLock(lockFile)
        val ownPid = ProcessHandle.current().pid()
        try {
            assertTrue(lock.tryLock())
            assertEquals(ownPid, lock.readHolderPid())
        } finally {
            lock.unlock()
        }
    }

    @Test
    fun `UD-272 - readHolderPid returns null when pid file is missing`() {
        // No prior holder → no .pid file → readHolderPid returns null
        // (callers print the generic "Stop it first" message in that case).
        val lock = ProcessLock(lockFile)
        assertNull(lock.readHolderPid(), "missing pid file → null")
    }

    @Test
    fun `UD-272 - readHolderPid returns null on non-numeric content`() {
        val pidFile = lockFile.resolveSibling(lockFile.fileName.toString() + ".pid")
        Files.writeString(pidFile, "not-a-pid\n")
        val lock = ProcessLock(lockFile)
        assertNull(lock.readHolderPid(), "garbage content → null PID")
    }

    @Test
    fun `UD-272 - second tryLock by same JVM in different ProcessLock returns false`() {
        // FileChannel.tryLock is per-JVM exclusive, so a second ProcessLock
        // on the same file in the same JVM emulates a contending peer.
        // After the first acquires, the second's readHolderPid sees our PID.
        val first = ProcessLock(lockFile)
        val second = ProcessLock(lockFile)
        try {
            assertTrue(first.tryLock(), "first should acquire")
            // Second tryLock with zero timeout returns false immediately
            // because the OS-level lock is held by the first.
            assertEquals(false, second.tryLock(0.milliseconds))
            // ... but it can still read the PID stamped by the first.
            val holder = second.readHolderPid()
            assertNotNull(holder, "should be able to read holder PID")
            assertEquals(ProcessHandle.current().pid(), holder)
        } finally {
            first.unlock()
            second.unlock()
        }
    }
}
