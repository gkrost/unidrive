package org.krost.unidrive.s3

import kotlin.test.*

class SigV4SignerTest {
    @Test
    fun `EMPTY_BODY_HASH is the sha256 of empty bytes`() {
        // SHA-256("") = e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            SigV4Signer.EMPTY_BODY_HASH,
        )
    }

    @Test
    fun `sha256Hex of known input`() {
        // SHA-256("hello") = 2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            SigV4Signer.sha256Hex("hello".toByteArray()),
        )
    }

    @Test
    fun `sign returns required headers`() {
        val headers =
            SigV4Signer.sign(
                method = "GET",
                url = "https://mybucket.s3.amazonaws.com/test.txt",
                region = "us-east-1",
                accessKey = "AKIAIOSFODNN7EXAMPLE",
                secretKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
                bodyHash = SigV4Signer.EMPTY_BODY_HASH,
            )
        assertTrue("Authorization" in headers)
        assertTrue("X-Amz-Date" in headers)
        assertTrue("X-Amz-Content-Sha256" in headers)
    }

    @Test
    fun `Authorization header starts with AWS4-HMAC-SHA256`() {
        val headers =
            SigV4Signer.sign(
                method = "PUT",
                url = "https://bucket.s3.eu-central-1.amazonaws.com/file.bin",
                region = "eu-central-1",
                accessKey = "AKID",
                secretKey = "SECRET",
                bodyHash = SigV4Signer.EMPTY_BODY_HASH,
            )
        assertTrue(headers["Authorization"]!!.startsWith("AWS4-HMAC-SHA256 Credential="))
    }

    @Test
    fun `extra headers are included in signed headers`() {
        val headers =
            SigV4Signer.sign(
                method = "PUT",
                url = "https://bucket.s3.amazonaws.com/dest.bin",
                region = "us-east-1",
                accessKey = "AKID",
                secretKey = "SECRET",
                bodyHash = SigV4Signer.EMPTY_BODY_HASH,
                extraHeaders = mapOf("x-amz-copy-source" to "/sourcebucket/source.bin"),
            )
        val auth = headers["Authorization"]!!
        assertTrue("x-amz-copy-source" in auth)
        assertEquals("/sourcebucket/source.bin", headers["x-amz-copy-source"])
    }

    @Test
    fun `X-Amz-Date has expected format`() {
        val headers =
            SigV4Signer.sign(
                method = "GET",
                url = "https://s3.amazonaws.com/bucket/",
                region = "us-east-1",
                accessKey = "AKID",
                secretKey = "SECRET",
                bodyHash = SigV4Signer.EMPTY_BODY_HASH,
            )
        // Format: 20240115T123456Z
        val date = headers["X-Amz-Date"]!!
        assertTrue(date.matches(Regex("""\d{8}T\d{6}Z""")), "Unexpected date format: $date")
    }

    @Test
    fun `different secrets produce different signatures`() {
        val h1 = SigV4Signer.sign("GET", "https://bucket.s3.amazonaws.com/f", "us-east-1", "AK", "SECRET1", SigV4Signer.EMPTY_BODY_HASH)
        val h2 = SigV4Signer.sign("GET", "https://bucket.s3.amazonaws.com/f", "us-east-1", "AK", "SECRET2", SigV4Signer.EMPTY_BODY_HASH)
        assertNotEquals(h1["Authorization"], h2["Authorization"])
    }
}
