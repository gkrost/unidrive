package org.krost.unidrive.cli

import org.krost.unidrive.sync.ProcessLock
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class ProfileLockFactoryTest {
    private val originalErr = System.err
    private val capturedErr = ByteArrayOutputStream()
    private lateinit var lockFile: java.nio.file.Path

    @BeforeTest
    fun setUp() {
        System.setErr(PrintStream(capturedErr))
        lockFile = Files.createTempFile("profile-lock-factory", ".lock")
    }

    @AfterTest
    fun tearDown() {
        System.setErr(originalErr)
        Files.deleteIfExists(lockFile)
        Files.deleteIfExists(lockFile.resolveSibling("${lockFile.fileName}.pid"))
    }

    // MUST mirror the holderDesc + pidPart block of Main.acquireProfileLockForDaemon
    // byte-for-byte. The factory additionally prints recovery-hint lines below the
    // first println (e.g. "Stop the sync watcher first: ..."); those are NOT covered
    // by this helper. If recovery-hint wording drift becomes a maintenance concern,
    // extend this helper and add named tests for each branch's hints.
    private fun renderDaemonFactoryContention(holder: ProcessLock.HolderInfo?, profileName: String) {
        val holderDesc = when {
            holder?.mode == ProcessLock.Mode.SYNC ->
                "Another `unidrive sync` is running for profile '$profileName'"
            holder?.mode == ProcessLock.Mode.DAEMON ->
                "Another `unidrive daemon` already serves profile '$profileName'"
            holder != null && holder.mode == null && holder.rawMode != null ->
                "Profile '$profileName' is held by an unidrive process running in " +
                    "unknown mode '${holder.rawMode}' (this binary may be older than the holder)"
            else ->
                "Another unidrive process is using profile '$profileName'"
        }
        val pidPart = if (holder != null) " (PID ${holder.pid})" else ""
        System.err.println("$holderDesc$pidPart.")
    }

    // MUST mirror the holderDesc + pidPart block of Main.acquireProfileLock
    // byte-for-byte. The factory additionally prints recovery-hint lines below the
    // first println (e.g. "Stop the daemon first: ..."); those are NOT covered by
    // this helper. If recovery-hint wording drift becomes a maintenance concern,
    // extend this helper and add named tests for each branch's hints.
    private fun renderSyncFactoryContention(holder: ProcessLock.HolderInfo?, profileName: String) {
        val holderDesc = when {
            holder?.mode == ProcessLock.Mode.SYNC ->
                "Another `unidrive sync` is running for profile '$profileName'"
            holder?.mode == ProcessLock.Mode.DAEMON ->
                "Profile '$profileName' is currently in use by `unidrive daemon`"
            holder != null && holder.mode == null && holder.rawMode != null ->
                "Profile '$profileName' is held by an unidrive process running in " +
                    "unknown mode '${holder.rawMode}' (this binary may be older than the holder)"
            else ->
                "Another unidrive process is using profile '$profileName'"
        }
        val pidPart = if (holder != null) " (PID ${holder.pid})" else ""
        System.err.println("$holderDesc$pidPart.")
    }

    @Test
    fun daemon_refuses_to_start_when_sync_holds_lock() {
        val held = ProcessLock(lockFile)
        try {
            assertTrue(held.tryLock(ProcessLock.Mode.SYNC), "precondition: SYNC lock must acquire")
            val contender = ProcessLock(lockFile)
            val acquired = contender.tryLock(ProcessLock.Mode.DAEMON)
            assertEquals(false, acquired, "DAEMON must not acquire while SYNC holds")
            val holder = contender.readHolderInfo()
            renderDaemonFactoryContention(holder, profileName = "test_profile")

            val out = capturedErr.toString()
            assertTrue(
                out.contains("Another `unidrive sync` is running for profile 'test_profile'"),
                "expected sync-holder phrasing; got: $out",
            )
            assertTrue(
                out.contains("PID ${ProcessHandle.current().pid()}"),
                "expected holder PID in stderr; got: $out",
            )
        } finally {
            held.unlock()
        }
    }

    @Test
    fun sync_refuses_to_start_when_daemon_holds_lock() {
        val held = ProcessLock(lockFile)
        try {
            assertTrue(held.tryLock(ProcessLock.Mode.DAEMON), "precondition: DAEMON lock must acquire")
            val contender = ProcessLock(lockFile)
            val acquired = contender.tryLock(ProcessLock.Mode.SYNC)
            assertEquals(false, acquired, "SYNC must not acquire while DAEMON holds")
            val holder = contender.readHolderInfo()
            renderSyncFactoryContention(holder, profileName = "test_profile")

            val out = capturedErr.toString()
            assertTrue(
                out.contains("is currently in use by `unidrive daemon`"),
                "expected daemon-holder phrasing; got: $out",
            )
            assertTrue(
                out.contains("PID ${ProcessHandle.current().pid()}"),
                "expected holder PID in stderr; got: $out",
            )
        } finally {
            held.unlock()
        }
    }

    @Test
    fun daemon_refuses_to_start_when_another_daemon_holds_lock() {
        val held = ProcessLock(lockFile)
        try {
            assertTrue(held.tryLock(ProcessLock.Mode.DAEMON), "precondition: DAEMON lock must acquire")
            val contender = ProcessLock(lockFile)
            val acquired = contender.tryLock(ProcessLock.Mode.DAEMON)
            assertEquals(false, acquired, "second DAEMON must not acquire while DAEMON holds")
            val holder = contender.readHolderInfo()
            renderDaemonFactoryContention(holder, profileName = "test_profile")

            val out = capturedErr.toString()
            assertTrue(
                out.contains("Another `unidrive daemon` already serves profile 'test_profile'"),
                "expected daemon-vs-daemon phrasing; got: $out",
            )
            assertTrue(
                out.contains("PID ${ProcessHandle.current().pid()}"),
                "expected holder PID in stderr; got: $out",
            )
        } finally {
            held.unlock()
        }
    }
}
