package org.krost.unidrive.cli

object AnsiHelper {
    internal var envLookup: (String) -> String? = { System.getenv(it) }
    internal var consolePresent: () -> Boolean = { System.console() != null }

    fun isAnsiSupported(): Boolean {
        if (envLookup("NO_COLOR") != null) return false
        if (envLookup("TERM") == "dumb") return false
        return consolePresent() && envLookup("TERM") != null
    }

    fun bold(text: String): String = if (isAnsiSupported()) "\u001b[1m$text\u001b[0m" else text

    fun dim(text: String): String = if (isAnsiSupported()) "\u001b[90m$text\u001b[0m" else text

    fun green(text: String): String = if (isAnsiSupported()) "\u001b[32m$text\u001b[0m" else text

    fun yellow(text: String): String = if (isAnsiSupported()) "\u001b[33m$text\u001b[0m" else text

    fun red(text: String): String = if (isAnsiSupported()) "\u001b[31m$text\u001b[0m" else text

    fun reset(): String = if (isAnsiSupported()) "\u001b[0m" else ""
}
