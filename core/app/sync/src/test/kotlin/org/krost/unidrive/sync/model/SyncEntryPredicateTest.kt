package org.krost.unidrive.sync.model

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #136: the UD-901 pending-upload predicate lives in ONE place —
 * [SyncEntry.isPendingUpload] — so no consumer re-assembles it from halves and
 * silently misclassifies the gap row (remoteId == null, isHydrated == false: a
 * sparse partial-download row that has no bytes to upload).
 */
class SyncEntryPredicateTest {

    private fun entry(
        remoteId: String?,
        isHydrated: Boolean,
    ) = SyncEntry(
        path = "/f.txt",
        remoteId = remoteId,
        remoteHash = remoteId?.let { "h" },
        remoteSize = if (remoteId != null) 10L else 0L,
        remoteModified = null,
        localMtime = 1711627200000,
        localSize = 10L,
        isFolder = false,
        isPinned = false,
        isHydrated = isHydrated,
        lastSynced = Instant.EPOCH,
    )

    @Test
    fun `never-uploaded hydrated row is a pending upload`() {
        assertTrue(entry(remoteId = null, isHydrated = true).isPendingUpload)
    }

    @Test
    fun `the gap row - sparse partial download with no remote id - is NOT a pending upload`() {
        assertFalse(
            entry(remoteId = null, isHydrated = false).isPendingUpload,
            "no bytes on disk, nothing to upload — the row belongs to the download lane",
        )
    }

    @Test
    fun `remote-backed rows are never pending uploads regardless of hydration`() {
        assertFalse(entry(remoteId = "r1", isHydrated = true).isPendingUpload)
        assertFalse(entry(remoteId = "r1", isHydrated = false).isPendingUpload)
    }

    @Test
    fun `predicate tracks copy-updated fields`() {
        // It is derived from the two stored columns, so a copy() that flips either
        // half flips the predicate — an upload landing (remoteId set) or a
        // dehydrate/free (isHydrated false) both settle it.
        val pending = entry(remoteId = null, isHydrated = true)
        assertTrue(pending.isPendingUpload)
        assertFalse(pending.copy(remoteId = "r2").isPendingUpload, "upload landed")
        assertFalse(pending.copy(isHydrated = false).isPendingUpload, "no local bytes")
    }
}
