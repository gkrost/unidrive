package org.krost.unidrive.io

import org.junit.Assume.assumeTrue
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [grantProblem] is the read-only check behind the IPC token files: it reports anyone besides the
 * current user (and SYSTEM / Administrators on Windows) who holds access, and never changes anything.
 */
class OwnerOnlyGrantsTest {
    private lateinit var parent: Path

    @BeforeTest
    fun setUp() {
        parent = Files.createTempDirectory("unidrive-owner-grants-")
    }

    @AfterTest
    fun tearDown() {
        parent.toFile().deleteRecursively()
    }

    // ── DACL reading (pure) ─────────────────────────────────────────────────

    private val user = "S-1-5-21-1-2-3-1001"
    private val allowed = setOf(user, "SY", "BA")

    @Test
    fun `a DACL that grants only the user, SYSTEM and Administrators has no problem`() {
        assertNull(daclGrantProblem("D:P(A;;FA;;;$user)(A;;FA;;;SY)(A;;FA;;;BA)", allowed))
        assertNull(daclGrantProblem("D:AI(A;OICIID;FA;;;SY)(A;OICIID;FA;;;BA)(A;OICIID;FA;;;$user)", allowed))
        assertNull(daclGrantProblem("D:P(A;;FA;;;$user)", allowed), "fewer principals is fine")
        assertNull(daclGrantProblem("D:P", allowed), "an empty DACL grants nobody")
    }

    @Test
    fun `an allow entry for anyone else is a problem, a deny entry is not`() {
        val extra = daclGrantProblem("D:P(A;;FA;;;$user)(A;;0x1200a9;;;BU)", allowed)
        assertNotNull(extra)
        assertTrue("BU" in extra, extra)
        assertNotNull(daclGrantProblem("D:P(A;;FA;;;$user)(A;;FR;;;S-1-5-21-1-2-3-1002)", allowed))
        assertNotNull(daclGrantProblem("D:P(A;;FA;;;$user)(A;OICIIO;GA;;;CO)", allowed), "an inherit-only entry still grants the children")
        assertNull(daclGrantProblem("D:P(D;;FA;;;WD)(A;;FA;;;$user)", allowed))
    }

    @Test
    fun `a missing DACL grants everyone and an unreadable one is not trusted`() {
        assertNotNull(daclGrantProblem("D:NO_ACCESS_CONTROL", allowed))
        assertNotNull(daclGrantProblem("", allowed))
        assertNotNull(daclGrantProblem("D:P(A;;FA;;;$user", allowed), "unbalanced parentheses")
    }

    @Test
    fun `trustees compare without regard to case`() {
        assertNull(daclGrantProblem("D:P(A;;FA;;;${user.lowercase()})", allowed))
    }

    // ── Windows ─────────────────────────────────────────────────────────────

    private fun assumeWindows() = assumeTrue("Windows ACLs only", WindowsAclProbe.isWindows)

    @Test
    fun `a restricted file and folder have no problem`() {
        assumeWindows()
        val dir = Files.createDirectory(parent.resolve("dir"))
        OwnerOnly.requireDirectory(dir)
        val file = Files.writeString(dir.resolve("ipc.token"), "x")
        OwnerOnly.requireFile(file)

        assertNull(OwnerOnly.grantProblem(dir))
        assertNull(OwnerOnly.grantProblem(file))
    }

    @Test
    fun `an inherited grant for another principal is reported and left in place`() {
        assumeWindows()
        WindowsAclProbe.grantInheritableRead(parent, WindowsAclProbe.USERS_SID)
        val file = Files.writeString(parent.resolve("ipc.token"), "x")
        val before = WindowsAclProbe.dacl(file)

        val problem = OwnerOnly.grantProblem(file)

        assertNotNull(problem)
        assertTrue("BU" in problem, problem)
        assertEquals(before, WindowsAclProbe.dacl(file), "a check never changes the ACL")
    }

    // ── POSIX ───────────────────────────────────────────────────────────────

    private fun assumePosix() =
        assumeTrue("POSIX file attributes only", FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))

    @Test
    fun `POSIX 0600 file and 0700 folder have no problem, group or other bits do`() {
        assumePosix()
        val dir = Files.createDirectory(parent.resolve("dir"))
        val file = Files.writeString(dir.resolve("ipc.token"), "x")
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"))
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"))
        assertNull(OwnerOnly.grantProblem(dir))
        assertNull(OwnerOnly.grantProblem(file))

        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r-----"))
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx--x--x"))
        assertNotNull(OwnerOnly.grantProblem(file))
        assertNotNull(OwnerOnly.grantProblem(dir))
        assertEquals("rw-r-----", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)), "a check never chmods")
    }

    @Test
    fun `a path that does not exist is a problem`() {
        assertNotNull(OwnerOnly.grantProblem(parent.resolve("missing")))
    }
}
