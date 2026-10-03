package org.krost.unidrive.internxt

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.krost.unidrive.CloudItem
import org.krost.unidrive.UnidriveJson
import org.krost.unidrive.internxt.model.FilesSyncResponse
import org.krost.unidrive.internxt.model.FoldersSyncResponse
import org.krost.unidrive.internxt.model.InternxtFile
import org.krost.unidrive.internxt.model.InternxtFolder
import org.krost.unidrive.internxt.model.SyncPage
import java.time.Instant

// The cursor listing of a full enumeration: `GET /files/sync` and `GET /folders/sync` page through the
// whole account by a keyset (updated_at, uuid) instead of an offset. The first request of a stream names
// `updatedAt`, every later one only the `nextCursor` of the page before it, until a page has none. Live,
// a page of 1,000 folders takes about a second and a page of 1,000 files 2.5-6 s, where an offset page
// takes 25-56 s and the gateway cuts it at about two minutes: the offset listing cannot enumerate a large
// drive, the cursor listing can, and it can resume from the cursor of its last page.

private val log = org.slf4j.LoggerFactory.getLogger("org.krost.unidrive.internxt.CursorListing")

/** The first page of a stream asks for everything changed since this instant: the beginning of time. */
internal const val SYNC_LISTING_START: String = "1970-01-01T00:00:00.000Z"

/** A full enumeration needs the live items only. A cursor is only good for the status it was issued for. */
internal const val SYNC_LISTING_STATUS: String = "EXISTS"

/** The statuses by which a server says it has no such endpoint. Only on the first page of a stream. */
private val ENDPOINT_MISSING_STATUSES = setOf(404, 405, 501)

/** The first page names [updatedAt]; a page that continues names the [cursor] alone, with the same status and limit. */
internal fun syncQueryParams(
    updatedAt: String?,
    cursor: String?,
    status: String,
    limit: Int,
): Map<String, String> {
    val params = linkedMapOf("status" to status, "limit" to limit.toString())
    if (cursor != null) {
        params["cursor"] = cursor
    } else if (updatedAt != null) {
        params["updatedAt"] = updatedAt
    }
    return params
}

// Tolerant: unknown keys are ignored and a null where the model has a default takes the default. An item
// that cannot be decoded at all (no uuid) fails the page, because a listing that silently loses an item
// would let the engine take its absence for a deletion.
private val syncJson = Json(from = UnidriveJson) { coerceInputValues = true }

internal fun parseFilesSyncPage(body: String): SyncPage<InternxtFile> {
    val page = syncJson.decodeFromString<FilesSyncResponse>(body)
    return SyncPage(page.files.orEmpty(), page.nextCursor?.takeIf { it.isNotBlank() })
}

internal fun parseFoldersSyncPage(body: String): SyncPage<InternxtFolder> {
    val page = syncJson.decodeFromString<FoldersSyncResponse>(body)
    return SyncPage(page.folders.orEmpty(), page.nextCursor?.takeIf { it.isNotBlank() })
}

/** Where one stream stands: the cursor to ask for next, or finished. Neither: not started. */
@Serializable
internal data class StreamPosition(
    val cursor: String? = null,
    val done: Boolean = false,
)

/**
 * What the page boundary persists as the scan marker: the strategy, the status the cursors belong to, the
 * instant the enumeration began, and the position of both streams. The two streams are strictly sequential
 * inside themselves and independent of each other, so each has its own cursor.
 */
@Serializable
internal data class CursorMarker(
    val strategy: String,
    val status: String,
    val startedAt: String,
    val folders: StreamPosition,
    val files: StreamPosition,
)

internal const val CURSOR_MARKER_STRATEGY: String = "cursor"

// JSON, not a delimited string: a cursor is opaque and may hold any character. The marker of the offset
// listing is `files|folders` offsets and is no JSON object, so neither format can be read as the other.
private val markerJson = Json { encodeDefaults = true }

internal fun cursorMarker(
    startedAt: String,
    folders: StreamPosition,
    files: StreamPosition,
): String {
    val marker = CursorMarker(CURSOR_MARKER_STRATEGY, SYNC_LISTING_STATUS, startedAt, folders, files)
    return markerJson.encodeToString(CursorMarker.serializer(), marker)
}

/**
 * The marker the cursor listing wrote, or null when [raw] is anything else: absent, the offset listing's
 * `files|folders`, another strategy's, a cursor of another status, or damaged. Null means "not resumable by
 * this strategy": the listing starts from scratch. A half-readable marker is never trusted.
 */
internal fun decodeCursorMarker(raw: String?): CursorMarker? {
    if (raw.isNullOrBlank()) return null
    val marker =
        try {
            markerJson.decodeFromString<CursorMarker>(raw)
        } catch (_: IllegalArgumentException) {
            return null
        }
    if (marker.strategy != CURSOR_MARKER_STRATEGY || marker.status != SYNC_LISTING_STATUS) return null
    if (runCatching { Instant.parse(marker.startedAt) }.isFailure) return null
    if (marker.folders.cursor?.isBlank() == true || marker.files.cursor?.isBlank() == true) return null
    return marker
}

// A staged row records no strategy, and the staging of a scan survives a switch of strategy (the scan id is
// kept). The cursor listing therefore marks the rows it stages and resumes from those only: rows an offset
// listing left behind belong to another listing and may describe items that are gone by now.
internal const val CURSOR_STAGED_TAG: String = "cursor-listing"

/** What a resumed cursor listing starts from: where both streams stand, and the rows its earlier pages staged. */
internal class CursorResume(
    val marker: CursorMarker,
    val rows: List<CloudItem>,
)

/**
 * The resume point of the stored scan, or null when this strategy cannot resume it and must list from the start:
 * the marker is not a cursor marker (see [decodeCursorMarker]), or it claims progress while none of the rows this
 * strategy staged is there. Continuing from cursors whose rows are gone would leave out everything those rows
 * described, and a listing with items missing looks complete to the engine, which then reads their absence as
 * deletions. [staged] is every row the scan holds, whoever staged it.
 */
internal fun cursorResumeOf(
    marker: String?,
    staged: List<CloudItem>,
): CursorResume? {
    val decoded = decodeCursorMarker(marker) ?: return null
    val mine = staged.filter { it.hash == CURSOR_STAGED_TAG }
    val claimsProgress = decoded.folders != StreamPosition() || decoded.files != StreamPosition()
    if (claimsProgress && mine.isEmpty()) return null
    return CursorResume(decoded, mine)
}

/** How a stream ended: [COMPLETE] when the server said there is no next page, [CUT_SHORT] when a guard stopped it. */
internal enum class StreamEnd { COMPLETE, CUT_SHORT }

/** The first page of [stream] was answered "no such endpoint": this server does not serve the cursor listing. */
internal class CursorListingUnavailable(
    val stream: String,
    cause: InternxtApiException,
) : Exception("The cursor listing of $stream is not served here (HTTP ${cause.statusCode})", cause)

/**
 * Pages one stream to its end, strictly one request at a time (a cursor names the page before it).
 *
 * [fetch] gets the cursor to ask with, null for the first page. [onPage] gets every page that is to be kept,
 * with the position the stream stands at once the page is stored; it is called before the next request, so a
 * persisted position never runs ahead of its rows.
 *
 * The server ends a stream by a page without `nextCursor`. Two guards keep a misbehaving server from looping
 * the client: a page with items whose `nextCursor` was already asked for ends the stream (its rows are kept,
 * its position is not advanced), and a second empty page in a row ends it (the first one is tolerated: an
 * empty page that carries a cursor is not the end). A stream a guard ended is [StreamEnd.CUT_SHORT]: nobody
 * saw its end, so the listing must not be taken for complete.
 *
 * [CursorListingUnavailable] is thrown for a 404, 405 or 501 on the first page only. Every other failure,
 * and the same statuses on a later page, propagates unchanged: the stream stands where its last page left it.
 */
internal suspend fun <T> pageCursorStream(
    stream: String,
    start: StreamPosition,
    fetch: suspend (cursor: String?) -> SyncPage<T>,
    onPage: suspend (items: List<T>, position: StreamPosition) -> Unit,
): StreamEnd {
    if (start.done) return StreamEnd.COMPLETE
    var position = start
    val asked = HashSet<String>()
    var emptyInARow = 0
    while (true) {
        val sent = position.cursor
        if (sent != null) asked.add(sent)
        val page =
            try {
                fetch(sent)
            } catch (e: InternxtApiException) {
                if (sent == null && e.statusCode in ENDPOINT_MISSING_STATUSES) throw CursorListingUnavailable(stream, e)
                throw e
            }
        val next = page.nextCursor
        log.debug("Scanning {}: {} items on the page, next cursor {}", stream, page.items.size, if (next == null) "none" else "present")
        when {
            next == null -> {
                onPage(page.items, StreamPosition(done = true))
                return StreamEnd.COMPLETE
            }
            page.items.isEmpty() -> {
                emptyInARow++
                if (emptyInARow >= 2) {
                    log.warn("The {} listing returned a second empty page in a row: ending the stream, the listing is incomplete", stream)
                    return StreamEnd.CUT_SHORT
                }
                position = StreamPosition(cursor = next)
                onPage(page.items, position)
            }
            next in asked -> {
                log.warn("The {} listing returned a next cursor it was already asked for: ending the stream, the listing is incomplete", stream)
                onPage(page.items, position)
                return StreamEnd.CUT_SHORT
            }
            else -> {
                emptyInARow = 0
                position = StreamPosition(cursor = next)
                onPage(page.items, position)
            }
        }
    }
}
