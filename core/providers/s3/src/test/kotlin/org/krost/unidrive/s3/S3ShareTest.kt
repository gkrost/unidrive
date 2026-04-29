package org.krost.unidrive.s3

import kotlin.test.*

class S3ShareTest {
    private val service =
        S3ApiService(
            S3Config(
                bucket = "my-bucket",
                region = "eu-central-1",
                endpoint = "https://s3.eu-central-1.amazonaws.com",
                accessKey = "AKIAIOSFODNN7EXAMPLE",
                secretKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
            ),
        )

    @Test
    fun `presign generates URL with expected query params`() {
        val url = service.presign("documents/report.pdf", expirySeconds = 3600)

        assertTrue(
            url.startsWith("https://s3.eu-central-1.amazonaws.com/my-bucket/documents/report.pdf?"),
            "URL should be presigned endpoint",
        )
        assertTrue(url.contains("X-Amz-Algorithm=AWS4-HMAC-SHA256"), "Should have algorithm")
        assertTrue(url.contains("X-Amz-Credential="), "Should have credential")
        assertTrue(url.contains("X-Amz-Expires=3600"), "Should have expiry")
        assertTrue(url.contains("X-Amz-SignedHeaders=host"), "Should have signed headers")
        assertTrue(url.contains("X-Amz-Signature="), "Should have signature")
    }

    @Test
    fun `presign uses custom expiry seconds`() {
        val url = service.presign("test.txt", expirySeconds = 86400)

        assertTrue(
            url.contains("X-Amz-Expires=86400"),
            "Custom expiry should be reflected in URL",
        )
    }

    @Test
    fun `presign generates different signatures for different expiries`() {
        val url1 = service.presign("file.txt", expirySeconds = 3600)
        val url2 = service.presign("file.txt", expirySeconds = 7200)

        assertNotEquals(url1, url2, "Different expiries should produce different signatures")
    }

    @Test
    fun `presign handles paths with special characters`() {
        val url = service.presign("folder with spaces/file & more.txt", expirySeconds = 3600)

        assertFalse(url.contains(" "), "Spaces should be encoded")
        assertTrue(url.contains("%20"), "Spaces should be percent-encoded")
    }
}
