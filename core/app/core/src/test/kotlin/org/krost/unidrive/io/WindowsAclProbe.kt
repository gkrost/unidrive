package org.krost.unidrive.io

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Reads and changes Windows ACLs through `icacls`, independently of the code under test, so a test
 * can simulate an inherited grant and check the result with a second tool.
 */
internal object WindowsAclProbe {
    val isWindows: Boolean = System.getProperty("os.name", "").lowercase().contains("win")

    /** BUILTIN\Users, a principal the owner-only ACL must not contain. */
    const val USERS_SID = "S-1-5-32-545"

    /** The DACL of [path] in SDDL, as `icacls /save` writes it. */
    fun dacl(path: Path): String {
        val out = Files.createTempFile("unidrive-acl-probe-", ".txt")
        try {
            run("icacls", path.toString(), "/save", out.toString())
            return String(Files.readAllBytes(out), Charsets.UTF_16LE)
                .lines()
                .map { it.trim() }
                .first { it.startsWith("D:") }
        } finally {
            Files.deleteIfExists(out)
        }
    }

    /** Adds an inheritable read grant for [sid] on [dir]; children created afterwards inherit it. */
    fun grantInheritableRead(
        dir: Path,
        sid: String,
    ) = run("icacls", dir.toString(), "/grant", "*$sid:(OI)(CI)RX")

    fun grantRead(file: Path, sid: String) = run("icacls", file.toString(), "/grant", "*$sid:R")

    fun protectInheritance(path: Path) = run("icacls", path.toString(), "/inheritance:d")

    /** Denies the owner (OWNER RIGHTS, S-1-3-4) reading the permissions of [file]; deleting it through the folder still works.
     *  Only an OWNER RIGHTS ACE can take the owner's implicit READ_CONTROL away — a deny to Everyone or another
     *  group cannot — and even this one does not bind every token (an elevated Administrators token in some
     *  configurations still reads the DACL). Callers probe the effect and skip where it does not hold. */
    fun denyOwnerReadControl(file: Path) = run("icacls", file.toString(), "/deny", "*S-1-3-4:(RC)")

    fun junction(link: Path, target: Path) = run("cmd", "/c", "mklink", "/J", link.toString(), target.toString())

    /** The SID of the user running the tests, as `whoami` reports it. */
    val userSid: String by lazy {
        Regex("S-1-[0-9-]+").find(run("whoami", "/user", "/fo", "csv", "/nh"))?.value
            ?: error("whoami printed no SID")
    }

    // Windows can encode a current-user SID as an alias, such as LA for the local Administrator.
    // Convert independently of OwnerOnly so expectations compare the same SDDL representation.
    val userSddlSid: String by lazy {
        val powershell = Path.of(System.getenv("SystemRoot"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe")
        val script = "\$sd = [Security.AccessControl.RawSecurityDescriptor]::new('D:(A;;FA;;;$userSid)'); " +
            "\$sd.GetSddlForm([Security.AccessControl.AccessControlSections]::Access)"
        val sddl = run(powershell.toString(), "-NoProfile", "-NonInteractive", "-Command", script)
        aces(sddl).single().substringAfterLast(';')
    }

    /** The SDDL access entries of [dacl], without the parentheses. */
    fun aces(dacl: String): List<String> = Regex("""\(([^()]*)\)""").findAll(dacl).map { it.groupValues[1] }.toList()

    private fun run(vararg command: String): String {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().toString(Charsets.ISO_8859_1)
        check(process.waitFor(30, TimeUnit.SECONDS)) { "${command.toList()} did not finish" }
        check(process.exitValue() == 0) { "${command.toList()} exited ${process.exitValue()}: $output" }
        return output
    }
}
