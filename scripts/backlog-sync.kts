#!/usr/bin/env kotlin
/*
 * backlog-sync — reconcile UD-### references between code and docs/backlog/.
 *
 * Usage:  kotlinc -script scripts/backlog-sync.kts
 *
 * See docs/AGENT-SYNC.md for the governing contract. This script is the canonical
 * implementation of the "orphan / stale / anchorless / abandoned" checks.
 *
 * Exit codes:
 *   0  — clean, or only warnings
 *   1  — hard errors (orphan code refs, stale closed items)
 */

import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit

val root: File = File(".").canonicalFile.let {
    // Allow running from scripts/ or from the repo root.
    if (File(it, "docs/backlog/BACKLOG.md").exists()) it else it.parentFile
}
require(File(root, "docs/backlog/BACKLOG.md").exists()) {
    "Run from the monorepo root (where docs/backlog/BACKLOG.md lives)."
}

// Only scan referential sources. The docs/ tree is the definitional source
// of truth (BACKLOG.md entries, AGENT-SYNC.md range labels, ADR cross-refs),
// so we skip it to avoid flagging range-marker IDs as orphans.
val scanRoots = listOf("core", "ui", "shell-win", "protocol", "scripts")
val idPattern = Regex("""UD-(\d{3})""")
val frontmatterBlock = Regex("""(?m)^---\s*\n(.*?)\n---""", RegexOption.DOT_MATCHES_ALL)

data class BacklogItem(
    val id: String,
    val fields: Map<String, String>,
    val sourceFile: String,
)

// ---------- Collect backlog entries ----------

fun parseFrontmatter(text: String, source: String): List<BacklogItem> =
    frontmatterBlock.findAll(text).mapNotNull { match ->
        val body = match.groupValues[1]
        val fields = mutableMapOf<String, String>()
        var currentKey: String? = null
        val buf = StringBuilder()
        for (line in body.lines()) {
            val kv = Regex("""^([a-z_]+):\s*(.*)$""").matchEntire(line)
            if (kv != null) {
                currentKey?.let { fields[it] = buf.toString().trim() }
                currentKey = kv.groupValues[1]
                buf.clear()
                buf.append(kv.groupValues[2])
            } else {
                buf.append('\n').append(line)
            }
        }
        currentKey?.let { fields[it] = buf.toString().trim() }
        val id = fields["id"] ?: return@mapNotNull null
        if (!id.matches(Regex("""UD-\d{3}"""))) return@mapNotNull null
        BacklogItem(id, fields, source)
    }.toList()

val openItems = parseFrontmatter(
    File(root, "docs/backlog/BACKLOG.md").readText(),
    "docs/backlog/BACKLOG.md",
).associateBy { it.id }

val closedItems = parseFrontmatter(
    File(root, "docs/backlog/CLOSED.md").readText(),
    "docs/backlog/CLOSED.md",
).associateBy { it.id }

// ---------- Scan code for UD-### references ----------

val codeRefs: MutableMap<String, MutableList<String>> = mutableMapOf()
for (dir in scanRoots) {
    val d = File(root, dir)
    if (!d.isDirectory) continue
    d.walk()
        .filter { it.isFile }
        .filter { f ->
            // Skip binary-ish and build artefacts we already excluded.
            f.extension !in setOf("jar", "class", "so", "dll", "exe", "pdb", "png", "ico", "svg", "zip", "gz")
        }
        .forEach { f ->
            val rel = f.relativeTo(root).path.replace('\\', '/')
            // Don't count backlog files themselves as code refs (they define the IDs).
            if (rel.startsWith("docs/backlog/")) return@forEach
            try {
                f.useLines { lines ->
                    lines.forEachIndexed { i, line ->
                        for (m in idPattern.findAll(line)) {
                            val id = "UD-${m.groupValues[1]}"
                            codeRefs.getOrPut(id) { mutableListOf() }
                                .add("$rel:${i + 1}")
                        }
                    }
                }
            } catch (_: Exception) { /* skip unreadable */ }
        }
}

// ---------- Checks ----------

val orphans = mutableListOf<Pair<String, List<String>>>()
val staleClosed = mutableListOf<Pair<String, List<String>>>()
val anchorless = mutableListOf<Pair<String, String>>()
val abandoned = mutableListOf<String>()

for ((id, refs) in codeRefs) {
    when {
        openItems.containsKey(id) -> {}
        closedItems.containsKey(id) -> staleClosed += id to refs
        else -> orphans += id to refs
    }
}

for ((id, item) in openItems) {
    val codeRefsField = item.fields["code_refs"].orEmpty()
    val paths = Regex("""-\s*([^\n]+)""").findAll(codeRefsField).map { it.groupValues[1].trim() }.toList()
    for (p in paths) {
        val cleaned = p.substringBefore(':').trim()
        if (cleaned.isEmpty()) continue
        if (!File(root, cleaned).exists()) {
            anchorless += id to cleaned
        }
    }
    if (paths.isEmpty() && item.fields["status"] in listOf("open", null)) {
        val opened = item.fields["opened"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (opened != null && ChronoUnit.DAYS.between(opened, LocalDate.now()) > 30L) {
            abandoned += id
        }
    }
}

// ---------- Report ----------

fun header(s: String) { println("\n=== $s ===") }

header("Backlog inventory")
println("Open:   ${openItems.size}")
println("Closed: ${closedItems.size}")
println("Code refs found: ${codeRefs.values.sumOf { it.size }} across ${codeRefs.size} distinct IDs")

var hardFail = false

if (orphans.isNotEmpty()) {
    hardFail = true
    header("ORPHAN code refs (ID not in BACKLOG.md or CLOSED.md)")
    for ((id, refs) in orphans.sortedBy { it.first }) {
        println("  $id")
        refs.take(5).forEach { println("    $it") }
        if (refs.size > 5) println("    ... (${refs.size - 5} more)")
    }
}

if (staleClosed.isNotEmpty()) {
    hardFail = true
    header("STALE CLOSED (ID in CLOSED.md still referenced in source)")
    for ((id, refs) in staleClosed.sortedBy { it.first }) {
        println("  $id")
        refs.take(5).forEach { println("    $it") }
    }
}

if (anchorless.isNotEmpty()) {
    header("WARN: anchorless open items (code_refs point to missing files)")
    for ((id, p) in anchorless.sortedBy { it.first }) println("  $id → $p")
}

if (abandoned.isNotEmpty()) {
    header("WARN: abandoned (open, no code_refs, > 30 days old)")
    abandoned.sorted().forEach { println("  $it") }
}

if (!hardFail) println("\nOK: no hard errors.") else println("\nFAIL: hard errors above.")
System.exit(if (hardFail) 1 else 0)
