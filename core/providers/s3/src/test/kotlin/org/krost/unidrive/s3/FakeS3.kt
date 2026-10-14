package org.krost.unidrive.s3

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.decodeURLPart
import io.ktor.http.headersOf
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * An in-memory S3 bucket behind a MockEngine, just enough of the API for the provider: objects with metadata, ListObjectsV2
 * (prefix, delimiter, start-after, continuation), HEAD, conditional PUT, multipart (with ListParts and part copies), copy,
 * delete and DeleteObjects. Every request must carry a SigV4 Authorization; [requests] records them all.
 */
class FakeS3(
    private val bucket: String = "b",
    private val pageSize: Int = 1000,
) {
    class Obj(
        val bytes: ByteArray?,
        val size: Long,
        val etag: String,
        val meta: Map<String, String>,
        val lastModified: Instant,
    )

    class Request(
        val method: HttpMethod,
        val key: String?,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: ByteArray,
        val encodedPath: String,
    )

    val objects = sortedMapOf<String, Obj>()
    val requests = mutableListOf<Request>()
    private val uploads = mutableMapOf<String, Pair<String, MutableMap<Int, Obj>>>()
    private val uploadMeta = mutableMapOf<String, Map<String, String>>()
    private var tick = Instant.parse("2026-01-01T00:00:00Z")
    private var uploadCounter = 0

    /** Answers the next request [matching] with [status] and an S3 error [code] instead of serving it. */
    val failures = mutableListOf<Triple<(Request) -> Boolean, Int, String>>()

    fun put(
        key: String,
        content: String,
        meta: Map<String, String> = emptyMap(),
    ) {
        val bytes = content.toByteArray()
        objects[key] = Obj(bytes, bytes.size.toLong(), md5Hex(bytes), meta, nextTime())
    }

    /** An object S3 reports with [size] but whose bytes the fake does not hold (for copies above 5 GiB). */
    fun putSized(
        key: String,
        size: Long,
    ) {
        objects[key] = Obj(null, size, "big-1", emptyMap(), nextTime())
    }

    fun text(key: String): String? = objects[key]?.bytes?.toString(Charsets.UTF_8)

    fun count(predicate: (Request) -> Boolean): Int = synchronized(requests) { requests.count(predicate) }

    val engine =
        MockEngine { request ->
            val segments = request.url.encodedPath.removePrefix("/").split("/", limit = 2)
            check(segments[0] == bucket) { "path-style request expected, got ${request.url.encodedPath}" }
            val key = segments.getOrNull(1)?.takeIf { it.isNotEmpty() }?.decodeURLPart()
            val auth = request.headers["Authorization"] ?: ""
            check(auth.startsWith("AWS4-HMAC-SHA256 Credential=") && request.headers["x-amz-date"] != null) { "unsigned request" }
            val r =
                Request(
                    request.method,
                    key,
                    request.url.parameters.entries().associate { it.key to it.value.first() },
                    request.headers.entries().associate { it.key.lowercase() to it.value.first() },
                    request.body.toByteArray(),
                    request.url.encodedPath,
                )
            synchronized(requests) { requests += r }
            val failure = failures.firstOrNull { it.first(r) }
            if (failure != null) {
                failures.remove(failure)
                return@MockEngine error(failure.second, failure.third)
            }
            serve(request, r)
        }

    private fun MockRequestHandleScope.serve(
        request: HttpRequestData,
        r: Request,
    ): HttpResponseData {
        val q = r.query
        return when {
            r.method == HttpMethod.Get && r.key == null && q["list-type"] == "2" -> list(q)
            r.method == HttpMethod.Post && r.key == null && "delete" in q -> deleteMany(r.body)
            "uploads" in q || "uploadId" in q -> multipart(r)
            r.method == HttpMethod.Put && r.headers["x-amz-copy-source"] != null -> {
                val source = objects[copySourceKey(r)] ?: return error(404, "NoSuchKey")
                objects[r.key!!] = Obj(source.bytes, source.size, source.etag, source.meta, nextTime())
                xml("<CopyObjectResult><ETag>\"${source.etag}\"</ETag></CopyObjectResult>")
            }
            r.method == HttpMethod.Put -> {
                val current = objects[r.key!!]
                val ifMatch = r.headers["if-match"]?.trim('"')
                if (ifMatch != null && current?.etag != ifMatch) return error(if (current == null) 404 else 412, "PreconditionFailed")
                objects[r.key] = Obj(r.body, r.body.size.toLong(), md5Hex(r.body), meta(r), nextTime())
                respond("", HttpStatusCode.OK, headersOf("ETag", "\"${objects.getValue(r.key).etag}\""))
            }
            r.method == HttpMethod.Head -> {
                val obj = objects[r.key] ?: return respond("", HttpStatusCode.NotFound)
                val headers =
                    listOf(
                        "ETag" to listOf("\"${obj.etag}\""),
                        "Content-Length" to listOf(obj.size.toString()),
                        "Last-Modified" to listOf(DateTimeFormatter.RFC_1123_DATE_TIME.format(obj.lastModified.atZone(ZoneOffset.UTC))),
                    ) + obj.meta.map { "x-amz-meta-${it.key}" to listOf(it.value) }
                respond("", HttpStatusCode.OK, headersOf(*headers.toTypedArray()))
            }
            r.method == HttpMethod.Get -> {
                val obj = objects[r.key] ?: return error(404, "NoSuchKey")
                respond(obj.bytes!!, HttpStatusCode.OK, headersOf("Content-Length", obj.size.toString()))
            }
            r.method == HttpMethod.Delete -> {
                objects.remove(r.key)
                respond("", HttpStatusCode.NoContent)
            }
            else -> error(400, "Unsupported ${request.method.value}")
        }
    }

    private fun MockRequestHandleScope.multipart(r: Request): HttpResponseData {
        val q = r.query
        return when {
            r.method == HttpMethod.Post && "uploads" in q -> {
                val id = "up${++uploadCounter}"
                uploads[id] = r.key!! to mutableMapOf()
                uploadMeta[id] = meta(r)
                xml("<InitiateMultipartUploadResult><UploadId>$id</UploadId></InitiateMultipartUploadResult>")
            }
            r.method == HttpMethod.Put -> part(r)
            r.method == HttpMethod.Get -> listParts(q.getValue("uploadId"))
            r.method == HttpMethod.Post -> complete(r)
            else -> {
                uploads.remove(q.getValue("uploadId"))
                respond("", HttpStatusCode.NoContent)
            }
        }
    }

    private fun MockRequestHandleScope.list(q: Map<String, String>): HttpResponseData {
        val prefix = q["prefix"] ?: ""
        val delimiter = q["delimiter"]
        val after = q["continuation-token"] ?: q["start-after"] ?: ""
        val max = minOf(q["max-keys"]?.toInt() ?: 1000, pageSize)
        val entries = mutableListOf<Pair<String, Obj?>>()
        val seenPrefixes = mutableSetOf<String>()
        for ((key, obj) in objects) {
            if (!key.startsWith(prefix) || key <= after) continue
            val rest = key.removePrefix(prefix)
            if (delimiter != null && rest.contains(delimiter)) {
                val common = prefix + rest.substringBefore(delimiter) + delimiter
                if (seenPrefixes.add(common)) entries += common to null
            } else {
                entries += key to obj
            }
        }
        val page = entries.take(max)
        val truncated = entries.size > max
        val body =
            buildString {
                append("<ListBucketResult><IsTruncated>$truncated</IsTruncated>")
                if (truncated) append("<NextContinuationToken>${esc(page.last().first)}</NextContinuationToken>")
                page.forEach { (key, obj) ->
                    if (obj == null) {
                        append("<CommonPrefixes><Prefix>${esc(key)}</Prefix></CommonPrefixes>")
                    } else {
                        append("<Contents><Key>${esc(key)}</Key><LastModified>${obj.lastModified}</LastModified>")
                        append("<ETag>&quot;${obj.etag}&quot;</ETag><Size>${obj.size}</Size></Contents>")
                    }
                }
                append("</ListBucketResult>")
            }
        return xml(body)
    }

    private fun MockRequestHandleScope.part(r: Request): HttpResponseData {
        val (key, parts) = uploads[r.query.getValue("uploadId")] ?: return error(404, "NoSuchUpload")
        check(key == r.key)
        val number = r.query.getValue("partNumber").toInt()
        val copySource = r.headers["x-amz-copy-source"]
        if (copySource != null) {
            val range = r.headers.getValue("x-amz-copy-source-range").removePrefix("bytes=").split("-").map { it.toLong() }
            parts[number] = Obj(null, range[1] - range[0] + 1, "copy$number", emptyMap(), tick)
            return xml("<CopyPartResult><ETag>\"copy$number\"</ETag></CopyPartResult>")
        }
        val md5 = java.util.Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(r.body))
        if (r.headers["content-md5"] != md5) return error(400, "BadDigest")
        parts[number] = Obj(r.body, r.body.size.toLong(), md5Hex(r.body), emptyMap(), tick)
        return respond("", HttpStatusCode.OK, headersOf("ETag", "\"${md5Hex(r.body)}\""))
    }

    private fun MockRequestHandleScope.listParts(uploadId: String): HttpResponseData {
        val (_, parts) = uploads[uploadId] ?: return error(404, "NoSuchUpload")
        return xml(
            "<ListPartsResult><IsTruncated>false</IsTruncated>" +
                parts.entries.joinToString("") { "<Part><PartNumber>${it.key}</PartNumber><ETag>\"${it.value.etag}\"</ETag><Size>${it.value.size}</Size></Part>" } +
                "</ListPartsResult>",
        )
    }

    private fun MockRequestHandleScope.complete(r: Request): HttpResponseData {
        val id = r.query.getValue("uploadId")
        val (key, parts) = uploads[id] ?: return error(404, "NoSuchUpload")
        val ifMatch = r.headers["if-match"]?.trim('"')
        if (ifMatch != null && objects[key]?.etag != ifMatch) return error(412, "PreconditionFailed")
        val numbers = Regex("<PartNumber>(\\d+)</PartNumber>").findAll(r.body.toString(Charsets.UTF_8)).map { it.groupValues[1].toInt() }.toList()
        check(numbers == numbers.sorted() && numbers.toSet() == parts.keys) { "complete lists $numbers, held ${parts.keys}" }
        val bytes = if (parts.values.all { it.bytes != null }) numbers.map { parts.getValue(it).bytes!! }.reduce { a, b -> a + b } else null
        val size = numbers.sumOf { parts.getValue(it).size }
        objects[key] = Obj(bytes, size, "multi-${numbers.size}", uploadMeta[id] ?: emptyMap(), nextTime())
        uploads.remove(id)
        return xml("<CompleteMultipartUploadResult><ETag>\"multi-${numbers.size}\"</ETag></CompleteMultipartUploadResult>")
    }

    private fun MockRequestHandleScope.deleteMany(body: ByteArray): HttpResponseData {
        val keys = Regex("<Key>(.*?)</Key>").findAll(body.toString(Charsets.UTF_8)).map { unesc(it.groupValues[1]) }.toList()
        keys.forEach { objects.remove(it) }
        return xml("<DeleteResult></DeleteResult>")
    }

    val openUploads: Int get() = uploads.size

    private fun copySourceKey(r: Request): String = r.headers.getValue("x-amz-copy-source").removePrefix("/$bucket/").decodeURLPart()

    private fun meta(r: Request): Map<String, String> =
        r.headers.filterKeys { it.startsWith("x-amz-meta-") }.mapKeys { it.key.removePrefix("x-amz-meta-") }

    private fun nextTime(): Instant = tick.also { tick = tick.plusSeconds(1) }

    private fun MockRequestHandleScope.xml(body: String) = respond(body, HttpStatusCode.OK, headersOf("Content-Type", "application/xml"))

    private fun MockRequestHandleScope.error(
        status: Int,
        code: String,
    ) = respond("<Error><Code>$code</Code><Message>$code</Message></Error>", HttpStatusCode.fromValue(status), headersOf("Content-Type", "application/xml"))

    companion object {
        fun md5Hex(bytes: ByteArray): String = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

        private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        private fun unesc(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
    }
}
