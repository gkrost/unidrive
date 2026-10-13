package org.krost.unidrive.cli

import org.krost.unidrive.sync.Reconciler

internal fun pathMatchesExcludePatterns(path: String, patterns: List<String>): Boolean =
    patterns.any { pattern -> Reconciler.matchesGlob(path, pattern) }
