package org.krost.unidrive

/**
 * One page of remote changes. Providers return one or more pages per
 * [CloudProvider.delta] call; the engine concatenates pages until
 * [hasMore] is false.
 *
 * UD-360: [complete] signals whether the provider gathered the *entire*
 * scope it was asked for. `complete=false` means the provider had to
 * skip part of the inventory due to transient errors (e.g. Internxt
 * returning 503 on a subtree). When any page in a full-sync delta
 * returns `complete=false`, the engine MUST skip the absence-implies-
 * deletion sweep ([SyncEngine.detectMissingAfterFullSync]); otherwise
 * the missing rows would synthesize spurious `DeleteLocal` actions.
 * Pagination semantics ([hasMore]) are unchanged. Providers that always
 * gather the complete scope leave the default `true`.
 *
 * [unreachableIds] are remote items the listing returned but could not place in
 * the tree, because a folder above them is permanently gone (the server answers
 * 404 or 410 for it). They are not deleted, they have no path this pass: the page
 * is [complete] without them, and the absence sweep treats them as present, so a
 * tracked row of theirs is neither reaped nor turned into a local delete. A folder
 * that is unavailable for a transient reason (5xx, timeout, 429, unreadable
 * answer) is not this: its items are left out and the page is not [complete].
 * Providers that always place every item leave the default empty set.
 */
data class DeltaPage(
    val items: List<CloudItem>,
    val cursor: String,
    val hasMore: Boolean,
    val complete: Boolean = true,
    val unreachableIds: Set<String> = emptySet(),
)
