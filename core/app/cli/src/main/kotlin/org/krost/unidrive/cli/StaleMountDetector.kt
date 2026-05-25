package org.krost.unidrive.cli

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

object StaleMountDetector {
    private val procMounts: Path = Paths.get("/proc/self/mounts")

    fun detectStaleFuseUnidriveMounts(): List<String> {
        if (!Files.isReadable(procMounts)) return emptyList()
        return Files.readAllLines(procMounts)
            .mapNotNull { parseUnidriveFuseMountpoint(it) }
    }

    internal fun parseUnidriveFuseMountpoint(line: String): String? {
        val parts = line.split(Regex("\\s+"))
        if (parts.size < 3) return null
        val device = parts[0]
        val mountpoint = parts[1]
        val fstype = parts[2]
        if (fstype != "fuse" && !fstype.startsWith("fuse.")) return null
        if (device != "unidrive") return null
        return mountpoint
    }
}
