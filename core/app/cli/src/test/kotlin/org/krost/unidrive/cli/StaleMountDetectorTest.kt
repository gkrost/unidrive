package org.krost.unidrive.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StaleMountDetectorTest {
    @Test
    fun parses_unidrive_fuse_mountpoint() {
        val line = "unidrive /tmp/onedrive-smoke fuse rw,nosuid,nodev,relatime,user_id=1000,group_id=1000 0 0"
        assertEquals("/tmp/onedrive-smoke", StaleMountDetector.parseUnidriveFuseMountpoint(line))
    }

    @Test
    fun parses_fuse_dot_subtype_mountpoint() {
        val line = "unidrive /tmp/mnt fuse.unidrive rw 0 0"
        assertEquals("/tmp/mnt", StaleMountDetector.parseUnidriveFuseMountpoint(line))
    }

    @Test
    fun rejects_non_fuse_mounts() {
        val line = "/dev/sda1 / ext4 rw,relatime 0 0"
        assertNull(StaleMountDetector.parseUnidriveFuseMountpoint(line))
    }

    @Test
    fun rejects_other_fuse_mounts() {
        val line = "user@host:/path /mnt/sshfs fuse.sshfs rw 0 0"
        assertNull(StaleMountDetector.parseUnidriveFuseMountpoint(line))
    }

    @Test
    fun rejects_malformed_lines() {
        assertNull(StaleMountDetector.parseUnidriveFuseMountpoint(""))
        assertNull(StaleMountDetector.parseUnidriveFuseMountpoint("only-one-field"))
        assertNull(StaleMountDetector.parseUnidriveFuseMountpoint("two fields"))
    }

    // Invariant (issue #105 half B): a unidrive FUSE mount whose backing Rust
    // co-daemon process is still alive is NOT stale — the previous daemon's
    // client is still serving it (or will reconnect). Flagging it as stale is
    // the misleading WARNING the issue calls out. The liveness probe is
    // injected so this exercises no real /proc and no real mount.
    @Test
    fun mount_with_live_codaemon_is_not_flagged_stale() {
        val mountLines = listOf(
            "unidrive /tmp/onedrive-smoke fuse.unidrive rw,user_id=1000,group_id=1000 0 0",
        )
        val probe = StaleMountDetector.CoDaemonLivenessProbe { mountpoint ->
            // A live unidrive-mount co-daemon is serving this exact mountpoint.
            mountpoint == "/tmp/onedrive-smoke"
        }

        val stale = StaleMountDetector.detectStaleFuseUnidriveMounts(mountLines, probe)

        assertTrue(
            stale.isEmpty(),
            "a unidrive FUSE mount with a live co-daemon must not be flagged stale; got: $stale",
        )
    }

    // Invariant (issue #105 half B): a unidrive FUSE mount with NO live backing
    // co-daemon process IS stale — its co-daemon was kill -9'd or otherwise died
    // and the mountpoint no longer serves data. This is the only case that
    // should fire the WARNING.
    @Test
    fun mount_without_codaemon_is_flagged_stale() {
        val mountLines = listOf(
            "unidrive /tmp/onedrive-smoke fuse.unidrive rw,user_id=1000,group_id=1000 0 0",
        )
        val probe = StaleMountDetector.CoDaemonLivenessProbe { false }

        val stale = StaleMountDetector.detectStaleFuseUnidriveMounts(mountLines, probe)

        assertEquals(
            listOf("/tmp/onedrive-smoke"),
            stale,
            "a unidrive FUSE mount with no live co-daemon must be flagged stale",
        )
    }

    // Invariant: a mix of mounts is partitioned correctly — only the ones
    // without a live co-daemon are flagged. Guards against an all-or-nothing
    // filter regression.
    @Test
    fun only_mounts_without_codaemon_are_flagged_among_several() {
        val mountLines = listOf(
            "/dev/sda1 / ext4 rw 0 0",
            "unidrive /tmp/alive fuse.unidrive rw,user_id=1000,group_id=1000 0 0",
            "unidrive /tmp/dead fuse.unidrive rw,user_id=1000,group_id=1000 0 0",
            "user@host:/p /mnt/sshfs fuse.sshfs rw 0 0",
        )
        val probe = StaleMountDetector.CoDaemonLivenessProbe { mountpoint ->
            mountpoint == "/tmp/alive"
        }

        val stale = StaleMountDetector.detectStaleFuseUnidriveMounts(mountLines, probe)

        assertEquals(
            listOf("/tmp/dead"),
            stale,
            "only the unidrive FUSE mount without a live co-daemon must be flagged",
        )
    }
}
