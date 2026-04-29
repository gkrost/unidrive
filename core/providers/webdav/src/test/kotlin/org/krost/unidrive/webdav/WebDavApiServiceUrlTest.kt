package org.krost.unidrive.webdav

import kotlin.test.*

/**
 * Unit tests for [WebDavApiService] URL-building helpers.
 * These don't require a live WebDAV server.
 */
class WebDavApiServiceUrlTest {
    private fun service(baseUrl: String) =
        WebDavApiService(
            WebDavConfig(baseUrl = baseUrl, username = "user", password = "pass"),
        )

    @Test
    fun `resourceUrl appends virtual path to base`() {
        val svc = service("https://dav.example.com/remote.php/dav/files/user")
        assertEquals(
            "https://dav.example.com/remote.php/dav/files/user/docs/file.txt",
            svc.resourceUrl("/docs/file.txt"),
        )
    }

    @Test
    fun `resourceUrl with trailing slash on base`() {
        val svc = service("https://dav.example.com/webdav/")
        assertEquals(
            "https://dav.example.com/webdav/file.bin",
            svc.resourceUrl("/file.bin"),
        )
    }

    @Test
    fun `resourceUrl with empty virtual path returns root with slash`() {
        val svc = service("https://dav.example.com/webdav")
        assertEquals(
            "https://dav.example.com/webdav/",
            svc.resourceUrl(""),
        )
    }

    @Test
    fun `resourceUrl with root slash returns root with slash`() {
        val svc = service("https://dav.example.com/webdav")
        assertEquals(
            "https://dav.example.com/webdav/",
            svc.resourceUrl("/"),
        )
    }

    @Test
    fun `resourceUrl with nested path`() {
        val svc = service("https://nc.example.com/remote.php/dav/files/alice")
        assertEquals(
            "https://nc.example.com/remote.php/dav/files/alice/folder/sub/doc.pdf",
            svc.resourceUrl("/folder/sub/doc.pdf"),
        )
    }

    @Test
    fun `resourceUrl encodes single space in filename`() {
        val svc = service("https://dav.example.com/webdav")
        assertEquals(
            "https://dav.example.com/webdav/my%20file.txt",
            svc.resourceUrl("/my file.txt"),
        )
    }

    @Test
    fun `resourceUrl encodes multiple consecutive spaces in filename`() {
        val svc = service("https://dav.example.com/webdav")
        assertEquals(
            "https://dav.example.com/webdav/file%20with%20%20multiple%20%20%20spaces.txt",
            svc.resourceUrl("/file with  multiple   spaces.txt"),
        )
    }

    @Test
    fun `resourceUrl encodes spaces in nested path segments independently`() {
        val svc = service("https://dav.example.com/webdav")
        assertEquals(
            "https://dav.example.com/webdav/my%20folder/sub%20dir/my%20%20file.txt",
            svc.resourceUrl("/my folder/sub dir/my  file.txt"),
        )
    }

    @Test
    fun `resourceUrl encodes special characters in path segments`() {
        val svc = service("https://dav.example.com/webdav")
        assertEquals(
            "https://dav.example.com/webdav/notes%20%26%20docs/file%20%2B%20extra.txt",
            svc.resourceUrl("/notes & docs/file + extra.txt"),
        )
    }

    @Test
    fun `resourceUrl encodes non-ASCII characters`() {
        val svc = service("https://dav.example.com/webdav")
        assertEquals(
            "https://dav.example.com/webdav/Sch%C3%B6ne%20Gr%C3%BC%C3%9Fe/%E6%96%87%E6%9B%B8.txt",
            svc.resourceUrl("/Schöne Grüße/文書.txt"),
        )
    }

    @Test
    fun `resourceUrl preserves slashes as path separators`() {
        val svc = service("https://dav.example.com/webdav")
        val url = svc.resourceUrl("/a/b/c/d.txt")
        assertEquals("https://dav.example.com/webdav/a/b/c/d.txt", url)
        assertFalse(url.contains("%2F"), "slashes must not be percent-encoded")
    }
}
