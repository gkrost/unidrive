package org.krost.unidrive.e2e

import java.io.OutputStreamWriter
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path

object EncodingSampleFactory {

    private val PANGRAMS: Map<String, String> = mapOf(
        "UTF-8"       to "The quick brown fox jumps over the lazy dog. Ää Öö Üü ß €",
        "ISO-8859-1"  to "The quick brown fox jumps over the lazy dog. Ää Öö Üü ß",
        "Shift_JIS"   to "素早い茶色のキツネは怠け者の犬を飛び越えた。",
        "GB18030"     to "敏捷的棕色狐狸跳过了懒惰的狗。",
        "KOI8-R"      to "Быстрая коричневая лиса перепрыгнула через ленивую собаку.",
    )

    fun generate(dir: Path, encodings: List<String>) {
        Files.createDirectories(dir)
        for (enc in encodings) {
            val charset = try {
                Charset.forName(enc)
            } catch (_: Exception) {
                continue
            }
            val safeName = enc.lowercase().replace("-", "").replace(" ", "_")
            val file = dir.resolve("${safeName}_sample.txt")
            val pangram = PANGRAMS[enc] ?: "The quick brown fox jumps over the lazy dog."
            OutputStreamWriter(Files.newOutputStream(file), charset).use { it.write(pangram) }
        }
    }
}
