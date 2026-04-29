package org.krost.unidrive.webdav

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WebDavApiServiceQuotaTest {
    private fun service() =
        WebDavApiService(
            WebDavConfig(
                baseUrl = "https://dav.example.com/webdav",
                username = "alice",
                password = "secret",
                tokenPath = Files.createTempDirectory("webdav-quota-test"),
            ),
        )

    @Test
    fun `parseQuotaPropfindXml returns QuotaInfo when both properties present`() {
        val xml =
            """<?xml version="1.0" encoding="UTF-8"?>
<D:multistatus xmlns:D="DAV:">
  <D:response>
    <D:href>/webdav/</D:href>
    <D:propstat>
      <D:prop>
        <D:quota-available-bytes>10737418240</D:quota-available-bytes>
        <D:quota-used-bytes>5368709120</D:quota-used-bytes>
      </D:prop>
      <D:status>HTTP/1.1 200 OK</D:status>
    </D:propstat>
  </D:response>
</D:multistatus>"""
        val q = service().parseQuotaPropfindXml(xml)!!
        // available = 10 GiB, used = 5 GiB → total = 15 GiB
        assertEquals(10_737_418_240L + 5_368_709_120L, q.total)
        assertEquals(5_368_709_120L, q.used)
        assertEquals(10_737_418_240L, q.remaining)
    }

    @Test
    fun `parseQuotaPropfindXml returns null when neither property present`() {
        val xml =
            """<?xml version="1.0" encoding="UTF-8"?>
<D:multistatus xmlns:D="DAV:">
  <D:response>
    <D:href>/webdav/</D:href>
    <D:propstat>
      <D:prop>
        <D:resourcetype><D:collection/></D:resourcetype>
      </D:prop>
      <D:status>HTTP/1.1 200 OK</D:status>
    </D:propstat>
  </D:response>
</D:multistatus>"""
        assertNull(service().parseQuotaPropfindXml(xml))
    }

    @Test
    fun `parseQuotaPropfindXml handles zero available bytes`() {
        val xml =
            """<D:multistatus xmlns:D="DAV:">
  <D:response>
    <D:propstat>
      <D:prop>
        <D:quota-available-bytes>0</D:quota-available-bytes>
        <D:quota-used-bytes>2147483648</D:quota-used-bytes>
      </D:prop>
    </D:propstat>
  </D:response>
</D:multistatus>"""
        val q = service().parseQuotaPropfindXml(xml)!!
        assertEquals(0L, q.remaining)
        assertEquals(2_147_483_648L, q.used)
        assertEquals(2_147_483_648L, q.total)
    }

    @Test
    fun `parseQuotaPropfindXml handles available-only (used absent)`() {
        val xml =
            """<D:multistatus xmlns:D="DAV:">
  <D:response>
    <D:propstat>
      <D:prop>
        <D:quota-available-bytes>4294967296</D:quota-available-bytes>
      </D:prop>
    </D:propstat>
  </D:response>
</D:multistatus>"""
        val q = service().parseQuotaPropfindXml(xml)!!
        assertEquals(4_294_967_296L, q.remaining)
        assertEquals(0L, q.used)
        assertEquals(4_294_967_296L, q.total)
    }
}
