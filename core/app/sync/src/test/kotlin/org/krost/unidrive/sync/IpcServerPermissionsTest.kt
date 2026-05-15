package org.krost.unidrive.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

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
    // parent dir 0700 (tempSocketDir()). Skip on non-POSIX filesystems (Windows).
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
}
