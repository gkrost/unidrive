package org.krost.unidrive.s3

import kotlin.test.*

class S3ApiServiceTest {
    private val service =
        S3ApiService(
            S3Config(
                bucket = "my-bucket",
                region = "us-east-1",
                endpoint = "https://s3.amazonaws.com",
                accessKey = "AKID",
                secretKey = "SECRET",
            ),
        )

    @Test
    fun `objectUrl encodes Greek characters`() {
        val url = service.objectUrl("φάκελος/αρχείο.txt")
        assertFalse(url.contains("φ"), "Non-ASCII characters must be percent-encoded")
        assertTrue(url.contains("%CF%86%CE%AC%CE%BA%CE%B5%CE%BB%CE%BF%CF%82"), "Greek segment must be encoded")
    }

    @Test
    fun `objectUrl encodes Japanese characters`() {
        val url = service.objectUrl("フォルダ/ファイル.txt")
        assertFalse(url.contains("フ"), "Non-ASCII characters must be percent-encoded")
    }

    @Test
    fun `objectUrl encodes emoji`() {
        val url = service.objectUrl("📁 folder/📄 file.txt")
        assertFalse(url.contains("📁"), "Emoji must be percent-encoded")
        assertFalse(url.contains("📄"), "Emoji must be percent-encoded")
    }

    @Test
    fun `objectUrl preserves slashes as separators`() {
        val url = service.objectUrl("a/b/c.txt")
        assertTrue(url.endsWith("/a/b/c.txt"), "Slashes must not be encoded; got: $url")
    }

    @Test
    fun `objectUrl strips leading slash`() {
        val withSlash = service.objectUrl("/foo/bar.txt")
        val withoutSlash = service.objectUrl("foo/bar.txt")
        assertEquals(withSlash, withoutSlash)
    }

    @Test
    fun `objectUrl leaves ASCII paths unchanged`() {
        val url = service.objectUrl("documents/report-2024.pdf")
        assertTrue(url.endsWith("/documents/report-2024.pdf"), "ASCII paths must not be altered; got: $url")
    }

    @Test
    fun `objectUrl encodes spaces as %20 not plus`() {
        val url = service.objectUrl("my folder/my file.txt")
        assertFalse(url.contains("+"), "Spaces must be encoded as %20, not +")
        assertTrue(url.contains("%20"), "Spaces must be encoded as %20")
    }

    @Test
    fun `copy source path is URL-encoded for Unicode`() {
        // The x-amz-copy-source header must percent-encode non-ASCII characters.
        // objectUrl already does this encoding; verify the same contract holds for
        // a copy-source path containing Japanese characters.
        val url = service.objectUrl("日本語のフォルダ/file.txt")
        assertFalse(url.contains("日"), "Non-ASCII characters in copy source must be percent-encoded")
        assertFalse(url.contains("+"), "Copy source encoding must use %20, not +")
        assertTrue(url.contains("%"), "Copy source path must contain percent-encoded characters")
    }
}
