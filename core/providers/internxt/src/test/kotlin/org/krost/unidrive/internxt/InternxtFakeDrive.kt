package org.krost.unidrive.internxt

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.krost.unidrive.CloudItem
import org.krost.unidrive.ScanContext
import org.krost.unidrive.ScanProgress
import org.krost.unidrive.internxt.model.InternxtCredentials
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

// A fake Internxt drive for the tests that run the provider's full listing end to end: the status filter, the keyset
// cursors (a cursor is good for the status it was issued for only), a cursor that can be walled with HTTP 408 for the
// filtered query, and a folder meta that answers HTTP 200 for a trashed folder, as the live server does. The engine's
// staging stands beside it as a test double.

/** A provider over [engine], signed in with test credentials, as the provider tests build it. */
internal fun internxtProviderOn(engine: MockEngine): InternxtProvider {
    val provider = InternxtProvider()
    val auth = InternxtProvider::class.java.getDeclaredField("authService").also { it.isAccessible = true }.get(provider)
    val credsField = auth.javaClass.getDeclaredField("credentials").also { it.isAccessible = true }
    val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"exp":9999999999}""".toByteArray())
    credsField.set(
        auth,
        InternxtCredentials(
            jwt = "header.$payload.signature",
            mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
            rootFolderId = "root",
            email = "test@example.invalid",
            bridgeUser = "bridge-user",
            bridgeUserId = "bridge-secret",
            bucket = "6928426c1a2316b856c9ab81",
        ),
    )
    val api = InternxtProvider::class.java.getDeclaredField("api").also { it.isAccessible = true }.get(provider)
    val clientField = InternxtApiService::class.java.getDeclaredField("httpClient").also { it.isAccessible = true }
    (clientField.get(api) as? HttpClient)?.close()
    clientField.set(api, HttpClient(engine))
    return provider
}

// ---- the fake drive --------------------------------------------------------------------------------------------

private val fakeJsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
private val fakeMillis = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
private val fakeMicros = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC)

internal fun fakeAt(time: String) = Instant.parse("2026-07-28T${time}Z")

internal data class FakeFolder(
    val uuid: String,
    val name: String,
    val parent: String,
    val updatedAt: Instant,
    val status: String = "EXISTS",
)

internal data class FakeFile(
    val uuid: String,
    val name: String,
    val folder: String,
    val updatedAt: Instant,
    val status: String = "EXISTS",
)

private class FakeCursorBody(
    val updatedAt: Instant,
    val uuid: String,
    val status: String?,
)

private fun fakeFolderJson(
    row: FakeFolder,
    withStatus: Boolean = true,
): String {
    val status = if (withStatus) ",\"status\":\"${row.status}\",\"deleted\":${row.status == "TRASHED"},\"removed\":${row.status == "DELETED"}" else ""
    return "{\"uuid\":\"${row.uuid}\",\"plainName\":\"${row.name}\",\"parentUuid\":\"${row.parent}\"$status," +
        "\"modificationTime\":\"2026-07-01T10:00:00.000Z\",\"updatedAt\":\"${fakeMillis.format(row.updatedAt)}\"}"
}

private fun fakeFileJson(row: FakeFile) =
    "{\"uuid\":\"${row.uuid}\",\"plainName\":\"${row.name}\",\"type\":\"txt\",\"size\":\"1\",\"folderUuid\":\"${row.folder}\"," +
        "\"status\":\"${row.status}\",\"modificationTime\":\"2026-07-01T10:00:00.000Z\",\"updatedAt\":\"${fakeMillis.format(row.updatedAt)}\"}"

/**
 * The drive. [wall]: a folders request with the status filter after the cursor of this uuid is answered 408, however
 * often it comes (the unfiltered form of the query answers). [metaDelayMs]: each folder meta takes this long.
 * [metaGone]: folders whose meta answers 404. [metaOnly]: folders that only their meta answers.
 */
internal class FakeDrive(
    val folders: List<FakeFolder>,
    val files: List<FakeFile>,
    val pageSize: Int = 2,
    val wall: String? = null,
    val metaDelayMs: Long = 0,
    val metaGone: Set<String> = emptySet(),
    // Folders that only their meta knows: the listings do not return them (an unchanged ancestor of an incremental delta).
    val metaOnly: List<FakeFolder> = emptyList(),
) {
    val metaRequests = CopyOnWriteArrayList<String>()
    private val inFlight = AtomicInteger()
    val maxInFlight = AtomicInteger()

    private fun cursorFor(
        updatedAt: Instant,
        uuid: String,
        status: String?,
    ): String {
        val fields = listOfNotNull("\"updatedAt\":\"${fakeMicros.format(updatedAt)}\"", "\"uuid\":\"$uuid\"", status?.let { "\"status\":\"$it\"" })
        return Base64.getEncoder().encodeToString("{${fields.joinToString(",")}}".toByteArray())
    }

    private fun decodeCursor(token: String): FakeCursorBody? =
        runCatching {
            val fields = Json.parseToJsonElement(String(Base64.getDecoder().decode(token))).jsonObject
            FakeCursorBody(
                Instant.parse(fields.getValue("updatedAt").jsonPrimitive.content),
                fields.getValue("uuid").jsonPrimitive.content,
                fields["status"]?.jsonPrimitive?.contentOrNull,
            )
        }.getOrNull()

    private fun <T> page(
        rows: List<T>,
        updatedAt: (T) -> Instant,
        uuid: (T) -> String,
        status: String?,
        cursor: FakeCursorBody?,
        since: String?,
    ): Pair<List<T>, String?> {
        val ordered = rows.sortedWith(compareBy({ updatedAt(it) }, { uuid(it) }))
        val after =
            if (cursor != null) {
                ordered.filter { r -> updatedAt(r).isAfter(cursor.updatedAt) || (updatedAt(r) == cursor.updatedAt && uuid(r) > cursor.uuid) }
            } else {
                ordered.filter { updatedAt(it).isAfter(Instant.parse(since!!)) }
            }
        val taken = after.take(pageSize)
        val next = if (after.size > pageSize) cursorFor(updatedAt(taken.last()), uuid(taken.last()), status) else null
        return taken to next
    }

    val engine =
        MockEngine { request ->
            val path = request.url.encodedPath
            val params = request.url.parameters
            val status = params["status"]
            val token = params["cursor"]
            val cursor = token?.let(::decodeCursor)
            fun respondJson(body: String) = respond(body, HttpStatusCode.OK, fakeJsonHeaders)
            when {
                path.endsWith("/drive/folders/sync") -> {
                    if (token != null && cursor == null) return@MockEngine respond("{}", HttpStatusCode.BadRequest, fakeJsonHeaders)
                    if (cursor != null && cursor.status != status) return@MockEngine respond("{}", HttpStatusCode.BadRequest, fakeJsonHeaders)
                    if (status == "EXISTS" && cursor != null && cursor.uuid == wall) {
                        return@MockEngine respond("""{"statusCode":408,"message":"Request Timeout"}""", HttpStatusCode.RequestTimeout, fakeJsonHeaders)
                    }
                    val listed = folders.filter { status == null || it.status == status }
                    val (taken, next) = page(listed, { it.updatedAt }, { it.uuid }, status, cursor, params["updatedAt"])
                    val rows = taken.joinToString(",") { fakeFolderJson(it) }
                    respondJson("""{"folders":[$rows],"nextCursor":${next?.let { "\"$it\"" } ?: "null"}}""")
                }
                path.endsWith("/drive/files/sync") -> {
                    val (taken, next) = page(files, { it.updatedAt }, { it.uuid }, status, cursor, params["updatedAt"])
                    respondJson("""{"files":[${taken.joinToString(",") { fakeFileJson(it) }}],"nextCursor":${next?.let { "\"$it\"" } ?: "null"}}""")
                }
                // The offset listing (an incremental delta asks for status ALL since a cursor): everything in one page, then nothing.
                path.endsWith("/drive/folders") || path.endsWith("/drive/files") -> {
                    if ((params["offset"]?.toIntOrNull() ?: 0) > 0) return@MockEngine respondJson("[]")
                    val since = params["updatedAt"]?.let(Instant::parse)
                    fun listed(status: String): Boolean = params["status"] == null || params["status"] == "ALL" || params["status"] == status
                    val rows =
                        if (path.endsWith("/drive/folders")) {
                            folders.filter { listed(it.status) && (since == null || it.updatedAt.isAfter(since)) }.map { fakeFolderJson(it) }
                        } else {
                            files.filter { listed(it.status) && (since == null || it.updatedAt.isAfter(since)) }.map { fakeFileJson(it) }
                        }
                    respondJson(rows.joinToString(",", "[", "]"))
                }
                path.endsWith("/meta") -> {
                    val uuid = path.substringBeforeLast("/meta").substringAfterLast('/')
                    metaRequests += uuid
                    maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
                    try {
                        if (metaDelayMs > 0) delay(metaDelayMs)
                    } finally {
                        inFlight.decrementAndGet()
                    }
                    // A trashed folder is answered with its row and HTTP 200, as the live server does.
                    val row = (folders + metaOnly).lastOrNull { it.uuid == uuid }
                    if (row == null || uuid in metaGone) respond("""{"statusCode":404}""", HttpStatusCode.NotFound, fakeJsonHeaders) else respondJson(fakeFolderJson(row))
                }
                else -> error("unexpected request: ${request.url}")
            }
        }
}

internal class StagingKilled : RuntimeException("killed by the test")

/** The engine's staging as a test double: the rows and every marker, handed to the next attempt as `loadStagedItems` returns them. */
internal class FakeStaging(
    var dieWhen: ((String) -> Boolean)? = null,
) {
    val rows = ConcurrentHashMap<String, CloudItem>()
    val markers: MutableList<String> = Collections.synchronizedList(mutableListOf())

    val persistPage: suspend (List<CloudItem>, String) -> Unit = { items, marker ->
        items.forEach { rows[it.id] = it }
        markers += marker
        if (dieWhen?.invoke(marker) == true) throw StagingKilled()
    }

    fun context(onProgress: ((ScanProgress) -> Unit)? = null): ScanContext {
        // What the engine hands back keeps the cloud identity only: no path, no creation time, no mime type.
        val staged = rows.values.sortedBy { it.id }.map { it.copy(path = "/${it.name}", created = null, mimeType = null) }
        return ScanContext(markers.lastOrNull(), staged, persistPage, onProgress = onProgress)
    }
}

// The account: two live folders on the first page (the cursor after a2 is walled), then a trashed tree with live
// content under it, a deleted folder, and a live folder whose parent is trashed. Files hang below all of them.
//
//   a1, a2 (live, top)          T1 "OldStuff" (trashed, top)   D1 "Gone" (deleted, top)
//                               T2 "Deep" (trashed, in T1)
//                               L2 "Kept" (live, in T2)
internal fun fakeAccountFolders() =
    listOf(
        FakeFolder("a1", "Docs", "root", fakeAt("19:30:00")),
        FakeFolder("a2", "Pics", "root", fakeAt("19:31:00")),
        FakeFolder("T1", "OldStuff", "root", fakeAt("19:40:00"), "TRASHED"),
        FakeFolder("T2", "Deep", "T1", fakeAt("19:41:00"), "TRASHED"),
        FakeFolder("D1", "Gone", "root", fakeAt("19:42:00"), "DELETED"),
        FakeFolder("L2", "Kept", "T2", fakeAt("19:50:00")),
    )

internal fun fakeAccountFiles() =
    listOf(
        FakeFile("f1", "readme", "a1", fakeAt("19:10:00")),
        FakeFile("f2", "note", "T1", fakeAt("19:20:00")),
        FakeFile("f3", "keep", "L2", fakeAt("19:30:00")),
    )
