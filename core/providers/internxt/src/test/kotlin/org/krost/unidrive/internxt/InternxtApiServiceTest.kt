package org.krost.unidrive.internxt

import kotlinx.serialization.json.Json
import org.krost.unidrive.internxt.model.Mirror
import org.krost.unidrive.internxt.model.StartUploadResponse
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals

class InternxtApiServiceTest {
    @Test
    fun `deserialize mirror response`() {
        val json = Json { ignoreUnknownKeys = true }
        val raw = """[{
            "index": 0,
            "hash": "aabbcc",
            "size": 1024,
            "parity": false,
            "token": "tok123",
            "healthy": true,
            "farmer": {
                "userAgent": "ua",
                "protocol": "https",
                "address": "1.2.3.4",
                "port": 8080,
                "nodeID": "nid",
                "lastSeen": "2026-01-01T00:00:00.000Z"
            },
            "url": "https://1.2.3.4:8080/shards/aabbcc?token=tok123",
            "operation": "PULL"
        }]"""
        val mirrors = json.decodeFromString<List<Mirror>>(raw)
        assertEquals(1, mirrors.size)
        assertEquals("https://1.2.3.4:8080/shards/aabbcc?token=tok123", mirrors[0].url)
        assertEquals("aabbcc", mirrors[0].hash)
    }

    @Test
    fun `deserialize start upload response`() {
        val json = Json { ignoreUnknownKeys = true }
        val raw = """{"uploads":[{"index":0,"uuid":"uuid-1","url":"https://storage.example.com/put"}]}"""
        val resp = json.decodeFromString<StartUploadResponse>(raw)
        assertEquals(1, resp.uploads.size)
        assertEquals("uuid-1", resp.uploads[0].uuid)
        assertEquals("https://storage.example.com/put", resp.uploads[0].url)
    }

    @Test
    fun `delta cursor rewinds 6 hours`() {
        val cursor = "2026-03-29T18:30:00.000Z"
        val rewound = InternxtProvider.rewindCursor(cursor)
        assertEquals("2026-03-29T12:30:00Z", rewound)
    }

    @Test
    fun `delta cursor rewind crosses midnight`() {
        val cursor = "2026-03-29T03:00:00.000Z"
        val rewound = InternxtProvider.rewindCursor(cursor)
        assertEquals("2026-03-28T21:00:00Z", rewound)
    }

    @Test
    fun `split path into segments`() {
        val segments = InternxtProvider.pathSegments("/Documents/Work/report.pdf")
        assertEquals(listOf("Documents", "Work", "report.pdf"), segments)
    }

    @Test
    fun `split root path returns empty`() {
        val segments = InternxtProvider.pathSegments("/")
        assertEquals(emptyList(), segments)
    }

    @Test
    fun `bridge auth header is correctly formed`() {
        val user = "user@example.com"
        val userId = "secret"
        // sha256("secret") = 2bb80d537b1da3e38bd30361aa855686bde0eacd7162fef6a25fe97bf527a25b
        val header = InternxtCrypto.bridgeAuthHeader(user, userId)
        val expected =
            Base64.getEncoder().encodeToString(
                "$user:2bb80d537b1da3e38bd30361aa855686bde0eacd7162fef6a25fe97bf527a25b".toByteArray(),
            )
        assertEquals("Basic $expected", header)
    }
}
