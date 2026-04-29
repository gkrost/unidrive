package org.krost.unidrive.webdav

import kotlin.test.*

/**
 * Regression tests for WebDAV folder detection (#15).
 *
 * Bug: folders were not detected when the server used uppercase namespace prefixes
 * (e.g. `<D:collection/>`) or omitted the namespace prefix entirely.
 *
 * Fix: case-insensitive matching with multiple namespace variants in parsePropfindResponse.
 * These tests exercise the fixed parsing against real-world PROPFIND response variants
 * and will FAIL if the case-insensitive detection is reverted.
 */
class WebDavFolderDetectionTest {
    private val service =
        WebDavApiService(
            WebDavConfig(baseUrl = "https://dav.example.com/webdav", username = "u", password = "p"),
        )

    // ── Folder detection: namespace variants ─────────────────────────────────

    @Test
    fun `folder detected with lowercase d-collection (Nextcloud)`() {
        val xml =
            propfindXml(
                href = "/webdav/photos/",
                resourceType = "<d:resourcetype><d:collection/></d:resourcetype>",
            )
        val entries = service.parsePropfindResponse(xml, "/")
        val folder = entries.single()
        assertTrue(folder.isFolder, "Expected folder with <d:collection/>")
    }

    @Test
    fun `folder detected with uppercase D-collection (Apache mod_dav)`() {
        val xml =
            propfindXml(
                href = "/webdav/photos/",
                resourceType = "<D:resourcetype><D:collection/></D:resourcetype>",
            )
        val entries = service.parsePropfindResponse(xml, "/")
        val folder = entries.single()
        assertTrue(folder.isFolder, "Expected folder with <D:collection/>")
    }

    @Test
    fun `folder detected with no namespace prefix (some IIS servers)`() {
        val xml =
            propfindXml(
                href = "/webdav/photos/",
                resourceType = "<resourcetype><collection/></resourcetype>",
            )
        val entries = service.parsePropfindResponse(xml, "/")
        val folder = entries.single()
        assertTrue(folder.isFolder, "Expected folder with <collection/> (no namespace)")
    }

    @Test
    fun `folder detected with mixed case namespace (edge case)`() {
        val xml =
            propfindXml(
                href = "/webdav/photos/",
                resourceType = "<D:resourcetype><D:Collection/></D:resourcetype>",
            )
        val entries = service.parsePropfindResponse(xml, "/")
        val folder = entries.single()
        assertTrue(folder.isFolder, "Expected folder with <D:Collection/> (mixed case)")
    }

    @Test
    fun `folder detected with custom namespace alias`() {
        // Some servers use a custom namespace alias like <lp1:collection/>
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<D:multistatus xmlns:D="DAV:" xmlns:lp1="DAV:">
  <D:response>
    <D:href>/webdav/docs/</D:href>
    <D:propstat>
      <D:prop>
        <lp1:resourcetype><lp1:collection/></lp1:resourcetype>
        <lp1:getcontentlength>0</lp1:getcontentlength>
      </D:prop>
      <D:status>HTTP/1.1 200 OK</D:status>
    </D:propstat>
  </D:response>
</D:multistatus>"""
        val entries = service.parsePropfindResponse(xml, "/")
        val folder = entries.single()
        assertTrue(folder.isFolder, "Expected folder with custom namespace alias <lp1:collection/>")
    }

    // ── File detection (must NOT be flagged as folder) ───────────────────────

    @Test
    fun `file not detected as folder with empty resourcetype`() {
        val xml =
            propfindXml(
                href = "/webdav/readme.txt",
                resourceType = "<D:resourcetype/>",
                size = "1024",
            )
        val entries = service.parsePropfindResponse(xml, "/")
        val file = entries.single()
        assertFalse(file.isFolder, "Empty <D:resourcetype/> must be a file, not folder")
    }

    @Test
    fun `file path and size parsed correctly alongside folder detection`() {
        val xml =
            propfindXml(
                href = "/webdav/report.pdf",
                resourceType = "<D:resourcetype></D:resourcetype>",
                size = "51200",
                lastModified = "Mon, 01 Apr 2024 12:00:00 GMT",
                etag = "\"abc123\"",
            )
        val entries = service.parsePropfindResponse(xml, "/")
        val file = entries.single()
        assertFalse(file.isFolder)
        assertEquals(51200L, file.size)
        assertEquals("/report.pdf", file.path)
    }

    // ── Href trailing-slash fallback ─────────────────────────────────────────

    @Test
    fun `folder detected via trailing slash when resourcetype is missing`() {
        // Some minimal DAV servers omit resourcetype entirely; trailing "/" is the fallback
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<D:multistatus xmlns:D="DAV:">
  <D:response>
    <D:href>/webdav/backup/</D:href>
    <D:propstat>
      <D:prop>
        <D:getcontentlength>0</D:getcontentlength>
      </D:prop>
      <D:status>HTTP/1.1 200 OK</D:status>
    </D:propstat>
  </D:response>
</D:multistatus>"""
        val entries = service.parsePropfindResponse(xml, "/")
        val folder = entries.single()
        assertTrue(folder.isFolder, "Trailing slash on href should mark as folder")
    }

    @Test
    fun `file href without trailing slash is not a folder`() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
<D:multistatus xmlns:D="DAV:">
  <D:response>
    <D:href>/webdav/data.bin</D:href>
    <D:propstat>
      <D:prop>
        <D:resourcetype></D:resourcetype>
        <D:getcontentlength>999</D:getcontentlength>
      </D:prop>
      <D:status>HTTP/1.1 200 OK</D:status>
    </D:propstat>
  </D:response>
</D:multistatus>"""
        val entries = service.parsePropfindResponse(xml, "/")
        val file = entries.single()
        assertFalse(file.isFolder)
    }

    // ── Synology DSM inline-namespace format (UD-276 regression) ────────────

    @Test
    fun `Synology DSM inline-namespace response is parsed (UD-276)`() {
        // Synology inlines namespace declarations on each D:response element.
        // The original regex <[Dd]:response> did not match this variant.
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<D:multistatus xmlns:D="DAV:" xmlns:ns0="DAV:">
<D:response xmlns:lp1="DAV:" xmlns:lp2="http://apache.org/dav/props/" xmlns:g0="DAV:">
<D:href>/home/photo.jpg</D:href>
<D:propstat>
<D:prop>
<lp1:resourcetype></lp1:resourcetype>
<g0:getcontentlength>56895</g0:getcontentlength>
<lp1:getlastmodified>Tue, 21 Apr 2026 13:18:03 GMT</lp1:getlastmodified>
<lp1:getetag>"abc123"</lp1:getetag>
</D:prop>
<D:status>HTTP/1.1 200 OK</D:status>
</D:propstat>
</D:response>
</D:multistatus>"""
        val service = WebDavApiService(WebDavConfig(baseUrl = "https://nas.local/home", username = "u", password = "p"))
        val entries = service.parsePropfindResponse(xml, "/")
        assertEquals(1, entries.size, "Synology inline-namespace response must parse to 1 entry")
        assertEquals("/photo.jpg", entries[0].path)
        assertEquals(56895L, entries[0].size)
        assertFalse(entries[0].isFolder)
    }

    // ── Helper ───────────────────────────────────────────────────────────────

    private fun propfindXml(
        href: String,
        resourceType: String,
        size: String = "0",
        lastModified: String = "Mon, 01 Apr 2024 12:00:00 GMT",
        etag: String = "\"etag\"",
    ): String =
        """<?xml version="1.0" encoding="UTF-8"?>
<D:multistatus xmlns:D="DAV:">
  <D:response>
    <D:href>$href</D:href>
    <D:propstat>
      <D:prop>
        $resourceType
        <D:getcontentlength>$size</D:getcontentlength>
        <D:getlastmodified>$lastModified</D:getlastmodified>
        <D:getetag>$etag</D:getetag>
      </D:prop>
      <D:status>HTTP/1.1 200 OK</D:status>
    </D:propstat>
  </D:response>
</D:multistatus>"""
}
