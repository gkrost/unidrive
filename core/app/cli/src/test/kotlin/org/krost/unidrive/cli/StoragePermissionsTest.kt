package org.krost.unidrive.cli

import org.junit.Assume.assumeTrue
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Engine start tightens what earlier versions created with inherited permissions: the credential
 * files and the profile folder, the hydration cache's profile root and the log folder. Temp folders
 * only; the real profile, cache and log folders are never touched here.
 */
class StoragePermissionsTest {
    private lateinit var root: Path

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("unidrive-storage-perm-")
    }

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    private fun sids(path: Path): Set<String> = WindowsAclProbe.aces(WindowsAclProbe.dacl(path)).map { it.substringAfterLast(';') }.toSet()

    private val ownerOnlySids get() = setOf(WindowsAclProbe.userSddlSid, "SY", "BA")

    @Test
    fun `a pre-existing profile folder, its credential files and its state are tightened on Windows`() {
        assumeTrue("Windows ACLs only", WindowsAclProbe.isWindows)
        WindowsAclProbe.grantInheritableRead(root, WindowsAclProbe.USERS_SID)
        val profileDir = Files.createDirectory(root.resolve("profile"))
        val token = Files.writeString(profileDir.resolve("token.json"), "{}")
        val credentials = Files.writeString(profileDir.resolve("credentials.json"), "{}")
        val stateDb = Files.writeString(profileDir.resolve("state.db"), "x")
        assertTrue(WindowsAclProbe.USERS_SID in sids(token) || "BU" in sids(token), "precondition: inherited extra grant")

        StoragePermissions.restrictProfile(profileDir)

        for (file in listOf(token, credentials)) {
            val dacl = WindowsAclProbe.dacl(file)
            assertTrue(dacl.startsWith("D:P"), "$file: explicit, protected: $dacl")
            assertEquals(ownerOnlySids, sids(file), dacl)
        }
        assertTrue(WindowsAclProbe.dacl(profileDir).startsWith("D:P"))
        assertEquals(ownerOnlySids, sids(profileDir))
        assertEquals(ownerOnlySids, sids(stateDb))
    }

    @Test
    fun `the cache profile root is created owner-only and the log folder is tightened on Windows`() {
        assumeTrue("Windows ACLs only", WindowsAclProbe.isWindows)
        WindowsAclProbe.grantInheritableRead(root, WindowsAclProbe.USERS_SID)
        val cacheRoot = root.resolve("cache").resolve("unidrive").resolve("hydration").resolve("p1")
        val logDir = Files.createDirectory(root.resolve("logs"))
        val log = Files.writeString(logDir.resolve("unidrive.log"), "line\n")

        StoragePermissions.restrictCacheAndLogs(cacheRoot, logDir)

        assertTrue(Files.isDirectory(cacheRoot))
        assertTrue(WindowsAclProbe.dacl(cacheRoot).startsWith("D:P"))
        assertEquals(ownerOnlySids, sids(cacheRoot))
        assertEquals(ownerOnlySids, sids(logDir))
        assertEquals(ownerOnlySids, sids(log))
    }

    @Test
    fun `profile, cache and log folders become 0700 and credential files 0600 on POSIX`() {
        assumeTrue(
            "POSIX file attributes only",
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
        )
        val profileDir = Files.createDirectory(root.resolve("profile"))
        Files.setPosixFilePermissions(profileDir, PosixFilePermissions.fromString("rwxr-xr-x"))
        val token = Files.writeString(profileDir.resolve("token.json"), "{}")
        Files.setPosixFilePermissions(token, PosixFilePermissions.fromString("rw-r--r--"))
        val cacheRoot = root.resolve("cache").resolve("p1")
        val logDir = Files.createDirectory(root.resolve("logs"))
        Files.setPosixFilePermissions(logDir, PosixFilePermissions.fromString("rwxr-xr-x"))

        StoragePermissions.restrictProfile(profileDir)
        StoragePermissions.restrictCacheAndLogs(cacheRoot, logDir)

        fun mode(p: Path) = PosixFilePermissions.toString(Files.getPosixFilePermissions(p))
        assertEquals("rw-------", mode(token))
        assertEquals("rwx------", mode(profileDir))
        assertEquals("rwx------", mode(cacheRoot))
        assertEquals("rwx------", mode(logDir))
    }

    @Test
    fun `a missing log folder is skipped and a missing profile folder is left alone`() {
        StoragePermissions.restrictProfile(root.resolve("no-profile"))
        StoragePermissions.restrictCacheAndLogs(root.resolve("cache"), root.resolve("no-logs"))
        assertTrue(Files.isDirectory(root.resolve("cache")))
        assertTrue(Files.notExists(root.resolve("no-logs")))
        assertTrue(Files.notExists(root.resolve("no-profile")))
    }
}
