package org.krost.unidrive.sync

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * #737: the name of a local conflict copy, the scheme keep-both uses for the user's side copy
 * (`SyncEngine.applyKeepBoth`): `<stem>.conflict-local-<yyyyMMdd'T'HHmm>.<ext>`, or without the
 * extension when the name has none. [n] > 1 adds a free-name counter, `<stem>.conflict-local-<stamp> (n).<ext>`,
 * so a second copy in the same minute never lands on the first.
 *
 * It works on the leaf name only, so a dot in a parent folder's name is never taken for the
 * extension, and a dotfile (`.bashrc`) keeps its whole name as the stem.
 */
internal object ConflictCopyNames {
    fun localCopyName(
        leaf: String,
        now: Instant,
        n: Int = 1,
    ): String {
        val stamp =
            DateTimeFormatter
                .ofPattern("yyyyMMdd'T'HHmm")
                .withZone(ZoneId.systemDefault())
                .format(now)
        val dot = leaf.lastIndexOf('.')
        val stem = if (dot > 0) leaf.substring(0, dot) else leaf
        val ext = if (dot > 0) leaf.substring(dot + 1) else ""
        val counter = if (n > 1) " ($n)" else ""
        val suffix = ".conflict-local-$stamp$counter"
        return if (ext.isNotEmpty()) "$stem$suffix.$ext" else "$stem$suffix"
    }
}
