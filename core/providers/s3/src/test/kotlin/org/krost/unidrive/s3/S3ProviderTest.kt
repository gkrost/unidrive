package org.krost.unidrive.s3

import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.CapabilityResult
import org.krost.unidrive.CloudItem
import org.krost.unidrive.ConfigurationException
import org.krost.unidrive.PermanentDownloadFailureException
import org.krost.unidrive.ProviderException
import org.krost.unidrive.ProviderFactory
import org.krost.unidrive.RemoteConflictException
import org.krost.unidrive.ScanContext
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.ServiceLoader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The provider against [FakeS3]: listing, uploads (single and multipart, resumed), conditional writes, copies, deletes, links. */
class S3ProviderTest {
    private val dir = Files.createTempDirectory("s3-provider")
    private val mib = 1024L * 1024

    private fun provider(
        fake: FakeS3,
        endpoint: String = "http://localhost:9000",
        threshold: Long = S3Config.MULTIPART_THRESHOLD,
        minPart: Long = S3Config.MIN_PART_SIZE,
    ) = S3Provider(
        S3Config("b", "us-east-1", endpoint, "AK", "SK", dir, multipartThreshold = threshold, minPartSize = minPart),
        fake.engine,
    )

    private fun local(
        name: String,
        bytes: ByteArray,
    ) = dir.resolve(name).also {
        Files.write(it, bytes)
        Files.setLastModifiedTime(it, FileTime.from(Instant.parse("2026-02-03T04:05:06.123Z")))
    }

    @Test
    fun `the provider is found by the service loader`() {
        assertTrue(ServiceLoader.load(ProviderFactory::class.java).any { it.id == "s3" })
    }

    @Test
    fun `delta lists the whole bucket with folders from markers and from keys, page by page`() =
        runTest {
            val fake = FakeS3(pageSize = 2)
            fake.put("a.txt", "1")
            fake.put("docs/", "")
            fake.put("docs/deep/x.txt", "22")
            fake.put("z.txt", "333")
            val progress = mutableListOf<Int>()

            val page = provider(fake).delta(null, onPageProgress = { progress += it })

            assertEquals(listOf("/docs", "/docs/deep", "/a.txt", "/docs/deep/x.txt", "/z.txt"), page.items.map { it.path })
            assertEquals(listOf(true, true, false, false, false), page.items.map { it.isFolder })
            assertFalse(page.hasMore)
            assertTrue(provider(fake).deltaIsFullListing)
            assertEquals(2, progress.size)
            val x = page.items.single { it.path == "/docs/deep/x.txt" }
            assertEquals("docs/deep/x.txt", x.id)
            assertEquals(FakeS3.md5Hex("22".toByteArray()), x.hash)
            assertEquals(2, x.size)
        }

    @Test
    fun `a resumed listing starts after the marker and keeps the staged items`() =
        runTest {
            val fake = FakeS3(pageSize = 1)
            fake.put("a.txt", "1")
            fake.put("b.txt", "2")
            fake.put("c.txt", "3")
            val persisted = mutableListOf<String>()
            val staged = CloudItem("a.txt", "", "placeholder", 1, false, null, null, null, null)
            val context = ScanContext("a.txt", listOf(staged), { _, marker -> persisted += marker })

            val page = provider(fake).delta(null, scanContext = context)

            assertEquals(listOf("/a.txt", "/b.txt", "/c.txt"), page.items.map { it.path })
            assertEquals("a.txt", fake.requests.first { it.query["list-type"] == "2" }.query["start-after"])
            assertEquals(listOf("b.txt", "c.txt"), persisted)
        }

    @Test
    fun `children are the files and the folders one level down, not the folder's own marker`() =
        runTest {
            val fake = FakeS3()
            fake.put("docs/", "")
            fake.put("docs/a.txt", "1")
            fake.put("docs/sub/b.txt", "2")

            val children = provider(fake).listChildren("/docs")

            assertEquals(listOf("/docs/a.txt" to false, "/docs/sub" to true), children.map { it.path to it.isFolder })
        }

    @Test
    fun `a small upload is one signed PUT with the mtime as rclone writes it, and the item S3 then reports`() =
        runTest {
            val fake = FakeS3()

            val item = provider(fake).upload(local("g.txt", "hello".toByteArray()), "/dir/Grüße ~*.txt")

            val put = fake.requests.single { it.method == HttpMethod.Put }
            assertEquals("/b/dir/Gr%C3%BC%C3%9Fe%20~%2A.txt", put.encodedPath)
            assertEquals("1770091506.123000000", put.headers["x-amz-meta-mtime"])
            assertEquals("hello", fake.text("dir/Grüße ~*.txt"))
            assertEquals("/dir/Grüße ~*.txt", item.path)
            assertEquals(FakeS3.md5Hex("hello".toByteArray()), item.hash)
            assertEquals(fake.objects.getValue("dir/Grüße ~*.txt").lastModified, item.modified)
        }

    @Test
    fun `a big upload goes in streamed parts with their MD5 and completes in order`() =
        runTest {
            val fake = FakeS3()
            val bytes = ByteArray((2.5 * mib).toInt()) { (it % 253).toByte() }

            val progress = mutableListOf<Long>()
            provider(fake, threshold = mib, minPart = mib).upload(local("big.bin", bytes), "/big.bin") { done, _ -> progress += done }

            assertEquals(3, fake.count { it.query.containsKey("partNumber") })
            assertTrue(fake.objects.getValue("big.bin").bytes!!.contentEquals(bytes))
            assertEquals(listOf(mib, 2 * mib, bytes.size.toLong(), bytes.size.toLong()), progress)
            assertEquals("1770091506.123000000", fake.objects.getValue("big.bin").meta["mtime"])
            assertEquals(0, fake.openUploads)
            assertTrue(Files.list(dir.resolve("s3-uploads")).use { it.count() } == 0L)
        }

    @Test
    fun `a multipart upload cut off half-way resumes with only the missing parts`() =
        runTest {
            val fake = FakeS3()
            val bytes = ByteArray((2.5 * mib).toInt()) { (it % 251).toByte() }
            val file = local("big.bin", bytes)
            val provider = provider(fake, threshold = mib, minPart = mib)
            fake.failures += Triple({ r: FakeS3.Request -> r.query["partNumber"] == "3" }, 400, "RequestTimeout")

            assertFailsWith<S3Exception> { provider.upload(file, "/big.bin") }
            assertEquals(1, fake.openUploads)
            provider.upload(file, "/big.bin")

            assertEquals(1, fake.count { it.query.containsKey("uploads") }, "one upload, resumed")
            assertEquals(listOf("1", "2", "3", "3"), fake.requests.filter { it.query.containsKey("partNumber") }.map { it.query["partNumber"] })
            assertTrue(fake.objects.getValue("big.bin").bytes!!.contentEquals(bytes))
        }

    @Test
    fun `deleting a file that was never uploaded aborts the upload it left open`() =
        runTest {
            val fake = FakeS3()
            val file = local("big.bin", ByteArray((1.5 * mib).toInt()))
            val provider = provider(fake, threshold = mib, minPart = mib)
            fake.failures += Triple({ r: FakeS3.Request -> r.query["partNumber"] == "2" }, 400, "RequestTimeout")
            assertFailsWith<S3Exception> { provider.upload(file, "/big.bin") }

            provider.discardStagedUpload(file.toString())

            assertEquals(0, fake.openUploads)
        }

    @Test
    fun `parts are sized to the vendor's part limit`() {
        val aws = S3Provider(S3Config("b", "eu-west-1", "https://s3.eu-west-1.amazonaws.com", "AK", "SK", dir))
        val scaleway = S3Provider(S3Config("b", "fr-par", "https://s3.fr-par.scw.cloud", "AK", "SK", dir))
        val gib = 1024 * mib

        assertEquals(16 * mib, aws.partSizeFor(100 * gib))
        assertEquals(103 * mib, scaleway.partSizeFor(100 * gib))
        assertFailsWith<ProviderException> { scaleway.partSizeFor(5_001 * gib) }
    }

    @Test
    fun `on AWS a guarded write carries If-Match and a 412 is a conflict`() =
        runTest {
            val fake = FakeS3()
            fake.put("a.txt", "old")
            val etag = fake.objects.getValue("a.txt").etag
            val aws = provider(fake, endpoint = "https://s3.eu-west-1.amazonaws.com")

            aws.upload(local("a.txt", "new".toByteArray()), "/a.txt", ifMatchETag = etag)
            assertEquals("\"$etag\"", fake.requests.last { it.method == HttpMethod.Put }.headers["if-match"])
            assertEquals(HttpMethod.Put, fake.requests.first().method, "no HEAD comparison where If-Match does the work")

            assertFailsWith<RemoteConflictException> { aws.upload(local("a.txt", "newer".toByteArray()), "/a.txt", ifMatchETag = etag) }
            assertEquals("new", fake.text("a.txt"))
        }

    @Test
    fun `elsewhere a guarded write compares the ETag first and sends nothing on a change`() =
        runTest {
            val fake = FakeS3()
            fake.put("a.txt", "theirs")

            assertFailsWith<RemoteConflictException> { provider(fake).upload(local("a.txt", "mine".toByteArray()), "/a.txt", ifMatchETag = "stale") }
            assertEquals(0, fake.count { it.method == HttpMethod.Put })
            assertNull(fake.requests.firstOrNull { it.headers.containsKey("if-match") })
        }

    @Test
    fun `a folder delete removes every key below it in one batch, a guarded file delete checks the ETag`() =
        runTest {
            val fake = FakeS3()
            fake.put("docs/", "")
            fake.put("docs/a.txt", "1")
            fake.put("docs/sub/b.txt", "2")
            fake.put("keep.txt", "3")
            val provider = provider(fake)

            provider.delete("/docs")
            assertEquals(listOf("keep.txt"), fake.objects.keys.toList())
            val batch = fake.requests.single { it.query.containsKey("delete") }
            assertTrue(batch.headers.containsKey("content-md5"))

            assertFailsWith<RemoteConflictException> { provider.delete("/keep.txt", ifMatchETag = "stale") }
            provider.delete("/keep.txt", ifMatchETag = fake.objects.getValue("keep.txt").etag)
            assertTrue(fake.objects.isEmpty())
        }

    @Test
    fun `a move copies with the metadata and deletes the source`() =
        runTest {
            val fake = FakeS3()
            fake.put("a b.txt", "x", meta = mapOf("mtime" to "1.5"))

            val moved = provider(fake).move("/a b.txt", "/dir/c.txt")

            val copy = fake.requests.single { it.headers.containsKey("x-amz-copy-source") }
            assertEquals("/b/a%20b.txt", copy.headers["x-amz-copy-source"])
            assertEquals("COPY", copy.headers["x-amz-metadata-directive"])
            assertEquals(listOf("dir/c.txt"), fake.objects.keys.toList())
            assertEquals("1.5", fake.objects.getValue("dir/c.txt").meta["mtime"])
            assertEquals("/dir/c.txt", moved.path)
        }

    @Test
    fun `an object above 5 GiB is moved by part copies on any vendor`() =
        runTest {
            val fake = FakeS3()
            val size = 6L * 1024 * mib
            fake.putSized("huge.bin", size)

            provider(fake).move("/huge.bin", "/moved.bin")

            val ranges =
                fake.requests.filter { it.headers.containsKey("x-amz-copy-source-range") }.map {
                    it.headers.getValue("x-amz-copy-source-range").removePrefix("bytes=").split("-").let { (a, b) -> a.toLong()..b.toLong() }
                }
            assertEquals(0L, ranges.first().first)
            assertEquals(size - 1, ranges.last().last)
            ranges.zipWithNext().forEach { (a, b) -> assertEquals(a.last + 1, b.first) }
            assertTrue(ranges.all { it.last - it.first + 1 <= S3Config.MAX_PART_SIZE })
            assertEquals(size, fake.objects.getValue("moved.bin").size)
            assertFalse("huge.bin" in fake.objects)
        }

    @Test
    fun `a download is staged, and an object that is gone is a permanent failure`() =
        runTest {
            val fake = FakeS3()
            fake.put("a.txt", "content")
            val target = dir.resolve("out/a.txt")

            assertEquals(7, provider(fake).download("/a.txt", target))
            assertEquals("content", Files.readString(target))
            assertFailsWith<PermanentDownloadFailureException> { provider(fake).download("/gone.txt", dir.resolve("gone.txt")) }
        }

    @Test
    fun `a 503 SlowDown is waited out and retried`() =
        runTest {
            val fake = FakeS3()
            fake.put("a.txt", "x")
            fake.failures += Triple({ r: FakeS3.Request -> r.method == HttpMethod.Head }, 503, "SlowDown")

            assertEquals("/a.txt", provider(fake).getMetadata("/a.txt").path)
            assertEquals(2, fake.count { it.method == HttpMethod.Head })
        }

    @Test
    fun `a link is a presigned GET capped at seven days and never carries a password`() =
        runTest {
            val fake = FakeS3()
            val provider = provider(fake)

            val url = (provider.share("/a b.txt", expiryHours = 24 * 30) as CapabilityResult.Success).value

            assertTrue(url.startsWith("http://localhost:9000/b/a%20b.txt?X-Amz-Algorithm=AWS4-HMAC-SHA256"), url)
            assertTrue("X-Amz-Expires=604800" in url && "X-Amz-Signature=" in url, url)
            assertFailsWith<ProviderException> { provider.share("/a.txt", password = "pw") }
        }

    @Test
    fun `refused keys fail the sign-in check`() =
        runTest {
            val fake = FakeS3()
            fake.failures += Triple({ _: FakeS3.Request -> true }, 403, "InvalidAccessKeyId")
            val provider = provider(fake)

            assertFailsWith<AuthenticationException> { provider.authenticate() }
            assertFalse(provider.isAuthenticated)
        }

    @Test
    fun `the factory needs bucket and keys, defaults to AWS in the region, and refuses a non-URL endpoint`() {
        val factory = S3ProviderFactory()
        val base = mapOf("bucket" to "b", "access_key_id" to "AK", "secret_access_key" to "SK")

        assertFailsWith<ConfigurationException> { factory.create(base - "bucket", dir) }
        val aws = factory.create(base + ("region" to "eu-central-1"), dir) as S3Provider
        assertEquals("https://s3.eu-central-1.amazonaws.com", aws.config.endpoint)
        assertTrue(aws.config.vendor.conditionalWrites)
        assertEquals(1_000, (factory.create(base + ("endpoint" to "https://s3.fr-par.scw.cloud"), dir) as S3Provider).config.vendor.maxParts)
        assertFailsWith<ConfigurationException> { factory.create(base + ("endpoint" to "s3.example.com"), dir) }
    }
}
