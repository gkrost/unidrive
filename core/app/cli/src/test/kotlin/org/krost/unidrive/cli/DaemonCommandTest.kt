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
