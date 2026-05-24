package org.krost.unidrive.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
}
