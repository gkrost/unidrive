package org.krost.unidrive.s3

import kotlin.test.*

class S3ExpiryTest {
    private val config =
        S3Config(
            bucket = "test-bucket",
            region = "us-east-1",
            endpoint = "https://s3.us-east-1.amazonaws.com",
            accessKey = "AKIAIOSFODNN7EXAMPLE",
            secretKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
        )

    private val service = S3ApiService(config)

    @Test
    fun `share converts 24 hours to 86400 seconds`() {
        val expiryHours = 24
        val expirySeconds = expiryHours * 3600
        assertEquals(86400, expirySeconds)

        val url = service.presign("test/file.txt", expirySeconds = expirySeconds)
        assertTrue(url.contains("X-Amz-Expires=86400"), "Should have 86400 seconds expiry")
    }

    @Test
    fun `share converts 1 hour to 3600 seconds`() {
        val expiryHours = 1
        val expirySeconds = expiryHours * 3600
        assertEquals(3600, expirySeconds)

        val url = service.presign("test/file.txt", expirySeconds = expirySeconds)
        assertTrue(url.contains("X-Amz-Expires=3600"), "Should have 3600 seconds expiry")
    }

    @Test
    fun `share converts 48 hours to 172800 seconds`() {
        val expiryHours = 48
        val expirySeconds = expiryHours * 3600
        assertEquals(172800, expirySeconds)

        val url = service.presign("test/file.txt", expirySeconds = expirySeconds)
        assertTrue(url.contains("X-Amz-Expires=172800"), "Should have 172800 seconds expiry")
    }

    @Test
    fun `share converts 168 hours to 604800 seconds`() {
        val expiryHours = 168
        val expirySeconds = expiryHours * 3600
        assertEquals(604800, expirySeconds)

        val url = service.presign("test/file.txt", expirySeconds = expirySeconds)
        assertTrue(url.contains("X-Amz-Expires=604800"), "Should have 604800 seconds expiry (7 days)")
    }
}
