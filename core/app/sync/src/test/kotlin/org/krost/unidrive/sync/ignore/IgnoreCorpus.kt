package org.krost.unidrive.sync.ignore

import java.text.Normalizer

/**
 * One oracle case: rule files plus a list of paths to ask about.
 *
 * @property files rule files by path relative to the matching root (".gitignore", "sub/.gitignore"), full text.
 * @property paths paths to classify; a trailing "/" marks a directory.
 * @property modes the `core.ignorecase` values the case is run with.
 * @property minGit the oldest git whose behaviour the case pins; older ones skip it (see DEVIATIONS.md).
 */
internal data class OracleCase(
    val name: String,
    val resource: String,
    val files: Map<String, String>,
    val paths: List<String>,
    val modes: List<Boolean>,
    val minGit: List<Int>?,
)

/**
 * The corpus files under `ignore-oracle/` in the test resources.
 *
 * ```
 * ## comment (only before the first "---" of a case)
 * === case-name
 * modes: cs | ci | both          (default both: core.ignorecase=false and true)
 * min-git: 2.52                  (optional)
 * --- .gitignore                 a rule file; its lines follow verbatim
 * *.log
 * --- sub/.gitignore             a nested rule file
 * --- paths                      one path per line; a trailing / marks a directory
 * ```
 *
 * Tokens expand in rule and path lines: `{SP}`, `{TAB}`, `{CR}` and `{u:0065,0301}` (code points in hex), so
 * trailing spaces, CRLF and normalisation forms survive editors that strip or normalise text.
 */
internal object IgnoreCorpus {
    val resources =
        listOf(
            "01-basics.cases",
            "02-anchoring.cases",
            "03-doublestar.cases",
            "04-negation.cases",
            "05-escapes-whitespace.cases",
            "06-classes.cases",
            "07-case-unicode.cases",
            "08-nested.cases",
            "09-docs-examples.cases",
            "10-edge.cases",
        )

    private val TOKEN = Regex("""\{(SP|TAB|CR)}|\{u:([0-9A-Fa-f]{1,6}(?:,[0-9A-Fa-f]{1,6})*)}""")

    fun expand(line: String): String =
        TOKEN.replace(line) { m ->
            when (m.groupValues[1]) {
                "SP" -> " "
                "TAB" -> "\t"
                "CR" -> "\r"
                else -> m.groupValues[2].split(',').joinToString("") { String(Character.toChars(it.toInt(16))) }
            }
        }

    fun load(): List<OracleCase> {
        val all = resources.flatMap { load(it) }
        val dup = all.groupBy { it.name }.filterValues { it.size > 1 }.keys
        require(dup.isEmpty()) { "duplicate case names: $dup" }
        return all
    }

    private fun load(resource: String): List<OracleCase> {
        val text =
            IgnoreCorpus::class.java.getResourceAsStream("/ignore-oracle/$resource")?.use { String(it.readBytes(), Charsets.UTF_8) }
                ?: error("missing corpus resource $resource")
        val cases = ArrayList<OracleCase>()
        var name: String? = null
        var modes = listOf(false, true)
        var minGit: List<Int>? = null
        var files = LinkedHashMap<String, StringBuilder>()
        var paths = ArrayList<String>()
        var section: String? = null // null = header, "paths" or a file name

        fun flush() {
            val n = name ?: return
            require(files.isNotEmpty() && paths.isNotEmpty()) { "$resource: case '$n' needs rule files and paths" }
            cases.add(OracleCase(n, resource, files.mapValues { it.value.toString() }, paths, modes, minGit))
        }
        for (raw in text.split('\n')) {
            val line = raw.removeSuffix("\r")
            when {
                line.startsWith("=== ") -> {
                    flush()
                    name = line.removePrefix("=== ").trim()
                    modes = listOf(false, true)
                    minGit = null
                    files = LinkedHashMap()
                    paths = ArrayList()
                    section = null
                }
                line.startsWith("--- ") -> {
                    section = line.removePrefix("--- ").trim()
                    if (section != "paths") files[section] = StringBuilder()
                }
                section == null -> {
                    if (line.isBlank() || line.startsWith("##")) continue
                    val (k, v) = line.split(':', limit = 2).map { it.trim() }
                    when (k) {
                        "modes" -> modes = mapOf("cs" to listOf(false), "ci" to listOf(true), "both" to listOf(false, true))[v] ?: error("bad modes '$v'")
                        "min-git" -> minGit = v.split('.').map { it.toInt() }
                        else -> error("$resource: unknown header '$k' in case '$name'")
                    }
                }
                section == "paths" -> if (line.isNotEmpty()) paths.add(expand(line))
                else -> {
                    if (name == null) continue
                    files.getValue(section).append(expand(line)).append('\n')
                }
            }
        }
        flush()
        // The text of every rule file ends with a newline from the splitter; the file that ends mid-line is a unit test.
        return cases
    }

    fun nfc(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC)
}
