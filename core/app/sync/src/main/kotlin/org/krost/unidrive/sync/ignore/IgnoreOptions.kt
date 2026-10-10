package org.krost.unidrive.sync.ignore

/**
 * How the rules of one rule file are matched.
 *
 * @property ignoreCase git's `core.ignorecase`: ASCII letters compare case-insensitively (non-ASCII never folds).
 * @property normalizeNfc NFC-normalise rule text and queried paths before matching (a deliberate deviation from git's
 *   byte comparison, see DEVIATIONS.md). Off by default, like git.
 */
data class IgnoreOptions(
    val ignoreCase: Boolean = false,
    val normalizeNfc: Boolean = false,
)
