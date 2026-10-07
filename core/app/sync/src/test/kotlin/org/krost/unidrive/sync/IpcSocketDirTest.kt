package org.krost.unidrive.sync

import org.junit.Assume.assumeTrue
import org.krost.unidrive.io.OwnerOnly
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IpcSocketDirTest {
    private lateinit var tmp: Path

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("unidrive-ipc-dir-test-")
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    // ── POSIX fallback folder: the checks (pure) ────────────────────────────

    private val uid = 1000
    private val ownerOnly = 0b111_000_000
    private val good = IpcSocketDir.DirFacts(isSymbolicLink = false, isDirectory = true, isOther = false, ownerUid = uid, mode = ownerOnly)

    @Test
    fun `a real directory owned by this uid with mode 700 is accepted`() {
        assertNull(IpcSocketDir.fallbackDirProblem(good, uid))
        // The file-type bits that unix:mode carries do not matter.
        assertNull(IpcSocketDir.fallbackDirProblem(good.copy(mode = 0b100_000_111_000_000), uid))
    }

    @Test
    fun `a symbolic link, a non-directory, another owner or a wider mode is refused`() {
        assertTrue(IpcSocketDir.fallbackDirProblem(good.copy(isSymbolicLink = true, isDirectory = false), uid)!!.contains("symbolic link"))
        assertTrue(IpcSocketDir.fallbackDirProblem(good.copy(isDirectory = false), uid)!!.contains("not a directory"))
        assertTrue(IpcSocketDir.fallbackDirProblem(good.copy(isOther = true), uid)!!.contains("not a directory"))
        assertTrue(IpcSocketDir.fallbackDirProblem(good.copy(ownerUid = 0), uid)!!.contains("uid 0"))
        assertTrue(IpcSocketDir.fallbackDirProblem(good.copy(mode = 0b111_101_101), uid)!!.contains("755"))
        assertTrue(IpcSocketDir.fallbackDirProblem(good.copy(mode = 0b111_000_001), uid)!!.contains("701"))
    }

    // ── POSIX fallback folder: create or verify (fake file system) ─────────

    private class FakeOps(
        var existing: IpcSocketDir.DirFacts? = null,
        val createdMode: Int = 0b111_000_000,
    ) : IpcSocketDir.PosixDirOps {
        val created = mutableListOf<Path>()

        override fun facts(dir: Path): IpcSocketDir.DirFacts? = existing

        override fun createPrivate(dir: Path) {
            if (existing != null) throw FileAlreadyExistsException(dir.toString())
            created.add(dir)
            existing = IpcSocketDir.DirFacts(false, true, false, 1000, createdMode)
        }
    }

    @Test
    fun `the fallback folder has a deterministic per-user name and is created when absent`() {
        val ops = FakeOps()

        val dir = IpcSocketDir.ensurePosixFallbackDir(tmp, uid, ops)

        assertEquals(tmp.resolve("unidrive-ipc-1000"), dir)
        assertEquals(listOf(dir), ops.created)
    }

    @Test
    fun `an existing fallback folder that passes the checks is reused`() {
        val ops = FakeOps(existing = good)

        assertEquals(tmp.resolve("unidrive-ipc-1000"), IpcSocketDir.ensurePosixFallbackDir(tmp, uid, ops))
        assertTrue(ops.created.isEmpty())
    }

    @Test
    fun `an existing fallback folder that fails the checks is refused with a message naming it`() {
        for (bad in listOf(good.copy(ownerUid = 1001), good.copy(mode = 0b111_111_111), good.copy(isSymbolicLink = true))) {
            val e = assertFailsWith<IllegalStateException> { IpcSocketDir.ensurePosixFallbackDir(tmp, uid, FakeOps(existing = bad)) }
            assertTrue(e.message!!.contains("unidrive-ipc-1000"), e.message)
        }
    }

    @Test
    fun `on POSIX the fallback folder is created 700, reused, and refused once widened or replaced by a link`() {
        assumeTrue(
            "POSIX file attributes only",
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
        )
        val me = OwnerOnly.posixUid()

        val dir = IpcSocketDir.ensurePosixFallbackDir(tmp, me)

        assertEquals(tmp.resolve("unidrive-ipc-$me"), dir)
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)))
        assertEquals(dir, IpcSocketDir.ensurePosixFallbackDir(tmp, me))

        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"))
        assertFailsWith<IllegalStateException> { IpcSocketDir.ensurePosixFallbackDir(tmp, me) }

        Files.delete(dir)
        Files.createSymbolicLink(dir, Files.createDirectory(tmp.resolve("target")))
        assertFailsWith<IllegalStateException> { IpcSocketDir.ensurePosixFallbackDir(tmp, me) }
    }

    // ── Windows folder ──────────────────────────────────────────────────────

    private fun assumeWindows() = assumeTrue("Windows ACLs only", WindowsAclProbe.isWindows)

    private fun assertOwnerOnly(dir: Path) {
        val dacl = WindowsAclProbe.dacl(dir)
        assertTrue(dacl.startsWith("D:P"), "protected: $dacl")
        assertEquals(
            setOf("A;OICI;FA;;;${WindowsAclProbe.userSid}", "A;OICI;FA;;;SY", "A;OICI;FA;;;BA"),
            WindowsAclProbe.aces(dacl).toSet(),
            dacl,
        )
    }

    @Test
    fun `the Windows socket folder is owner-only even when the temp folder grants others access`() {
        assumeWindows()
        WindowsAclProbe.grantInheritableRead(tmp, WindowsAclProbe.USERS_SID)

        val dir = IpcSocketDir.ensureWindowsDir(tmp)

        assertEquals(tmp.resolve("unidrive-ipc"), dir)
        assertOwnerOnly(dir)
    }

    @Test
    fun `a pre-existing Windows socket folder and the files in it are tightened`() {
        assumeWindows()
        WindowsAclProbe.grantInheritableRead(tmp, WindowsAclProbe.USERS_SID)
        val existing = Files.createDirectory(tmp.resolve("unidrive-ipc"))
        val meta = Files.writeString(existing.resolve("unidrive-0123abcd.sock.meta"), "p\n")
        assertTrue(";BU)" in WindowsAclProbe.dacl(meta), "precondition")

        IpcSocketDir.ensureWindowsDir(tmp)

        assertOwnerOnly(existing)
        val metaSids = WindowsAclProbe.aces(WindowsAclProbe.dacl(meta)).map { it.substringAfterLast(';') }.toSet()
        assertEquals(setOf(WindowsAclProbe.userSid, "SY", "BA"), metaSids)
    }

    @Test
    fun `a junction in place of the Windows socket folder is refused`() {
        assumeWindows()
        val target = Files.createDirectory(tmp.resolve("elsewhere"))
        val link = tmp.resolve("unidrive-ipc")
        val p = ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString()).redirectErrorStream(true).start()
        p.inputStream.readBytes()
        assertTrue(p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0, "precondition: junction created")

        val e = assertFailsWith<IllegalStateException> { IpcSocketDir.ensureWindowsDir(tmp) }
        assertTrue(e.message!!.contains("unidrive-ipc"), e.message)
        Files.delete(link)
    }

    @Test
    fun `defaultSocketPath puts the socket in the owner-only folder`() {
        assumeWindows()
        WindowsAclProbe.grantInheritableRead(tmp, WindowsAclProbe.USERS_SID)

        val socket = IpcServer.defaultSocketPath("p1", tmp, windows = true)

        // The name may be the hashed form: the temp path of a test is long.
        assertEquals(tmp.resolve("unidrive-ipc"), socket.parent)
        assertOwnerOnly(socket.parent)
    }
}
