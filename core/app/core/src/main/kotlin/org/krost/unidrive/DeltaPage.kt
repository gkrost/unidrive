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
 */
data class DeltaPage(
    val items: List<CloudItem>,
    val cursor: String,
    val hasMore: Boolean,
    val complete: Boolean = true,
)
