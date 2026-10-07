package org.krost.unidrive.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IpcServerPermissionsTest {
    private lateinit var socketDir: Path
    private lateinit var socketPath: Path
    private var server: IpcServer? = null

    @BeforeTest
    fun setUp() {
        socketDir = Files.createTempDirectory("unidrive-ipc-perm-test")
        socketPath = socketDir.resolve("test.sock")
    }

    @AfterTest
    fun tearDown() {
        server?.close()
        Files.deleteIfExists(socketPath)
        Files.deleteIfExists(socketDir)
    }

    // UD-100: socket file must be 0600 after bind. Defense-in-depth parity with
    // parent dir 0700 (defaultSocketPath()). Skip on non-POSIX filesystems (Windows).
    @Test
    fun `socket file permissions are 0600 after start`() {
        assumeTrue(
            "POSIX file attributes not supported on this filesystem",
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
        )

        runBlocking(Dispatchers.IO) {
            val serverScope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                server = IpcServer(socketPath)
                server!!.start(serverScope)
                delay(100)

                val perms = Files.getPosixFilePermissions(socketPath)
                val expected = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
                assertEquals(expected, perms, "Socket file should have 0600 permissions, got: $perms")
            } finally {
                serverScope.cancel()
            }
        }
    }

    @Test
    fun `socket file has an owner-only ACL after start on Windows`() {
        assumeTrue("Windows ACLs only", WindowsAclProbe.isWindows)
        // The folder grants an extra principal inheritable access; the socket must not keep it.
        WindowsAclProbe.grantInheritableRead(socketDir, WindowsAclProbe.USERS_SID)

        runBlocking(Dispatchers.IO) {
            val serverScope = CoroutineScope(coroutineContext + SupervisorJob())
            try {
                server = IpcServer(socketPath)
                server!!.start(serverScope)

                val dacl = WindowsAclProbe.dacl(socketPath)
                assertTrue(dacl.startsWith("D:P"), "protected: $dacl")
                assertEquals(
                    setOf(WindowsAclProbe.userSid, "SY", "BA"),
                    WindowsAclProbe.aces(dacl).map { it.substringAfterLast(';') }.toSet(),
                    dacl,
                )
                // The owner can still connect.
                SocketChannel.open(UnixDomainSocketAddress.of(socketPath)).close()
            } finally {
                serverScope.cancel()
            }
        }
    }
}
