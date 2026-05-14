package org.krost.unidrive.e2e

enum class FsType { EXT4, NTFS }

object FsLimits {

    val WINDOWS_RESERVED_NAMES: Set<String> = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
    )

    val RESERVED_CHARS: Set<Char> = setOf('<', '>', ':', '"', '/', '\\', '|', '?', '*')

    /**
     * Returns the maximum number of codepoints that fit within the filesystem's
     * name-length limit, given the supplied codepoint list.
     *
     * EXT4: counts UTF-8 bytes; stops when the byte total would exceed 255.
     * NTFS: min(255, codepoints.size) — NTFS stores UTF-16 code units but the
     *       practical per-component limit is 255 characters.
     */
    fun maxNameLength(codepoints: List<Int>, fsType: FsType): Int = when (fsType) {
        FsType.NTFS -> minOf(255, codepoints.size)
        FsType.EXT4 -> {
            var byteTotal = 0
            var count = 0
            for (cp in codepoints) {
                val bytes = utf8ByteCount(cp)
                if (byteTotal + bytes > 255) break
                byteTotal += bytes
                count++
            }
            count
        }
    }

    /**
     * Returns true if the name should be skipped for the given filesystem.
     *
     * Skipped when:
     * - Contains any reserved character (<>:"/\|?*)
     * - Ends with a space or dot (Windows / cross-platform safety)
     * - On NTFS: base name (before first dot) is a Windows reserved name
     */
    fun shouldSkipName(name: String, fsType: FsType): Boolean {
        if (name.any { it in RESERVED_CHARS }) return true
        if (name.endsWith(' ') || name.endsWith('.')) return true
        if (fsType == FsType.NTFS) {
            val base = name.substringBefore('.').uppercase()
            if (base in WINDOWS_RESERVED_NAMES) return true
        }
        return false
    }

    /** Detects the local filesystem type from the OS name system property. */
    fun detectFsType(): FsType {
        val os = System.getProperty("os.name", "").lowercase()
        return if (os.contains("win")) FsType.NTFS else FsType.EXT4
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private fun utf8ByteCount(cp: Int): Int = when {
        cp < 0x80    -> 1
        cp < 0x800   -> 2
        cp < 0x10000 -> 3
        else         -> 4
    }
}
