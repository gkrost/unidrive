package org.krost.unidrive.internxt

import org.krost.unidrive.internxt.model.InternxtFolder
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * UD-376: pins the behaviour of [InternxtProvider.buildFolderPath] for the
 * three delta-page-completeness scenarios that the live integration audit
 * (2026-05-17) surfaced.
 *
 * The bug summarised: when `/folders` delta returns a folder whose
 * non-root ancestor was NOT in the same page (e.g. ancestor unchanged
 * since cursor), `buildFolderPath` silently coerces the missing
 * ancestor to the empty string, which then propagates into the
 * full path via `"$parentPath/$name"` → the item is wrongly written
 * to state.db at the root.
 *
 * In the user's profile this manifested as ~84 000 sync_entries rows
 * sharing remote_id with another (deeper) row — including the
 * specific `_XXX` folder whose orphaned `/_INBOX/_XXX/...` children
 * then cascade through the UD-225 download-recovery loop and surface
 * as "Folder not found: _XXX in /_INBOX/_XXX/..." in failures.jsonl.
 *
 * The "missing ancestor" test below is a **pinning test** — it
 * documents the broken behaviour so the next agent can flip the
 * assertion the moment the structural fix lands. The "happy path"
 * tests are correctness pins.
 *
 * Investigation note: docs/audits/internxt-xxx-phantom-investigation-2026-05-17.md
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

    /**
     * UD-376 — THIS IS THE BUG. When the delta page returns `_XXX` but
     * not `_INBOX` (because `_INBOX` has not changed since the cursor),
     * `buildFolderPath` cannot walk past the missing ancestor and
     * silently returns `""`. The caller then builds the final path as
     * `"" + "/" + "_XXX"` = `/_XXX` — the folder is wrongly relocated
     * to the root, every child whose state.db row still references
     * `/_INBOX/_XXX/...` becomes an orphan, and the UD-225 recovery
     * loop in `Reconciler.kt:183-204` re-emits those orphans as
     * `DownloadContent` against the stale path.
     *
     * The assertion below is **pinned at the buggy output**. When the
     * structural fix lands (one of: return null + drop item, fetch
     * missing ancestor on demand, fall back to state.db remote_id
     * lookup — see investigation note), flip this assertion to the
     * correct fix-specific expectation:
     *   - Option 1 (drop + signal incomplete): expect `null`,
     *     callers drop the item, delta signals `complete=false`.
     *   - Option 2 (on-demand fetch): expect `/_INBOX/_XXX` (full
     *     correct path), folderMap mutated by side-effect.
     *   - Option 3 (state.db fallback): expect `/_INBOX/_XXX` from
     *     DB lookup, no API round-trip.
     */
    @Test
    fun `BUG missing ancestor in folderMap collapses path to root (pinned regression)`() {
        // Delta page returned `_XXX` but NOT its parent `_INBOX` — typical
        // when `_INBOX` is unchanged since the cursor and so omitted by the
        // /folders endpoint.
        val xxx = InternxtFolder(uuid = "xxx-uuid", plainName = "_XXX", parentUuid = "inbox-uuid-not-in-map")
        val folderMapWithoutAncestor = mapOf(xxx.uuid to xxx)

        val result = InternxtProvider.buildFolderPath(xxx.uuid, folderMapWithoutAncestor, rootUuid)

        // Current (buggy) behaviour: parent fold returns "", so the folder
        // ends up named like a top-level entry. `folderToDeltaCloudItem`
        // then composes `"$parentPath/$name"` = `/_XXX`.
        assertEquals(
            "/_XXX",
            result,
            "UD-376 regression pin: current behaviour silently roots the item " +
                "when an ancestor is absent from the delta page. Flip this " +
                "assertion when the structural fix lands.",
        )
    }

    /**
     * Companion bug: a file (parent = `_XXX`) whose grand-ancestor is
     * missing similarly collapses to `/$name`. Pinned for the same reason.
     */
    @Test
    fun `BUG missing grand-ancestor still roots item under known leaf parent`() {
        // folderMap has `_XXX` (the immediate parent) but NOT `_INBOX` (its
        // own parent). buildFolderPath recurses into `_XXX`, then asks for
        // `_INBOX` which is absent, returns "".
        val xxx = InternxtFolder(uuid = "xxx-uuid", plainName = "_XXX", parentUuid = "inbox-uuid-not-in-map")
        val folderMap = mapOf(xxx.uuid to xxx)

        val result = InternxtProvider.buildFolderPath(xxx.uuid, folderMap, rootUuid)

        // The leaf parent `_XXX` is known, so this returns `"" + "/" + "_XXX"`.
        // Children built off this parentPath end up as `/_XXX/<child>`.
        assertEquals("/_XXX", result)
    }
}
