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

    private fun renderMountFactoryContention(holder: ProcessLock.HolderInfo?, profileName: String) {
        val holderDesc = when {
            holder?.mode == ProcessLock.Mode.SYNC ->
                "Another `unidrive sync` is mirroring profile '$profileName'"
            holder?.mode == ProcessLock.Mode.MOUNT ->
                "Another `unidrive mount` already serves profile '$profileName'"
            holder != null && holder.mode == null && holder.rawMode != null ->
                "Profile '$profileName' is held by an unidrive process running in " +
                    "unknown mode '${holder.rawMode}' (this binary may be older than the holder)"
            else ->
                "Another unidrive process is using profile '$profileName'"
        }
        val pidPart = if (holder != null) " (PID ${holder.pid})" else ""
        System.err.println("$holderDesc$pidPart.")
        if (holder?.mode == ProcessLock.Mode.SYNC) {
            System.err.println(
                "Stop the sync watcher first: `kill ${holder.pid}` (or Ctrl-C its terminal).",
            )
        }
    }

    private fun renderSyncFactoryContention(holder: ProcessLock.HolderInfo?, profileName: String) {
        val holderDesc = when {
            holder?.mode == ProcessLock.Mode.SYNC ->
                "Another `unidrive sync` is running for profile '$profileName'"
            holder?.mode == ProcessLock.Mode.MOUNT ->
                "Profile '$profileName' is currently FUSE-mounted by `unidrive mount`"
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
    fun mount_command_refuses_when_sync_holds_lock() {
        val held = ProcessLock(lockFile)
        try {
            assertTrue(held.tryLock(ProcessLock.Mode.SYNC), "precondition: SYNC lock must acquire")
            val contender = ProcessLock(lockFile)
            val acquired = contender.tryLock(ProcessLock.Mode.MOUNT)
            assertEquals(false, acquired, "MOUNT must not acquire while SYNC holds")
            val holder = contender.readHolderInfo()
            renderMountFactoryContention(holder, profileName = "test_profile")

            val out = capturedErr.toString()
            assertTrue(
                out.contains("is mirroring profile 'test_profile'"),
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
    fun sync_command_refuses_when_mount_holds_lock() {
        val held = ProcessLock(lockFile)
        try {
            assertTrue(held.tryLock(ProcessLock.Mode.MOUNT), "precondition: MOUNT lock must acquire")
            val contender = ProcessLock(lockFile)
            val acquired = contender.tryLock(ProcessLock.Mode.SYNC)
            assertEquals(false, acquired, "SYNC must not acquire while MOUNT holds")
            val holder = contender.readHolderInfo()
            renderSyncFactoryContention(holder, profileName = "test_profile")

            val out = capturedErr.toString()
            assertTrue(
                out.contains("is currently FUSE-mounted by"),
                "expected mount-holder phrasing; got: $out",
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
