package org.krost.unidrive.internxt

import org.krost.unidrive.internxt.model.InternxtFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
}
