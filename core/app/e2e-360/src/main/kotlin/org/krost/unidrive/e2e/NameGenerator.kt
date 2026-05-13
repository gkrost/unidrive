package org.krost.unidrive.e2e

import java.text.Normalizer
import kotlin.random.Random

class NameGenerator(
    private val asciiRatio: Int = 50,
    seed: Long,
) {
    private val rng = Random(seed)
    private val used = mutableSetOf<String>()

    private val asciiWords = listOf(
        "Report", "Invoice", "Notes", "Draft", "Backup", "Export", "Archive",
        "Summary", "Meeting", "Budget", "Photo", "Screenshot", "Recording",
        "Presentation", "Spreadsheet", "Letter", "Contract", "Manual",
    )

    private val unicodeWords = listOf(
        "Rechnung", "Änderung", "Übersicht", "Größe", "Straße",
        "年度報告", "テスト文書", "δοκιμή", "اختبار", "Кириллица",
        "Prélude", "café", "naïve", "résumé", "Ñoño",
        "🚀_launch", "🎵_music", "📁_folder",
    )

    /** Returns a unique, NFC-normalised file name with the given extension. */
    fun nextFileName(extension: String): String {
        repeat(100) {
            val base = pickWord()
            val candidate = Normalizer.normalize("${base}_${rng.nextInt(10000)}.$extension", Normalizer.Form.NFC)
            if (used.add(candidate)) return candidate
        }
        val fallback = "file_${System.nanoTime()}.$extension"
        used += fallback
        return fallback
    }

    /** Returns a unique, NFC-normalised folder name. */
    fun nextFolderName(): String {
        repeat(100) {
            val base = pickWord()
            val candidate = Normalizer.normalize("${base}_${rng.nextInt(10000)}", Normalizer.Form.NFC)
            if (used.add(candidate)) return candidate
        }
        val fallback = "folder_${System.nanoTime()}"
        used += fallback
        return fallback
    }

    // ── private ───────────────────────────────────────────────────────────────

    private fun pickWord(): String =
        if (rng.nextInt(100) < asciiRatio) asciiWords.random(rng)
        else unicodeWords.random(rng)
}
