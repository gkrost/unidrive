package org.krost.unidrive.io

import org.junit.Assume.assumeTrue
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OwnerOnlyTest {
    private lateinit var parent: Path

    @BeforeTest
    fun setUp() {
        parent = Files.createTempDirectory("unidrive-owner-only-")
    }

    @AfterTest
    fun tearDown() {
        parent.toFile().deleteRecursively()
    }

    @Test
    fun `a Windows Unix socket is tightened without being mistaken for a junction`() {
        assumeTrue("Windows ACLs only", WindowsAclProbe.isWindows)
        val socket = parent.resolve("live.sock")
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
            server.bind(UnixDomainSocketAddress.of(socket))
            WindowsAclProbe.grantRead(socket, WindowsAclProbe.USERS_SID)
            assertTrue(OwnerOnly.requireFile(socket).restricted)
            assertEquals(
                setOf(WindowsAclProbe.userSddlSid, "SY", "BA"),
                WindowsAclProbe.aces(WindowsAclProbe.dacl(socket)).map { it.substringAfterLast(';') }.toSet(),
            )
            WindowsAclProbe.grantRead(socket, WindowsAclProbe.USERS_SID)
            assertTrue(OwnerOnly.requireDirectory(parent).restricted)
            assertEquals(
                setOf(WindowsAclProbe.userSddlSid, "SY", "BA"),
                WindowsAclProbe.aces(WindowsAclProbe.dacl(socket)).map { it.substringAfterLast(';') }.toSet(),
            )
            SocketChannel.open(UnixDomainSocketAddress.of(socket)).close()
        }
    }

    // ── owner rule (pure) ───────────────────────────────────────────────────

    private val user = "S-1-5-21-1-2-3-1001"
    private val other = "S-1-5-21-1-2-3-1002"
    private val administrators = "S-1-5-32-544"

    @Test
    fun `the current user is an accepted owner, elevated or not`() {
        assertTrue(OwnerOnly.windowsOwnerAccepted(user, user, processIsAdministrator = false))
        assertTrue(OwnerOnly.windowsOwnerAccepted(user, user, processIsAdministrator = true))
    }

    @Test
    fun `Administrators is an accepted owner only while this process is an administrator`() {
        assertTrue(OwnerOnly.windowsOwnerAccepted(administrators, user, processIsAdministrator = true))
        assertFalse(OwnerOnly.windowsOwnerAccepted(administrators, user, processIsAdministrator = false))
    }

    @Test
    fun `another user is never an accepted owner`() {
        assertFalse(OwnerOnly.windowsOwnerAccepted(other, user, processIsAdministrator = false))
        assertFalse(OwnerOnly.windowsOwnerAccepted(other, user, processIsAdministrator = true))
    }

    // ── DACL comparison (pure) ──────────────────────────────────────────────

    private val desired = "D:P(A;OICI;FA;;;$user)(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)"

    @Test
    fun `a protected DACL with exactly the owner-only entries matches, in any order`() {
        assertTrue(OwnerOnly.daclIsOwnerOnly("D:PAI(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)(A;OICI;FA;;;$user)", desired))
        assertTrue(OwnerOnly.daclIsOwnerOnly(desired, desired))
    }

    @Test
    fun `inherited entries, a missing protection or an extra principal do not match`() {
        assertFalse(OwnerOnly.daclIsOwnerOnly("D:AI(A;OICIID;FA;;;$user)(A;OICIID;FA;;;SY)(A;OICIID;FA;;;BA)", desired))
        assertFalse(OwnerOnly.daclIsOwnerOnly("D:AI(A;OICI;FA;;;$user)(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)", desired))
        assertFalse(OwnerOnly.daclIsOwnerOnly("$desired(A;OICI;0x1200a9;;;BU)", desired))
        assertFalse(OwnerOnly.daclIsOwnerOnly("D:P(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)", desired))
    }

    // ── failures are reported ───────────────────────────────────────────────

    @Test
    fun `a path that does not exist is a failure, and require throws`() {
        val missing = parent.resolve("missing")
        val outcome = OwnerOnly.restrictDirectory(missing)
        assertTrue(outcome is OwnerOnly.Outcome.Failed, "got $outcome")
        assertFailsWith<IOException> { OwnerOnly.requireDirectory(missing) }
        assertFailsWith<IOException> { OwnerOnly.requireFile(missing) }
    }

    // ── Windows: explicit, protected DACL ───────────────────────────────────

    private fun assumeWindows() = assumeTrue("Windows ACLs only", WindowsAclProbe.isWindows)

    /** [dir] inherits an extra read grant from [parent], as folders under a shared temp folder do. */
    private fun childWithInheritedExtraGrant(name: String): Path {
        WindowsAclProbe.grantInheritableRead(parent, WindowsAclProbe.USERS_SID)
        val dir = Files.createDirectory(parent.resolve(name))
        assertTrue(";BU)" in WindowsAclProbe.dacl(dir), "precondition: the extra grant is inherited")
        return dir
    }

    private fun assertOwnerOnlyDirectory(dir: Path) {
        val dacl = WindowsAclProbe.dacl(dir)
        assertTrue(dacl.startsWith("D:P"), "protected (no inheritance from the parent): $dacl")
        assertEquals(
            setOf("A;OICI;FA;;;${WindowsAclProbe.userSddlSid}", "A;OICI;FA;;;SY", "A;OICI;FA;;;BA"),
            WindowsAclProbe.aces(dacl).toSet(),
            dacl,
        )
    }

    private fun assertInheritsOwnerOnly(path: Path) {
        val dacl = WindowsAclProbe.dacl(path)
        val sids = WindowsAclProbe.aces(dacl).map { it.substringAfterLast(';') }.toSet()
        assertEquals(setOf(WindowsAclProbe.userSddlSid, "SY", "BA"), sids, dacl)
    }

    @Test
    fun `restrictDirectory replaces an inherited ACL with the owner, SYSTEM and Administrators`() {
        assumeWindows()
        val dir = childWithInheritedExtraGrant("dir")

        assertEquals(OwnerOnly.Outcome.Changed, OwnerOnly.restrictDirectory(dir))

        assertOwnerOnlyDirectory(dir)
        // The owner keeps full control.
        val file = dir.resolve("f.txt")
        Files.writeString(file, "x")
        assertEquals("x", Files.readString(file))
        Files.delete(file)
    }

    @Test
    fun `restrictDirectory tightens a pre-existing folder and the files already in it`() {
        assumeWindows()
        val dir = childWithInheritedExtraGrant("existing")
        val file = Files.writeString(dir.resolve("state.db"), "x")
        val nested = Files.writeString(Files.createDirectory(dir.resolve("sub")).resolve("deep.txt"), "y")
        assertTrue(";BU)" in WindowsAclProbe.dacl(nested), "precondition")

        OwnerOnly.requireDirectory(dir)

        assertOwnerOnlyDirectory(dir)
        assertInheritsOwnerOnly(file)
        assertInheritsOwnerOnly(nested)
    }

    @Test
    fun `a directory already owner-only still tightens explicit and protected child grants`() {
        assumeWindows()
        val dir = Files.createDirectory(parent.resolve("explicit-children"))
        OwnerOnly.requireDirectory(dir)
        val file = Files.writeString(dir.resolve("state.db"), "x")
        WindowsAclProbe.grantRead(file, WindowsAclProbe.USERS_SID)
        val sub = Files.createDirectory(dir.resolve("protected"))
        WindowsAclProbe.protectInheritance(sub)
        WindowsAclProbe.grantInheritableRead(sub, WindowsAclProbe.USERS_SID)
        val nested = Files.writeString(sub.resolve("cache.txt"), "y")
        assertTrue(";BU)" in WindowsAclProbe.dacl(file), "precondition: explicit read grant")
        assertTrue(";BU)" in WindowsAclProbe.dacl(nested), "precondition: protected parent retains extra grant")

        assertEquals(OwnerOnly.Outcome.Changed, OwnerOnly.restrictDirectory(dir))

        assertOwnerOnlyDirectory(dir)
        assertOwnerOnlyDirectory(sub)
        assertInheritsOwnerOnly(file)
        assertInheritsOwnerOnly(nested)
        assertEquals(OwnerOnly.Outcome.Unchanged, OwnerOnly.restrictDirectory(dir))
    }

    @Test
    fun `restricting a directory never changes the ACL of a junction target`() {
        assumeWindows()
        val dir = Files.createDirectory(parent.resolve("with-junction"))
        val outside = Files.createDirectory(parent.resolve("outside"))
        WindowsAclProbe.grantInheritableRead(outside, WindowsAclProbe.USERS_SID)
        val before = WindowsAclProbe.dacl(outside)
        val link = dir.resolve("linked")
        WindowsAclProbe.junction(link, outside)
        try {
            assertTrue(OwnerOnly.restrictDirectory(dir) is OwnerOnly.Outcome.Failed)
            assertEquals(before, WindowsAclProbe.dacl(outside), "the target lies outside the restricted tree")
        } finally {
            Files.delete(link)
        }
    }

    // ── Windows: entries that change while the folder is walked ────────────
    // The walk lists a folder before it reads and sets the permissions of each entry; another process
    // (a second daemon sharing the IPC folder) may remove an entry in between. The seam of
    // restrictDirectory removes it at exactly that point.

    @Test
    fun `a file removed after the walk listed it is skipped and the walk completes`() {
        assumeWindows()
        val dir = Files.createDirectory(parent.resolve("walk"))
        val gone = Files.writeString(dir.resolve("a-gone.meta"), "p")
        val kept = Files.writeString(dir.resolve("b-kept.meta"), "p")
        WindowsAclProbe.grantRead(kept, WindowsAclProbe.USERS_SID)

        val outcome = OwnerOnly.restrictDirectory(dir) { if (it == gone) Files.delete(it) }

        assertFalse(Files.exists(gone), "precondition: removed during the walk")
        assertEquals(OwnerOnly.Outcome.Changed, outcome)
        assertOwnerOnlyDirectory(dir)
        assertInheritsOwnerOnly(kept)
    }

    @Test
    fun `a folder removed after the listing, before the walk opened it, is skipped`() {
        assumeWindows()
        val dir = Files.createDirectory(parent.resolve("walk"))
        val first = Files.writeString(dir.resolve("a.meta"), "p")
        val sub = Files.createDirectory(dir.resolve("b-sub"))
        Files.writeString(sub.resolve("x.meta"), "p")
        val kept = Files.writeString(dir.resolve("c-kept.meta"), "p")
        WindowsAclProbe.grantRead(kept, WindowsAclProbe.USERS_SID)
        val seen = mutableListOf<Path>()

        val outcome =
            OwnerOnly.restrictDirectory(dir) {
                seen.add(it)
                if (it == first) assertTrue(sub.toFile().deleteRecursively(), "precondition: folder removed")
            }

        // The listing of a small folder is read at once, so the walk still reaches b-sub and fails to open it.
        assertTrue(seen.none { it.startsWith(sub) }, "precondition: b-sub was gone before the walk reached it: $seen")
        assertEquals(OwnerOnly.Outcome.Changed, outcome)
        assertEquals(listOf(first, kept), seen, "the walk went on after b-sub")
        assertInheritsOwnerOnly(kept)
    }

    @Test
    fun `a folder removed at its own step is skipped with everything in it`() {
        assumeWindows()
        val dir = Files.createDirectory(parent.resolve("walk"))
        val sub = Files.createDirectory(dir.resolve("a-sub"))
        Files.writeString(sub.resolve("x.meta"), "p")
        val kept = Files.writeString(dir.resolve("b-kept.meta"), "p")
        WindowsAclProbe.grantRead(kept, WindowsAclProbe.USERS_SID)

        val outcome = OwnerOnly.restrictDirectory(dir) { if (it == sub) sub.toFile().deleteRecursively() }

        assertFalse(Files.exists(sub), "precondition: removed during the walk")
        assertEquals(OwnerOnly.Outcome.Changed, outcome)
        assertInheritsOwnerOnly(kept)
    }

    @Test
    fun `the folder itself removed during the walk is still a failure`() {
        assumeWindows()
        val dir = Files.createDirectory(parent.resolve("walk"))
        val first = Files.writeString(dir.resolve("a.meta"), "p")
        Files.writeString(dir.resolve("b.meta"), "p")

        val outcome = OwnerOnly.restrictDirectory(dir) { if (it == first) dir.toFile().deleteRecursively() }

        assertFalse(Files.exists(dir), "precondition: removed during the walk")
        assertTrue(outcome is OwnerOnly.Outcome.Failed, "got $outcome")
    }

    @Test
    fun `any other error on an entry still fails the walk and names the entry`() {
        assumeWindows()
        val dir = Files.createDirectory(parent.resolve("walk"))
        OwnerOnly.requireDirectory(dir)
        val locked = Files.writeString(dir.resolve("locked.meta"), "p")
        WindowsAclProbe.denyOwnerReadControl(locked)
        // Only meaningful where the deny really stops the DACL read: the owner's implicit READ_CONTROL is
        // taken away only by an OWNER RIGHTS deny, and even that does not bind every token (elevated
        // Administrators configurations still read the DACL — as on the CI runners, where this failed as
        // "got Changed"). Where the deny has no effect the error cannot be synthesized: skip, don't lie.
        assumeTrue(
            "the deny does not stop the DACL read under this token",
            OwnerOnly.restrictFile(locked) is OwnerOnly.Outcome.Failed,
        )

        val outcome = OwnerOnly.restrictDirectory(dir)

        assertTrue(outcome is OwnerOnly.Outcome.Failed && locked.toString() in outcome.reason, "got $outcome")
        val e = assertFailsWith<IOException> { OwnerOnly.requireDirectory(dir) }
        assertTrue(locked.toString() in e.message!!, e.message)
        Files.delete(locked)
    }

    @Test
    fun `what is created later in a restricted folder gets the owner-only entries only`() {
        assumeWindows()
        val dir = childWithInheritedExtraGrant("later")
        OwnerOnly.requireDirectory(dir)

        val file = Files.writeString(dir.resolve("later.txt"), "z")
        val sub = Files.createDirectory(dir.resolve("later-sub"))

        assertInheritsOwnerOnly(file)
        assertInheritsOwnerOnly(sub)
    }

    @Test
    fun `a later inheritable grant on the parent does not reach a restricted folder`() {
        assumeWindows()
        val dir = childWithInheritedExtraGrant("protected")
        OwnerOnly.requireDirectory(dir)

        WindowsAclProbe.grantInheritableRead(parent, "S-1-5-32-555")

        assertOwnerOnlyDirectory(dir)
    }

    @Test
    fun `restrictFile gives a file an explicit owner-only ACL`() {
        assumeWindows()
        WindowsAclProbe.grantInheritableRead(parent, WindowsAclProbe.USERS_SID)
        val file = Files.writeString(parent.resolve("token.json"), "{}")
        assertTrue(";BU)" in WindowsAclProbe.dacl(file), "precondition")

        assertEquals(OwnerOnly.Outcome.Changed, OwnerOnly.restrictFile(file))

        val dacl = WindowsAclProbe.dacl(file)
        assertTrue(dacl.startsWith("D:P"), dacl)
        assertEquals(
            setOf("A;;FA;;;${WindowsAclProbe.userSddlSid}", "A;;FA;;;SY", "A;;FA;;;BA"),
            WindowsAclProbe.aces(dacl).toSet(),
            dacl,
        )
        assertEquals("{}", Files.readString(file))
    }

    @Test
    fun `restricting twice changes nothing the second time`() {
        assumeWindows()
        val dir = childWithInheritedExtraGrant("twice")
        val file = Files.writeString(dir.resolve("credentials.json"), "{}")

        assertEquals(OwnerOnly.Outcome.Changed, OwnerOnly.restrictFile(file))
        assertEquals(OwnerOnly.Outcome.Changed, OwnerOnly.restrictDirectory(dir))
        assertEquals(OwnerOnly.Outcome.Unchanged, OwnerOnly.restrictFile(file))
        assertEquals(OwnerOnly.Outcome.Unchanged, OwnerOnly.restrictDirectory(dir))
    }

    @Test
    fun `a folder this process created has an accepted owner`() {
        assumeWindows()
        assertNull(OwnerOnly.ownerProblem(Files.createDirectory(parent.resolve("mine"))))
    }

    // ── POSIX: 0700 / 0600 ──────────────────────────────────────────────────

    private fun assumePosix() =
        assumeTrue(
            "POSIX file attributes only",
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
        )

    @Test
    fun `POSIX folder becomes 0700 and file 0600`() {
        assumePosix()
        val dir = Files.createDirectory(parent.resolve("dir"))
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"))
        val file = Files.writeString(dir.resolve("f"), "x")
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"))

        assertEquals(OwnerOnly.Outcome.Changed, OwnerOnly.restrictDirectory(dir))
        assertEquals(OwnerOnly.Outcome.Changed, OwnerOnly.restrictFile(file))

        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)))
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        assertEquals(OwnerOnly.Outcome.Unchanged, OwnerOnly.restrictDirectory(dir))
        assertEquals(OwnerOnly.Outcome.Unchanged, OwnerOnly.restrictFile(file))
    }

    @Test
    fun `POSIX owner of a folder this process created is accepted`() {
        assumePosix()
        assertNull(OwnerOnly.ownerProblem(Files.createDirectory(parent.resolve("mine"))))
    }
}
