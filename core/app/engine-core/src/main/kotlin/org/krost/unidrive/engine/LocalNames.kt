package org.krost.unidrive.engine

// #230: a cloud entry whose name cannot be represented on the local filesystem
// (Windows forbids all-dots names like `....`, trailing dot/space, reserved
// device names CON/PRN/AUX/NUL/COM[1-9]/LPT[1-9], and the characters
// <>:"/\|?*) can never be materialised here. The engines detect this up front:
// the mirror quarantines the row instead of re-attempting the doomed download
// every poll cycle (see SyncEngine.applyDownload / the reconciler recovery
// loop), and the mount refuses the write/mkdir/rename (#526).
private val WINDOWS_RESERVED_NAMES: Set<String> =
    setOf("CON", "PRN", "AUX", "NUL") + (1..9).map { "COM$it" } + (1..9).map { "LPT$it" }

private const val WINDOWS_RESERVED_CHARS = "<>:\"/\\|?*"

/**
 * Returns a human-readable reason if any component of [remotePath] cannot be
 * represented as a file/directory name on the local filesystem, else null.
 *
 * Shared by both front-ends (#560 U6 moved it here from :app:sync): the mirror
 * writes local files under its sync root, the mount writes cache files under
 * the hydration cache — both are bound by the same OS name rules.
 *
 * [windows] defaults to the host OS but is a parameter so the Win32 rules can be
 * unit-tested deterministically on any platform. POSIX accepts essentially any
 * byte in a name except '/' (the separator, already split out) and NUL.
 */
fun localNameIssue(
    remotePath: String,
    windows: Boolean = System.getProperty("os.name", "").lowercase().contains("win"),
): String? {
    for (component in remotePath.split('/')) {
        if (component.isEmpty() || component == "." || component == "..") continue
        if (component.any { it.code == 0 }) return "name contains a NUL character"
        // Filesystems commonly cap a single name at 255 units: UTF-16 code
        // units on Windows and UTF-8 bytes on POSIX.
        if (windows) {
            if (component.length > 255) {
                return "Windows names cannot exceed 255 characters (got ${component.length}): '${component.take(80)}…'"
            }
        } else {
            val bytes = component.toByteArray(Charsets.UTF_8).size
            if (bytes > 255) {
                return "POSIX names cannot exceed 255 UTF-8 bytes (got $bytes): '${component.take(80)}…'"
            }
        }
        if (!windows) continue
        if (component.last() == '.' || component.last() == ' ') {
            return "Windows names cannot end with '.' or a space: '$component'"
        }
        if (component.all { it == '.' }) return "Windows names cannot be all dots: '$component'"
        if (component.any { it.code < 0x20 || it in WINDOWS_RESERVED_CHARS }) {
            return "Windows names cannot contain control or reserved characters: '$component'"
        }
        if (component.substringBefore('.').uppercase() in WINDOWS_RESERVED_NAMES) {
            return "Windows reserved device name: '$component'"
        }
    }
    return null
}
