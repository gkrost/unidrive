package org.krost.unidrive.cli.ext.internal

import org.krost.unidrive.cli.AnsiHelper
import org.krost.unidrive.cli.ext.Formatter

/**
 * Adapts the internal [AnsiHelper] to the public [Formatter] contract.
 * Lives in internal subpackage so it is not considered part of the
 * public extension contract.
 */
internal class AnsiHelperFormatter : Formatter {
    override fun bold(s: String): String = AnsiHelper.bold(s)

    override fun dim(s: String): String = AnsiHelper.dim(s)

    override fun underline(s: String): String = if (AnsiHelper.isAnsiSupported()) "\u001b[4m$s\u001b[0m" else s
}
