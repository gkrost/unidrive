package org.krost.unidrive.telemetry

/**
 * What may be stored of an error: a class name and a short message with no URL, token or secret in
 * it. Provider exceptions quote request URLs (with signatures and tokens in the query) and headers, so
 * everything that looks like one is replaced before the text reaches the database. The filter errs on
 * the side of removing too much.
 */
internal object HistorySanitizer {
    const val MAX_MESSAGE_CHARS = 240
    private const val MAX_CLASS_CHARS = 120
    private const val REDACTED = "<redacted>"

    private val url = Regex("""(?i)\b[a-z][a-z0-9+.-]*://\S+""")
    private val bearer = Regex("""(?i)\b(bearer|basic|negotiate)\s+\S+""")
    private val keyValue =
        Regex(
            """(?i)\b([a-z0-9_.-]*(token|password|passwd|secret|api[_-]?key|authorization|signature|sig|credential|cookie|session)[a-z0-9_.-]*)\s*[=:]\s*\S+""",
        )
    private val jwt = Regex("""\beyJ[\w-]*(\.[\w-]+){1,2}""")
    private val longOpaque = Regex("""[A-Za-z0-9+/_=-]{40,}""")
    private val whitespace = Regex("""\s+""")
    private val classChars = Regex("""[^A-Za-z0-9_.$]""")

    /** A class name or short code, stripped to identifier characters; null when nothing is left. */
    fun errorClass(raw: String?): String? = raw?.replace(classChars, "")?.take(MAX_CLASS_CHARS)?.ifEmpty { null }

    /** [raw] with URLs, credentials and long opaque strings removed, on one line, at most [MAX_MESSAGE_CHARS]. */
    fun message(raw: String?): String? {
        if (raw == null) return null
        val cleaned =
            raw
                .replace(url, "<url>")
                .replace(bearer) { "${it.groupValues[1]} $REDACTED" }
                .replace(keyValue) { "${it.groupValues[1]}=$REDACTED" }
                .replace(jwt, REDACTED)
                .replace(longOpaque, REDACTED)
                .replace(whitespace, " ")
                .trim()
        return cleaned.take(MAX_MESSAGE_CHARS).ifEmpty { null }
    }
}
