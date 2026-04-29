package org.krost.unidrive.sftp

import kotlin.test.*

/**
 * Unit tests for [SftpApiService] path-translation helpers.
 * These don't require a live SFTP server.
 */
class SftpApiServicePathTest {
    private fun service(remotePath: String = "") =
        SftpApiService(
            SftpConfig(host = "localhost", remotePath = remotePath),
        )

    // serverPath

    @Test
    fun `serverPath with empty base and root virtual`() {
        val svc = service("")
        assertEquals(".", svc.serverPath(""))
        assertEquals(".", svc.serverPath("/"))
    }

    @Test
    fun `serverPath with empty base prepends nothing`() {
        val svc = service("")
        assertEquals("foo/bar.txt", svc.serverPath("/foo/bar.txt"))
    }

    @Test
    fun `serverPath with base prepends base`() {
        val svc = service("/data/sync")
        assertEquals("/data/sync/foo/bar.txt", svc.serverPath("/foo/bar.txt"))
    }

    @Test
    fun `serverPath trims trailing slash from base`() {
        val svc = service("/data/sync/")
        assertEquals("/data/sync/file.bin", svc.serverPath("/file.bin"))
    }

    // serverToVirtual

    @Test
    fun `serverToVirtual strips base prefix`() {
        val svc = service("/data/sync")
        assertEquals("/foo/bar.txt", svc.serverToVirtual("/data/sync/foo/bar.txt"))
    }

    @Test
    fun `serverToVirtual with no base returns path as-is with leading slash`() {
        val svc = service("")
        assertEquals("/foo/bar.txt", svc.serverToVirtual("foo/bar.txt"))
    }

    @Test
    fun `serverToVirtual round-trip`() {
        val svc = service("/home/user/files")
        val virtual = "/docs/report.pdf"
        assertEquals(virtual, svc.serverToVirtual(svc.serverPath(virtual)))
    }
}
