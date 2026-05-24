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
            // Sibling .pid file now carries '<pid> sync\n' format.
            val readContent = Files.readString(pidFile).trim()
            val readPid = readContent.substringBefore(' ').toLongOrNull()
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

    // -- Spec mount-sync-mode-mutex T1/T2/T3/T3a -------------------------------

    @Test
    fun lock_pid_file_carries_mode_after_tryLock() {
        val mountLockFile = Files.createTempFile("mode-mutex-mount", ".lock")
        val mountLock = ProcessLock(mountLockFile)
        try {
            assertTrue(mountLock.tryLock(ProcessLock.Mode.MOUNT))
            val pidFile = mountLockFile.resolveSibling("${mountLockFile.fileName}.pid")
            val body = Files.readString(pidFile).trim()
            assertEquals(
                "${ProcessHandle.current().pid()} mount",
                body,
                "lock-pid sidecar must carry '<pid> mount' on MOUNT acquisition",
            )
            assertEquals(ProcessHandle.current().pid(), mountLock.readHolderPid())
        } finally {
            mountLock.unlock()
            Files.deleteIfExists(mountLockFile)
        }

        val syncLockFile = Files.createTempFile("mode-mutex-sync", ".lock")
        val syncLock = ProcessLock(syncLockFile)
        try {
            assertTrue(syncLock.tryLock(ProcessLock.Mode.SYNC))
            val pidFile = syncLockFile.resolveSibling("${syncLockFile.fileName}.pid")
            val body = Files.readString(pidFile).trim()
            assertEquals("${ProcessHandle.current().pid()} sync", body)
        } finally {
            syncLock.unlock()
            Files.deleteIfExists(syncLockFile)
        }
    }

    @Test
    fun read_holder_info_returns_pid_and_mode_for_locked_file() {
        val lockFile = Files.createTempFile("holder-info", ".lock")
        val held = ProcessLock(lockFile)
        try {
            assertTrue(held.tryLock(ProcessLock.Mode.MOUNT))
            val reader = ProcessLock(lockFile)
            val info = reader.readHolderInfo()
            assertNotNull(info)
            assertEquals(ProcessHandle.current().pid(), info!!.pid)
            assertEquals(ProcessLock.Mode.MOUNT, info.mode)
        } finally {
            held.unlock()
            Files.deleteIfExists(lockFile)
        }

        val lockFile2 = Files.createTempFile("holder-info-sync", ".lock")
        val held2 = ProcessLock(lockFile2)
        try {
            assertTrue(held2.tryLock(ProcessLock.Mode.SYNC))
            val reader = ProcessLock(lockFile2)
            val info = reader.readHolderInfo()
            assertEquals(ProcessLock.Mode.SYNC, info?.mode)
        } finally {
            held2.unlock()
            Files.deleteIfExists(lockFile2)
        }
    }

    @Test
    fun legacy_pid_only_lock_pid_file_reads_as_sync_mode() {
        val lockFile = Files.createTempFile("legacy-pid", ".lock")
        val pidFile = lockFile.resolveSibling("${lockFile.fileName}.pid")
        Files.writeString(pidFile, "12345\n")
        try {
            val reader = ProcessLock(lockFile)
            val info = reader.readHolderInfo()
            assertNotNull(info, "legacy pid-only file must parse")
            assertEquals(12345L, info!!.pid)
            assertEquals(
                ProcessLock.Mode.SYNC,
                info.mode,
                "legacy format must default to SYNC (the only mode that existed before)",
            )
            assertEquals(null, info.rawMode, "legacy format must NOT set rawMode")
        } finally {
            Files.deleteIfExists(pidFile)
            Files.deleteIfExists(lockFile)
        }
    }

    @Test
    fun unknown_mode_token_yields_holder_info_with_null_mode_and_raw_token() {
        val lockFile = Files.createTempFile("unknown-mode", ".lock")
        val pidFile = lockFile.resolveSibling("${lockFile.fileName}.pid")
        Files.writeString(pidFile, "67890 mirror\n")
        try {
            val reader = ProcessLock(lockFile)
            val info = reader.readHolderInfo()
            assertNotNull(info, "unknown-mode file must still parse the pid")
            assertEquals(67890L, info!!.pid)
            assertEquals(
                null,
                info.mode,
                "unknown mode token must yield null mode (not silent SYNC default)",
            )
            assertEquals(
                "mirror",
                info.rawMode,
                "unknown mode token must be preserved verbatim in rawMode",
            )
        } finally {
            Files.deleteIfExists(pidFile)
            Files.deleteIfExists(lockFile)
        }
    }
}
