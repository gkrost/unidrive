package org.krost.unidrive.io

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission

/**
 * The read-only counterpart of [OwnerOnly.restrictFile] / [OwnerOnly.restrictDirectory]: null when
 * nobody but the current user can reach [path], otherwise why not. Changes nothing.
 *
 * - Windows: every entry of the DACL that grants access names the current user, SYSTEM or
 *   Administrators; deny entries are ignored (they only take access away), a missing DACL grants
 *   everyone. The owner is not looked at: an elevated process makes Administrators the owner of what
 *   it creates, so "owner == current user" would refuse a legitimate file.
 * - POSIX: no group or other permission bits (a symbolic link is judged by itself, not its target).
 *
 * Any failure to read the permissions is reported as a problem, never as "fine".
 */
public fun OwnerOnly.grantProblem(path: Path): String? =
    try {
        if (isWindowsPath(path)) windowsGrantProblem(path) else posixGrantProblem(path)
    } catch (e: Exception) {
        "its permissions cannot be read: ${e.message ?: e.javaClass.name}"
    } catch (e: LinkageError) {
        "its permissions cannot be read: ${e.message ?: e.javaClass.name}"
    }

private fun isWindowsPath(path: Path): Boolean =
    path.fileSystem == FileSystems.getDefault() &&
        System.getProperty("os.name", "").lowercase().contains("win")

private val groupOrOther =
    setOf(
        PosixFilePermission.GROUP_READ,
        PosixFilePermission.GROUP_WRITE,
        PosixFilePermission.GROUP_EXECUTE,
        PosixFilePermission.OTHERS_READ,
        PosixFilePermission.OTHERS_WRITE,
        PosixFilePermission.OTHERS_EXECUTE,
    )

private fun posixGrantProblem(path: Path): String? {
    val view =
        Files.getFileAttributeView(path, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS)
            ?: return "no POSIX permissions or Windows ACLs on ${path.fileSystem}"
    val permissions = view.readAttributes().permissions()
    val extra = permissions.intersect(groupOrOther)
    return if (extra.isEmpty()) null else "its mode grants ${extra.sorted().joinToString(", ")}"
}

// Windows writes well-known principals back as SDDL aliases (SY, BA, sometimes one for the user), so
// the accepted trustees are taken from a DACL Windows itself canonicalised, plus the plain SIDs.
private val windowsAllowedTrustees: Set<String> by lazy {
    val user = WindowsSecurity.currentUserSid
    val canonical = WindowsSecurity.canonicalDacl("D:(A;;FA;;;$user)(A;;FA;;;SY)(A;;FA;;;BA)")
    val aliases = daclAces(canonical)?.mapNotNull { it.split(';').getOrNull(TRUSTEE_FIELD) }.orEmpty()
    (aliases + listOf(user, "SY", "BA", SYSTEM_SID, WindowsSecurity.ADMINISTRATORS_SID)).toSet()
}

private fun windowsGrantProblem(path: Path): String? = daclGrantProblem(WindowsSecurity.read(path).dacl, windowsAllowedTrustees)

private const val SYSTEM_SID = "S-1-5-18"
private const val TRUSTEE_FIELD = 5
private val DENY_TYPES = setOf("D", "OD", "XD")

/**
 * The grant check on a DACL in SDDL (`D:<flags>(ace)(ace)...`, as Windows writes it back): null when
 * every entry that grants access names one of [allowedTrustees] (compared without regard to case).
 */
internal fun daclGrantProblem(
    dacl: String,
    allowedTrustees: Set<String>,
): String? {
    if (!dacl.startsWith("D:")) return "no DACL could be read"
    if (dacl.contains("NO_ACCESS_CONTROL")) return "it has no DACL, which grants everyone access"
    val aces = daclAces(dacl) ?: return "its DACL cannot be parsed: $dacl"
    val allowed = allowedTrustees.map { it.uppercase() }.toSet()
    val strangers =
        aces.mapNotNull { ace ->
            val fields = ace.split(';')
            val type = fields.firstOrNull()?.uppercase() ?: return@mapNotNull ace
            if (type in DENY_TYPES) return@mapNotNull null
            val trustee = fields.getOrNull(TRUSTEE_FIELD) ?: return@mapNotNull ace
            if (trustee.uppercase() in allowed) null else trustee
        }
    return if (strangers.isEmpty()) null else "its DACL grants access to ${strangers.distinct().joinToString(", ")}"
}

/** The entries of a DACL without their outer parentheses, or null when the parentheses do not balance. */
private fun daclAces(dacl: String): List<String>? {
    val aces = mutableListOf<String>()
    var depth = 0
    var start = -1
    for ((i, c) in dacl.withIndex()) {
        when (c) {
            '(' -> {
                if (depth == 0) start = i + 1
                depth++
            }
            ')' -> {
                depth--
                if (depth < 0) return null
                if (depth == 0) aces.add(dacl.substring(start, i))
            }
        }
    }
    return if (depth == 0) aces else null
}
