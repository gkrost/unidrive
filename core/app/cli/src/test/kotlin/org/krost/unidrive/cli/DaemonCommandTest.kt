package org.krost.unidrive.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Daemon-lifecycle reliability guards.
 *
 * One named test per fixed invariant:
 *   - `daemon status` refuses a non-daemon lock holder.
 *   - `daemon stop` detects a live mount served by this daemon
 *     (so it can refuse without --force rather than orphan the mount).
 */
class DaemonCommandTest {
    // ── daemon status warns/refuses on a non-daemon lock holder ──────────────

    // Invariant: when the .lock.pid holder's mode is not `daemon`
    // (a legacy `sync` watcher, or a pre-mode-mutex pid-only sidecar), the
    // status command must surface a mode-mismatch error pointing at the right
    // stop mechanism — NOT a misleading "daemon status" line for an entirely
    // different process class. The message is symmetric with `daemon stop`.
    @Test
    fun `daemon-status-warns-on-non-daemon-lock-holder`() {
        val msg = daemonModeMismatchMessage("internxt_test", "sync", 12345)
        assertTrue(
            msg.contains("is held by mode 'sync'"),
            "mode-mismatch message must name the actual holder mode; got: $msg",
        )
        assertTrue(
            msg.contains("not 'daemon'"),
            "message must state the holder is not a daemon; got: $msg",
        )
        assertTrue(
            msg.contains("kill 12345"),
            "message must point at the correct stop mechanism with the holder's pid; got: $msg",
        )
        assertFalse(
            msg.startsWith("pid "),
            "must NOT render the misleading file-derived 'pid <n>, mode ...' status line",
        )
    }

    // Invariant: the legacy pid-only sidecar (no mode field,
    // rendered as `(no-mode)` historically) is pre-mode-mutex sync and must be
    // treated as a non-daemon holder — same refusal path, no misleading status.
    @Test
    fun `daemon-status-treats-legacy-no-mode-holder-as-non-daemon`() {
        val msg = daemonModeMismatchMessage("internxt_test", null, 999)
        assertTrue(
            msg.contains("is held by mode '(no-mode)'"),
            "null mode token must render as (no-mode), not the literal 'null'; got: $msg",
        )
        assertFalse(msg.contains("'null'"), "must not leak a literal null mode; got: $msg")
    }

    // ── daemon status does not trust a stale .lock.pid ───────────────────────

    private fun withPidFile(contents: String?, block: (java.nio.file.Path) -> Unit) {
        val dir = java.nio.file.Files.createTempDirectory("daemon-status-test")
        try {
            val pidFile = dir.resolve(".lock.pid")
            if (contents != null) java.nio.file.Files.writeString(pidFile, contents)
            block(pidFile)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    // Invariant: a `.lock.pid` whose pid is no longer running (the daemon was
    // killed without its cleanup) is a stale lock, not a daemon. Status must say
    // so plainly and must not print the dead pid as a status line or suggest a
    // shutdown in progress.
    @Test
    fun `daemon-status-reports-dead-pid-as-stale-lock`() {
        withPidFile("26168 daemon\n") { pidFile ->
            val check = checkDaemonLock("p", pidFile, isAlive = { false })
            val notRunning = check as? DaemonLockCheck.NotRunning
            assertTrue(notRunning != null, "dead pid must read as not running; got: $check")
            assertEquals("no daemon running for profile 'p' (stale lock from pid 26168)", notRunning.message)
            assertFalse(notRunning.message.contains("mid-shutdown"))
        }
    }

    // Invariant: liveness is judged before the holder's mode — a dead legacy
    // sync watcher is just as stale as a dead daemon, and must not be reported
    // as a live holder of another mode.
    @Test
    fun `daemon-status-reports-dead-non-daemon-holder-as-stale-lock`() {
        withPidFile("26168 sync\n") { pidFile ->
            val check = checkDaemonLock("p", pidFile, isAlive = { false })
            assertEquals(
                DaemonLockCheck.NotRunning("no daemon running for profile 'p' (stale lock from pid 26168)"),
                check,
            )
        }
    }

    @Test
    fun `daemon-status-accepts-live-daemon-pid`() {
        withPidFile("26168 daemon\n") { pidFile ->
            assertEquals(DaemonLockCheck.Running(26168), checkDaemonLock("p", pidFile, isAlive = { it == 26168L }))
        }
    }

    @Test
    fun `daemon-status-still-refuses-live-non-daemon-holder`() {
        withPidFile("26168 sync\n") { pidFile ->
            val check = checkDaemonLock("p", pidFile, isAlive = { true })
            assertTrue(check is DaemonLockCheck.Refused, "live sync holder must still be refused; got: $check")
            assertTrue(check.message.contains("is held by mode 'sync'"), check.message)
        }
    }

    @Test
    fun `daemon-status-absent-and-malformed-lock-keep-their-messages`() {
        withPidFile(null) { pidFile ->
            assertEquals(
                DaemonLockCheck.NotRunning("no daemon running for profile 'p'"),
                checkDaemonLock("p", pidFile, isAlive = { true }),
            )
        }
        withPidFile("garbage") { pidFile ->
            val check = checkDaemonLock("p", pidFile, isAlive = { true })
            assertTrue(check is DaemonLockCheck.Refused && check.message.contains("malformed lock-pid file"), "$check")
        }
    }

    // Real liveness (no injected predicate): the pid of a process that has exited
    // reads as stale, this JVM's own pid reads as a running daemon.
    @Test
    fun `daemon-status-default-liveness-uses-the-real-process-table`() {
        val javaBin = java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val child = ProcessBuilder(javaBin, "-version").redirectErrorStream(true).start()
        child.inputStream.readAllBytes()
        child.waitFor()
        val deadPid = child.pid()
        withPidFile("$deadPid daemon\n") { pidFile ->
            val check = checkDaemonLock("p", pidFile)
            assertTrue(
                check is DaemonLockCheck.NotRunning && check.message.contains("stale lock from pid $deadPid"),
                "exited process must read as stale; got: $check",
            )
        }
        val ownPid = ProcessHandle.current().pid()
        withPidFile("$ownPid daemon\n") { pidFile ->
            assertEquals(DaemonLockCheck.Running(ownPid), checkDaemonLock("p", pidFile))
        }
    }

    // ── daemon stop detects a live mount served by this daemon ───────────────

    // Invariant: a live `unidrive-mount` co-daemon bound to this
    // daemon's IPC socket means stopping the daemon would orphan the mount
    // (reads then fail with EIO/ENOENT). The argv `--ipc <socket>` pair is the
    // link between a mount and its daemon, and is what the stop guard keys on.
    @Test
    fun `daemon-stop-warns-on-live-mount`() {
        val socket = "/run/user/1000/unidrive/internxt_test.sock"
        val servingArgv = listOf(
            "/home/gernot/.local/lib/unidrive/unidrive-mount",
            "--mount", "/home/gernot/mnt",
            "--ipc", socket,
            "--cache", "/home/gernot/.cache/unidrive/internxt_test",
        )
        assertTrue(
            StaleMountDetector.cmdlineServesSocket(servingArgv, socket),
            "a co-daemon whose --ipc matches this daemon's socket is served by this daemon",
        )
    }

    // Invariant: a co-daemon bound to a DIFFERENT profile's socket
    // must NOT be counted as served by this daemon — stopping this daemon would
    // not orphan it, so the stop must not be blocked on it.
    @Test
    fun `daemon-stop-ignores-mount-bound-to-other-daemon`() {
        val ourSocket = "/run/user/1000/unidrive/internxt_test.sock"
        val otherProfileArgv = listOf(
            "unidrive-mount",
            "--mount", "/home/gernot/mnt-od",
            "--ipc", "/run/user/1000/unidrive/onedrive.sock",
            "--cache", "/home/gernot/.cache/unidrive/onedrive",
        )
        assertFalse(
            StaleMountDetector.cmdlineServesSocket(otherProfileArgv, ourSocket),
            "a co-daemon bound to another profile's socket is not served by this daemon",
        )
    }

    // Invariant: non-co-daemon processes that merely mention the
    // socket path (e.g. a `cat <socket>`) must never be mistaken for a mount.
    // The argv[0] basename gate prevents that false positive.
    @Test
    fun `daemon-stop-ignores-non-codaemon-process-mentioning-socket`() {
        val socket = "/run/user/1000/unidrive/internxt_test.sock"
        assertFalse(
            StaleMountDetector.cmdlineServesSocket(listOf("cat", socket), socket),
            "only a unidrive-mount co-daemon counts; an unrelated process must not match",
        )
        assertEquals(false, StaleMountDetector.cmdlineServesSocket(emptyList(), socket))
    }
}
