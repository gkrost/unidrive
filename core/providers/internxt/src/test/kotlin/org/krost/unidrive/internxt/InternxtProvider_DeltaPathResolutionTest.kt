package org.krost.unidrive.internxt

import org.krost.unidrive.CloudItem
import org.krost.unidrive.internxt.model.InternxtFile
import org.krost.unidrive.internxt.model.InternxtFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Path resolution for Internxt's delta page. The /folders endpoint returns
 * only folders that changed since the cursor, so non-root ancestors of a
 * changed leaf may be absent. `buildFolderPath` returns null in that case
 * and callers drop the item + signal complete=false. The earlier silent-
 * empty fallback produced ~84k duplicate remote_id rows in user state.db.
 *
 * Investigation: docs/audits/internxt-phantom-investigation.md.
 */
class InternxtProvider_DeltaPathResolutionTest {
    private val rootUuid = "root-uuid"

    @Test
    fun `root uuid resolves to empty path`() {
        val result = InternxtProvider.buildFolderPath(rootUuid, emptyMap(), rootUuid)
        assertEquals("", result, "root must resolve to empty string (parentPath sentinel)")
    }

    @Test
    fun `single-level child resolves to slash-name`() {
        val inbox = InternxtFolder(uuid = "inbox-uuid", plainName = "_INBOX", parentUuid = rootUuid)
        val result = InternxtProvider.buildFolderPath(inbox.uuid, mapOf(inbox.uuid to inbox), rootUuid)
        assertEquals("/_INBOX", result)
    }

    @Test
    fun `two-level child resolves through ancestor`() {
        val inbox = InternxtFolder(uuid = "inbox-uuid", plainName = "_INBOX", parentUuid = rootUuid)
        val xxx = InternxtFolder(uuid = "xxx-uuid", plainName = "_XXX", parentUuid = inbox.uuid)
        val folderMap = mapOf(inbox.uuid to inbox, xxx.uuid to xxx)
        assertEquals("/_INBOX/_XXX", InternxtProvider.buildFolderPath(xxx.uuid, folderMap, rootUuid))
    }

    @Test
    fun `missing immediate ancestor returns null (signal incomplete)`() {
        // Delta page returned `_XXX` but NOT its parent `_INBOX` — typical
        // when `_INBOX` is unchanged since the cursor and so omitted by
        // the /folders endpoint. Pre-fix this collapsed to `/_XXX` and
        // orphaned every child whose state.db row still referenced
        // `/_INBOX/_XXX/...`.
        val xxx = InternxtFolder(uuid = "xxx-uuid", plainName = "_XXX", parentUuid = "inbox-uuid-not-in-map")
        val folderMapWithoutAncestor = mapOf(xxx.uuid to xxx)

        val result = InternxtProvider.buildFolderPath(xxx.uuid, folderMapWithoutAncestor, rootUuid)

        assertNull(result, "missing ancestor must return null so the caller drops the item")
    }

    @Test
    fun `missing grand-ancestor returns null`() {
        // folderMap has `_XXX` (the immediate parent) but NOT `_INBOX` (its
        // own parent). The recursion walks into `_XXX`, then asks for `_INBOX`
        // which is absent — the inner call returns null and the outer call
        // propagates it.
        val xxx = InternxtFolder(uuid = "xxx-uuid", plainName = "_XXX", parentUuid = "inbox-uuid-not-in-map")
        val folderMap = mapOf(xxx.uuid to xxx)

        // Lookup of `_XXX` itself succeeds (it's in the map, immediate parent
        // is the missing one).
        assertNull(InternxtProvider.buildFolderPath(xxx.uuid, folderMap, rootUuid))
    }

    @Test
    fun `unknown uuid (not even in map) returns null`() {
        val result = InternxtProvider.buildFolderPath("totally-unknown-uuid", emptyMap(), rootUuid)
        assertNull(result)
    }

    // ---- Self-healing ancestor re-fetch (buildFolderPathFetching) ----

    @Test
    fun `missing ancestor that IS fetchable resolves to a correct path (not dropped)`() =
        kotlinx.coroutines.test.runTest {
            // Delta page returned `_XXX` but NOT its unchanged parent `_INBOX`.
            // Pre-fix this dropped `_XXX` and forced complete=false forever on an
            // unchanged remote. The re-fetch splices `_INBOX` in and resolves.
            val inbox = InternxtFolder(uuid = "inbox-uuid", plainName = "_INBOX", parentUuid = rootUuid)
            val xxx = InternxtFolder(uuid = "xxx-uuid", plainName = "_XXX", parentUuid = inbox.uuid)
            val folderMap = mutableMapOf(xxx.uuid to xxx)
            val unfetchable = mutableSetOf<String>()

            val path =
                InternxtProvider.buildFolderPathFetching(
                    uuid = xxx.uuid,
                    folderMap = folderMap,
                    rootUuid = rootUuid,
                    unfetchable = unfetchable,
                    fetchFolder = { uuid -> if (uuid == inbox.uuid) inbox else null },
                )

            assertEquals("/_INBOX/_XXX", path)
            // Fetched ancestor is now memoised in the map for the rest of the pass.
            assertNotNull(folderMap[inbox.uuid])
            assertTrue(unfetchable.isEmpty())
        }

    @Test
    fun `multi-level missing ancestor chain is fetched up to root`() =
        kotlinx.coroutines.test.runTest {
            // `_XXX` present; both `_INBOX` and `_MID` missing — the walk must
            // climb the whole chain to root, fetching each absent ancestor once.
            val inbox = InternxtFolder(uuid = "inbox-uuid", plainName = "_INBOX", parentUuid = rootUuid)
            val mid = InternxtFolder(uuid = "mid-uuid", plainName = "_MID", parentUuid = inbox.uuid)
            val xxx = InternxtFolder(uuid = "xxx-uuid", plainName = "_XXX", parentUuid = mid.uuid)
            val byUuid = mapOf(inbox.uuid to inbox, mid.uuid to mid)
            val folderMap = mutableMapOf(xxx.uuid to xxx)
            val unfetchable = mutableSetOf<String>()

            val path =
                InternxtProvider.buildFolderPathFetching(
                    uuid = xxx.uuid,
                    folderMap = folderMap,
                    rootUuid = rootUuid,
                    unfetchable = unfetchable,
                    fetchFolder = { uuid -> byUuid[uuid] },
                )

            assertEquals("/_INBOX/_MID/_XXX", path)
        }

    @Test
    fun `genuinely unfetchable ancestor degrades to null without throwing`() =
        kotlinx.coroutines.test.runTest {
            // The ancestor is 404 / trashed / gone — fetchFolder returns null.
            // Resolution must degrade to drop (null), not throw, so delta() can
            // count it and let complete=false stand.
            val xxx = InternxtFolder(uuid = "xxx-uuid", plainName = "_XXX", parentUuid = "gone-uuid")
            val folderMap = mutableMapOf(xxx.uuid to xxx)
            val unfetchable = mutableSetOf<String>()

            val path =
                InternxtProvider.buildFolderPathFetching(
                    uuid = xxx.uuid,
                    folderMap = folderMap,
                    rootUuid = rootUuid,
                    unfetchable = unfetchable,
                    fetchFolder = { null },
                )

            assertNull(path, "unfetchable ancestor must drop (null), never throw")
            assertTrue(unfetchable.contains("gone-uuid"), "gone uuid is recorded so it is never re-fetched")
        }

    @Test
    fun `the same missing ancestor uuid is fetched at most once per pass`() =
        kotlinx.coroutines.test.runTest {
            // Scale guard: many leaves share one missing ancestor. The re-fetch
            // must memoise so an N+1 fetch storm can't happen against a
            // ~196k-file deep tree. Resolve five distinct children that all hang
            // off the same absent `_INBOX`; assert exactly one fetch.
            val inbox = InternxtFolder(uuid = "inbox-uuid", plainName = "_INBOX", parentUuid = rootUuid)
            val children =
                (1..5).map { InternxtFolder(uuid = "child-$it", plainName = "C$it", parentUuid = inbox.uuid) }
            val folderMap = children.associateByTo(mutableMapOf()) { it.uuid }
            val unfetchable = mutableSetOf<String>()
            var fetchCount = 0
            val fetch: suspend (String) -> InternxtFolder? = { uuid ->
                if (uuid == inbox.uuid) {
                    fetchCount++
                    inbox
                } else {
                    null
                }
            }

            children.forEach { child ->
                val path =
                    InternxtProvider.buildFolderPathFetching(child.uuid, folderMap, rootUuid, unfetchable, fetch)
                assertEquals("/_INBOX/${child.plainName}", path)
            }

            assertEquals(1, fetchCount, "the shared missing ancestor must be fetched exactly once across all leaves")
        }

    @Test
    fun `a known-unfetchable ancestor is not re-requested across leaves`() =
        kotlinx.coroutines.test.runTest {
            // Negative-cache twin of the memoisation test: an ancestor that is
            // gone must be requested at most once even though many leaves point
            // at it. Otherwise a gone-folder subtree triggers an N-fetch storm.
            val children =
                (1..4).map { InternxtFolder(uuid = "child-$it", plainName = "C$it", parentUuid = "gone-uuid") }
            val folderMap = children.associateByTo(mutableMapOf()) { it.uuid }
            val unfetchable = mutableSetOf<String>()
            var fetchCount = 0
            val fetch: suspend (String) -> InternxtFolder? = { _ ->
                fetchCount++
                null
            }

            children.forEach { child ->
                assertNull(
                    InternxtProvider.buildFolderPathFetching(child.uuid, folderMap, rootUuid, unfetchable, fetch),
                )
            }

            assertEquals(1, fetchCount, "a known-gone ancestor must be fetched at most once per pass")
        }

    // ---- Resumable-scan helpers ----

    @Test
    fun `advanceCursor picks the larger of seenMax and requestCursor`() {
        // Hot account: fetched items, freshest updatedAt is newer than the
        // last cursor → advance to seenMax.
        assertEquals(
            "2026-05-18T17:20:30.000Z",
            InternxtProvider.advanceCursor(
                seenMax = "2026-05-18T17:20:30.000Z",
                requestCursor = "2026-05-18T10:00:00.000Z",
            ),
        )
    }

    @Test
    fun `advanceCursor floors at requestCursor when no items were seen`() {
        // Empty page-set (incomplete sweep where every folder hit the 503
        // fallback) — without a floor the cursor would regress to
        // Instant.now() and we'd skip past items modified between the prior
        // cursor and now.
        assertEquals(
            "2026-05-18T15:00:00.000Z",
            InternxtProvider.advanceCursor(
                seenMax = null,
                requestCursor = "2026-05-18T15:00:00.000Z",
            ),
        )
    }

    @Test
    fun `advanceCursor floors at requestCursor when seenMax is older (stale page)`() {
        // Pathological: the gather saw only items older than the prior
        // cursor. The cursor must not regress.
        assertEquals(
            "2026-05-18T15:00:00.000Z",
            InternxtProvider.advanceCursor(
                seenMax = "2026-05-17T10:00:00.000Z",
                requestCursor = "2026-05-18T15:00:00.000Z",
            ),
        )
    }

    @Test
    fun `advanceCursor returns seenMax on first scan (no prior cursor)`() {
        assertEquals(
            "2026-05-18T17:20:30.000Z",
            InternxtProvider.advanceCursor(
                seenMax = "2026-05-18T17:20:30.000Z",
                requestCursor = null,
            ),
        )
    }

    @Test
    fun `advanceCursor falls back to Instant_now on first scan with empty results`() {
        // Both null: no prior cursor AND no items seen. Falling back to
        // Instant.now() means the next launch asks the gateway for "items
        // modified since just-now" rather than "since epoch" — saves a
        // pointless full re-scan on a first-launch / freshly-reset state.db
        // against an account that happens to have zero items.
        val result = InternxtProvider.advanceCursor(seenMax = null, requestCursor = null)
        // Sanity: parses as an instant and is within the last 5 seconds.
        val parsed = java.time.Instant.parse(result)
        val now = java.time.Instant.now()
        assertTrue(parsed.isBefore(now.plusSeconds(1)))
        assertTrue(parsed.isAfter(now.minusSeconds(5)))
    }

    @Test
    fun `buildMarker and parseResumeOffsets round-trip`() {
        val original = InternxtProvider.buildMarker(filesOffset = 250, foldersOffset = 100)
        val parsed = InternxtProvider.parseResumeOffsets(original)
        assertEquals(250, parsed.filesOffset)
        assertEquals(100, parsed.foldersOffset)
    }

    @Test
    fun `parseResumeOffsets falls back to zero on malformed input`() {
        // Null, empty, single-token, non-numeric — all degrade to a fresh-scan
        // start (the staging-clear path swept these on the prior gather, so
        // we should never see them in practice; the guard exists to prevent a
        // bad marker from re-reading items 100-200 instead of 0-200).
        listOf(null, "", "garbage", "5", "5|x", "x|5", "5|10|extra", "-5|10").forEach { marker ->
            val parsed = InternxtProvider.parseResumeOffsets(marker)
            assertEquals(0, parsed.filesOffset, "files offset for marker=`$marker`")
            assertEquals(0, parsed.foldersOffset, "folders offset for marker=`$marker`")
        }
    }

    @Test
    fun `toStagedCloudItem strips root parent_uuid to null`() {
        val file =
            InternxtFile(uuid = "f-uuid", plainName = "report", type = "txt", folderUuid = rootUuid, size = "42")
        val staged = with(InternxtProvider.Companion) { file.toStagedCloudItem(rootUuid) }
        // The Internxt API returns folderUuid==rootUuid for items at the
        // bucket root; the staged CloudItem.parentId should be null so resume-
        // time path reconstruction treats it as a root-level item.
        assertNull(staged.parentId)
        assertEquals("report.txt", staged.name)
        assertEquals(42L, staged.size)
        assertEquals("f-uuid", staged.id)
    }

    @Test
    fun `toStagedCloudItem preserves non-root parent_uuid`() {
        val file = InternxtFile(uuid = "f-uuid", plainName = "report", type = "txt", folderUuid = "inbox-uuid")
        val staged = with(InternxtProvider.Companion) { file.toStagedCloudItem(rootUuid) }
        assertEquals("inbox-uuid", staged.parentId)
    }

    @Test
    fun `resumed CloudItem round-trips through toResumedFolder back into the folder graph`() {
        // Engine staged a folder; the next launch's delta() loads it back as a
        // CloudItem and re-inflates it into an InternxtFolder so the folder
        // graph rebuild sees it alongside the freshly-fetched siblings.
        val staged =
            CloudItem(
                id = "inbox-uuid",
                name = "_INBOX",
                path = "/_INBOX", // placeholder; the real path is re-resolved
                size = 0,
                isFolder = true,
                modified = null,
                created = null,
                hash = null,
                mimeType = null,
                parentId = rootUuid,
            )
        val resumed = with(InternxtProvider.Companion) { staged.toResumedFolder() }
        assertEquals("inbox-uuid", resumed.uuid)
        assertEquals("_INBOX", resumed.plainName)
        assertEquals(rootUuid, resumed.parentUuid)
        // buildFolderPath against the resumed folder must reach back to root.
        assertEquals(
            "/_INBOX",
            InternxtProvider.buildFolderPath(resumed.uuid, mapOf(resumed.uuid to resumed), rootUuid),
        )
    }

    @Test
    fun `resumed file with extension round-trips through toResumedFile`() {
        // The CloudItem name is `${plainName}.${type}`; re-inflation has to
        // split it back so the file rebuild produces a usable Internxt model.
        val staged =
            CloudItem(
                id = "f-uuid",
                name = "report.txt",
                path = "/report.txt",
                size = 100,
                isFolder = false,
                modified = null,
                created = null,
                hash = null,
                mimeType = null,
                parentId = "inbox-uuid",
            )
        val resumed = with(InternxtProvider.Companion) { staged.toResumedFile() }
        assertEquals("f-uuid", resumed.uuid)
        assertEquals("report", resumed.plainName)
        assertEquals("txt", resumed.type)
        assertEquals("inbox-uuid", resumed.folderUuid)
        assertEquals("100", resumed.size)
    }

    @Test
    fun `resumed file without extension keeps full name in plainName`() {
        val staged =
            CloudItem(
                id = "f-uuid",
                name = "README",
                path = "/README",
                size = 0,
                isFolder = false,
                modified = null,
                created = null,
                hash = null,
                mimeType = null,
                parentId = null,
            )
        val resumed = with(InternxtProvider.Companion) { staged.toResumedFile() }
        assertEquals("README", resumed.plainName)
        assertEquals("", resumed.type, "no extension - type is empty (matches API absent type)")
    }

    @Test
    fun `resumed staged folder feeds into buildFolderPath alongside fresh ones`() {
        // Crash mid-scan: _INBOX was staged, _INBOX/_XXX was about to be
        // fetched. On resume, the staged _INBOX rejoins the folder graph
        // before _XXX's page arrives, and the graph reconstruction succeeds
        // — proving the resume seam matches the in-memory flow that worked
        // pre-resume.
        val resumedInbox =
            with(InternxtProvider.Companion) {
                CloudItem(
                    id = "inbox-uuid",
                    name = "_INBOX",
                    path = "/_INBOX",
                    size = 0,
                    isFolder = true,
                    modified = null,
                    created = null,
                    hash = null,
                    mimeType = null,
                    parentId = rootUuid,
                ).toResumedFolder()
            }
        val freshXxx = InternxtFolder(uuid = "xxx-uuid", plainName = "_XXX", parentUuid = "inbox-uuid")
        val folderMap = mapOf(resumedInbox.uuid to resumedInbox, freshXxx.uuid to freshXxx)
        assertEquals(
            "/_INBOX/_XXX",
            InternxtProvider.buildFolderPath(freshXxx.uuid, folderMap, rootUuid),
            "resumed folder must serve as a graph ancestor for fresh-fetched descendants",
        )
        // Sanity-check assertNotNull to keep the import live.
        assertNotNull(folderMap[resumedInbox.uuid])
    }
}
