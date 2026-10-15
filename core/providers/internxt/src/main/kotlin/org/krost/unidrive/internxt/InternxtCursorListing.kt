package org.krost.unidrive.internxt

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.krost.unidrive.CloudItem
import org.krost.unidrive.ProviderException
import org.krost.unidrive.internxt.model.FilesSyncResponse
import org.krost.unidrive.internxt.model.FoldersSyncResponse
import org.krost.unidrive.internxt.model.InternxtFile
import org.krost.unidrive.internxt.model.InternxtFolder
import org.krost.unidrive.internxt.model.InternxtJson
import org.krost.unidrive.internxt.model.SyncPage
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Base64

// The cursor listing of a full enumeration: `GET /files/sync` and `GET /folders/sync` page through the
// whole account by a keyset (updated_at, uuid) instead of an offset. The first request of a stream names
// `updatedAt`, every later one only the `nextCursor` of the page before it, until a page has none. Live,
// a page of 1,000 folders takes about a second and a page of 1,000 files 2.5-6 s, where an offset page
// takes 25-56 s and the gateway cuts it at about two minutes: the offset listing cannot enumerate a large
// drive, the cursor listing can, and it can resume from the cursor of its last page.
//
// A cursor is a property of the query: resuming it repeats the identical request. Live, the server answered
// one folder cursor with HTTP 408 after a constant ~115 s on every attempt, so a scan that resumed that
// cursor could never end. When a stream fails twice in a row on the same cursor with the same kind of
// failure, it leaves the cursor and continues from an `updatedAt` a little before it, without the status
// filter, the way the official client got past it, and drops the rows that are not live itself
// ([pageStreamWithFallback]).

private val log = org.slf4j.LoggerFactory.getLogger("org.krost.unidrive.internxt.CursorListing")

/** The first page of a stream asks for everything changed since this instant: the beginning of time. */
internal const val SYNC_LISTING_START: String = "1970-01-01T00:00:00.000Z"

/** A full enumeration needs the live items only. A cursor is only good for the status it was issued for. */
internal const val SYNC_LISTING_STATUS: String = "EXISTS"

/** The statuses by which a server says it has no such endpoint. Only on the first page of a stream. */
private val ENDPOINT_MISSING_STATUSES = setOf(404, 405, 501)

/** The first page names [updatedAt]; a page that continues names the [cursor] alone, with the same status and limit. A null [status] asks for every status. */
internal fun syncQueryParams(
    updatedAt: String?,
    cursor: String?,
    status: String?,
    limit: Int,
): Map<String, String> {
    val params = linkedMapOf<String, String>()
    if (status != null) params["status"] = status
    params["limit"] = limit.toString()
    if (cursor != null) {
        params["cursor"] = cursor
    } else if (updatedAt != null) {
        params["updatedAt"] = updatedAt
    }
    return params
}

/**
 * A row of a page asked for without a status filter has no `status`. The models default a missing status to
 * `EXISTS`, which is right for a page that was filtered to the live items and wrong here: nothing says whether
 * the row is live, and a guess would stage trashed or deleted items as live.
 */
internal class SyncRowWithoutStatus(
    val stream: String,
    val uuid: String?,
) : ProviderException("A row of the unfiltered $stream listing (uuid ${uuid ?: "unknown"}) has no status, so it cannot be told whether it is live")

// Every row of an unfiltered page names its status, as a string: that is the field the rows are filtered by.
private fun requireRowStatus(
    stream: String,
    body: String,
) {
    val rows = InternxtJson.parseToJsonElement(body).jsonObject[stream]?.jsonArray.orEmpty()
    for (row in rows) {
        val fields: JsonObject = row.jsonObject
        val status = fields["status"] as? JsonPrimitive
        if (status == null || !status.isString) {
            throw SyncRowWithoutStatus(stream, fields["uuid"]?.jsonPrimitive?.contentOrNull)
        }
    }
}

// A page is read with [InternxtJson]. An item that cannot be decoded at all (no uuid) fails the page, because a
// listing that silently loses an item would let the engine take its absence for a deletion.
/** [rowsCarryStatus]: the page was asked for without a status filter, so each of its rows must say what it is. */
internal fun parseFilesSyncPage(
    body: String,
    rowsCarryStatus: Boolean = false,
): SyncPage<InternxtFile> {
    if (rowsCarryStatus) requireRowStatus("files", body)
    val page = InternxtJson.decodeFromString<FilesSyncResponse>(body)
    return SyncPage(page.files.orEmpty(), page.nextCursor?.takeIf { it.isNotBlank() })
}

internal fun parseFoldersSyncPage(
    body: String,
    rowsCarryStatus: Boolean = false,
): SyncPage<InternxtFolder> {
    if (rowsCarryStatus) requireRowStatus("folders", body)
    val page = InternxtJson.decodeFromString<FoldersSyncResponse>(body)
    return SyncPage(page.folders.orEmpty(), page.nextCursor?.takeIf { it.isNotBlank() })
}

/** How often in a row a stream may fail on one cursor with one kind of failure before it leaves the cursor. */
internal const val FAILURES_BEFORE_FALLBACK: Int = 2

/** A stream that leaves a cursor continues this long before the cursor's own instant: the official client's margin. */
internal const val FALLBACK_REWIND_SECONDS: Long = 120

/**
 * The kind of failure that can be a property of the cursor asked for, null for any other (a 401, a 429, a 404, a
 * connection that never formed): the server's own timeout, and the server-side family (live, the gateway answers 524
 * at its own cap, and a read cut at our watchdog surfaces as 503).
 */
internal fun failureKindOf(statusCode: Int): String? =
    when {
        statusCode == 408 -> "408"
        statusCode >= 500 -> "5xx"
        else -> null
    }

/** The last failure of a stream, kept in the scan marker so that two attempts, or a restart between them, count as in a row. */
@Serializable
internal data class StreamFailure(
    val cursor: String,
    val kind: String,
    val count: Int,
)

/**
 * Where one stream stands: the cursor to ask for next, or finished. Neither: not started.
 *
 * [unfiltered]: the stream left a cursor it could not get an answer for and no longer asks for the live items only.
 * Its cursors carry no status, they are sent without one, and its pages hold rows of every status. Until its first
 * page is stored it has no cursor, and [since] is the `updatedAt` that page is asked for.
 *
 * [failure]: the last failure of this stream on [cursor], if any.
 */
@Serializable
internal data class StreamPosition(
    val cursor: String? = null,
    val done: Boolean = false,
    val unfiltered: Boolean = false,
    val since: String? = null,
    val failure: StreamFailure? = null,
)

/** This position after a request on its cursor failed with [kind]: the count goes up when it is the same failure again. */
internal fun StreamPosition.failedWith(kind: String): StreamPosition {
    val failed = cursor ?: return this
    val again = failure?.takeIf { it.cursor == failed && it.kind == kind }
    return copy(failure = StreamFailure(failed, kind, (again?.count ?: 0) + 1))
}

private val QUERY_INSTANT: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

/** The `updatedAt` of a [cursor] (the server issues base64 of a JSON object with `updatedAt`, `uuid` and, when filtered, `status`), null when it cannot be read. */
internal fun instantOfCursor(cursor: String): Instant? =
    runCatching {
        val bytes = runCatching { Base64.getDecoder().decode(cursor) }.getOrElse { Base64.getUrlDecoder().decode(cursor) }
        val fields = InternxtJson.parseToJsonElement(String(bytes, Charsets.UTF_8)).jsonObject
        fields["updatedAt"]?.jsonPrimitive?.contentOrNull?.let { Instant.parse(it) }
    }.getOrNull()

/**
 * The position from which a stream that gave up [cursor] continues: a little before the instant of the cursor, no
 * status filter. The rows between that instant and the cursor were stored already and arrive again, which the
 * listing absorbs by uuid. Null when the cursor cannot be read, and the stream then keeps resuming it.
 */
internal fun unfilteredPositionAfter(cursor: String): StreamPosition? {
    val instant = instantOfCursor(cursor) ?: return null
    val since = instant.minusSeconds(FALLBACK_REWIND_SECONDS).coerceAtLeast(Instant.EPOCH).truncatedTo(ChronoUnit.MILLIS)
    return StreamPosition(unfiltered = true, since = QUERY_INSTANT.format(since))
}

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
    if (!marker.folders.isReadable() || !marker.files.isReadable()) return null
    return marker
}

// A position that names a blank cursor, a stream that left its cursor without saying from when, or a failure record
// that is no record is damage: it is not guessed at.
private fun StreamPosition.isReadable(): Boolean {
    if (cursor?.isBlank() == true) return false
    if (awaitsFirstUnfilteredPage() && !hasStartingInstant()) return false
    return failure == null || (failure.cursor.isNotBlank() && failure.kind.isNotBlank() && failure.count >= 1)
}

private fun StreamPosition.awaitsFirstUnfilteredPage(): Boolean = unfiltered && !done && cursor == null

private fun StreamPosition.hasStartingInstant(): Boolean = since?.let { runCatching { Instant.parse(it) }.isSuccess } == true

// A staged row records no strategy, and the staging of a scan survives a switch of strategy (the scan id is
// kept). The cursor listing therefore marks the rows it stages and resumes from those only: rows an offset
// listing left behind belong to another listing and may describe items that are gone by now.
internal const val CURSOR_STAGED_TAG: String = "cursor-listing"

// A trashed folder that an unfiltered page returned is kept as an ancestor-only record: it is no item of the listing
// (its row is not live), but a live item can hang below it, and its row answers the folder's path without a request.
// It is staged beside the live rows, in the same transaction as the page, and tagged apart so that it is never taken for
// a live row. The marker is the same as without them: a scan staged before these rows existed resumes as it did,
// the ancestors it did not keep being asked for when the paths are resolved.
internal const val CURSOR_ANCESTOR_TAG: String = "cursor-listing|ancestor"

/**
 * What a resumed cursor listing starts from: where both streams stand, the rows its earlier pages staged, and the
 * ancestor-only records ([CURSOR_ANCESTOR_TAG]) they kept.
 */
internal class CursorResume(
    val marker: CursorMarker,
    val rows: List<CloudItem>,
    val ancestors: List<CloudItem> = emptyList(),
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
    return CursorResume(decoded, mine, staged.filter { it.hash == CURSOR_ANCESTOR_TAG })
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

/** One request of a stream: the first page names [updatedAt], a later one the [cursor] alone. A null [status] asks for every status. */
internal data class SyncRequest(
    val updatedAt: String?,
    val cursor: String?,
    val status: String?,
)

private fun requestFor(
    position: StreamPosition,
    cursor: String?,
): SyncRequest =
    if (position.unfiltered) {
        SyncRequest(updatedAt = position.since.takeIf { cursor == null }, cursor = cursor, status = null)
    } else {
        SyncRequest(updatedAt = SYNC_LISTING_START.takeIf { cursor == null }, cursor = cursor, status = SYNC_LISTING_STATUS)
    }

/**
 * [pageCursorStream] with a way out of a cursor the server cannot answer.
 *
 * [fetch] gets the request to send. [onPage] gets every page with the position after it; a position that is
 * [StreamPosition.unfiltered] holds rows of every status, and the caller drops the ones that are not live.
 * [onPosition] gets a position that changes without a page and is to be persisted: a recorded failure, or the switch.
 *
 * A request that fails with a kind of failure that a cursor can cause ([failureKindOf]) is recorded in the position.
 * The first failure propagates as it is, so a transient one resumes the same cursor on the next attempt like before.
 * The same failure on the same cursor a second time, in the next attempt or after a restart (the record is persisted
 * with the scan marker), leaves the cursor: the stream goes on from `updatedAt` a little before it without the status
 * filter ([unfilteredPositionAfter]). A failure of that stream itself, a failure of another kind, and a failure on a
 * first page, which has no cursor to leave, propagate unchanged.
 */
internal suspend fun <T> pageStreamWithFallback(
    stream: String,
    start: StreamPosition,
    fetch: suspend (request: SyncRequest) -> SyncPage<T>,
    onPage: suspend (items: List<T>, position: StreamPosition) -> Unit,
    onPosition: suspend (position: StreamPosition) -> Unit,
): StreamEnd {
    var from = start
    while (true) {
        var standing = from
        try {
            return pageCursorStream(
                stream = stream,
                start = from,
                fetch = { cursor -> fetch(requestFor(from, cursor)) },
                onPage = { items, at ->
                    standing = at.copy(unfiltered = from.unfiltered)
                    onPage(items, standing)
                },
            )
        } catch (e: InternxtApiException) {
            from = leaveCursorAfter(stream, standing, e, onPosition) ?: throw e
        }
    }
}

// The position to go on from after [e] on [standing], null when the stream stays where it is and [e] propagates.
private suspend fun leaveCursorAfter(
    stream: String,
    standing: StreamPosition,
    e: InternxtApiException,
    onPosition: suspend (StreamPosition) -> Unit,
): StreamPosition? {
    val kind = failureKindOf(e.statusCode)
    val cursor = standing.cursor
    if (kind == null || cursor == null || standing.unfiltered) return null
    val failed = standing.failedWith(kind)
    val times = failed.failure?.count ?: 0
    if (times >= FAILURES_BEFORE_FALLBACK) {
        val fallback = unfilteredPositionAfter(cursor)
        if (fallback != null) {
            log.info(
                "The {} listing failed {} times in a row on the same cursor (updatedAt {}) with {}: leaving the cursor, " +
                    "continuing from updatedAt {} without the status filter and skipping the rows that are not live. Last error: {}",
                stream,
                times,
                instantOfCursor(cursor),
                if (kind == "408") "HTTP 408" else "a server-side failure (HTTP ${e.statusCode})",
                fallback.since,
                e.message,
            )
            onPosition(fallback)
            return fallback
        }
        log.warn("The {} listing failed {} times in a row on the same cursor, which cannot be read to continue without it", stream, times)
    }
    log.debug("The {} listing failed on its cursor (HTTP {}): recorded as failure {}", stream, e.statusCode, times)
    onPosition(failed)
    return null
}
