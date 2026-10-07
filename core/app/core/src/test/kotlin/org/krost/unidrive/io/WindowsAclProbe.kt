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

    /** The SID of the user running the tests, as `whoami` reports it. */
    val userSid: String by lazy {
        Regex("S-1-[0-9-]+").find(run("whoami", "/user", "/fo", "csv", "/nh"))?.value
            ?: error("whoami printed no SID")
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
