package org.krost.unidrive.cli

import java.nio.charset.Charset

/**
 * UD-258 / UD-259 — select unicode vs ASCII glyphs based on stdout encoding
 * **and** Unicode block / font availability.
 *
 * UD-258 fixed the encoding side: on Windows PowerShell 5.1 (cp1252) the JVM's
 * default `System.out` encodes U+2714 / U+2601 / U+2500 as `?` before the byte
 * stream ever reaches the console. The launcher now passes
 * `-Dstdout.encoding=UTF-8`, and this object falls back to ASCII when
 * `stdout.encoding` (or `Charset.defaultCharset()`) is not a UTF variant.
 *
 * UD-259 splits "is unicode safe" into three tiers because a single boolean
 * conflates encoding with font coverage:
 *
 *   1. **Box-drawing** (U+2500–U+257F) — every monospace font since the 90s
 *      ships these. Gate on UTF-8 only.
 *   2. **Dingbats** (U+2700–U+27BF — ✓ ✗ ★ ☆) — Cascadia / Fira / JetBrains
 *      Mono ship them; legacy `conhost.exe` still renders `?` because it has
 *      no font fallback. Gate on UTF-8 + not-legacy-console.
 *   3. **Emoji + Misc Symbols** (U+2600–U+26FF + U+1F000+ — ☁ 📁) — only
 *      Windows Terminal pulls in `Segoe UI Emoji` via fallback. Gate on
 *      UTF-8 + emoji-fallback-available.
 *
 * `WT_SESSION` (set by Windows Terminal, never set by `conhost.exe`) is a
 * reliable proxy for "font fallback works" without an AWT font probe — which
 * wouldn't work in a headless CLI anyway.
 *
 * Additionally, Miscellaneous Symbols glyphs (U+2600–U+26FF) have **two**
 * Unicode-defined presentations:
 *   - **Emoji** (wide, ≈2 cells) — default when pulled from `Segoe UI Emoji`
 *     via font fallback. Cursor advances 1 cell, glyph draws ≈1.5 cells →
 *     visual overlap with the next column (e.g. the `│` separator after
 *     `☁` in `status --all`).
 *   - **Text** (narrow, 1 cell) — explicitly requested by appending the
 *     VS15 variation selector U+FE0E.
 *
 * Every Misc-Symbols glyph emitted by this renderer carries the VS15 suffix
 * so the table-column math stays honest. Fonts that don't have a
 * text-presentation form will show a `.notdef` box; recommend JetBrains
 * Mono / Sarasa Mono / Maple Mono to users who want native single-width
 * rendering of U+2601 et al.
 *
 * The `UNIDRIVE_ASCII=1` env override forces the ASCII fallback across all
 * three tiers — useful for CI and log-scraping pipelines.
 */
object GlyphRenderer {
    internal var envLookup: (String) -> String? = { System.getenv(it) }
    internal var propLookup: (String) -> String? = { System.getProperty(it) }
    internal var charsetLookup: () -> Charset = { Charset.defaultCharset() }

    /** Text-presentation variation selector (VS15). Forces narrow / 1-cell rendering. */
    private const val VS15 = "\uFE0E"

    /**
     * True when stdout encoding can carry multi-byte UTF-8 sequences.
     *
     * Decision order:
     *   1. `UNIDRIVE_ASCII=1` (any non-empty value other than `0`) forces
     *      ASCII — return false.
     *   2. `System.getProperty("stdout.encoding")` — the authoritative source
     *      on JDK 18+ (JEP 400). UTF-8 / UTF-16 variants pass; everything
     *      else (cp1252, windows-1252, IBM*, etc.) fails.
     *   3. Fallback to `Charset.defaultCharset()` for older runtimes.
     *
     * Kept as a building block — the public API is the three tier predicates
     * below.
     */
    private fun isStdoutUtf8(): Boolean {
        if (isAsciiForced()) return false
        val stdoutEncoding = propLookup("stdout.encoding")
        if (stdoutEncoding != null) return isUtf(stdoutEncoding)
        return isUtf(charsetLookup().name())
    }

    /**
     * **Tier 1 — Box drawing** (U+2500–U+257F). Encoding is the only thing
     * that can break this; every monospace font has these glyphs.
     */
    fun isBoxDrawingSafe(): Boolean = isStdoutUtf8()

    /**
     * **Tier 2 — Dingbats** (U+2700–U+27BF: ✓ ✗ ★ ☆). UTF-8 plus a console
     * with font fallback. Legacy `conhost.exe` (no `WT_SESSION`) renders
     * dingbats as `?` even with UTF-8 because Consolas has no glyph and
     * conhost won't fall back to another font.
     */
    fun isDingbatsSafe(): Boolean = isStdoutUtf8() && !isLegacyConsole()

    /**
     * **Tier 3 — Emoji + Misc Symbols** (U+2600–U+26FF + U+1F000+: ☁ ⚠ ⚙ ⚡ 📁).
     * UTF-8 plus a console that does emoji-font fallback (Windows Terminal
     * via `Segoe UI Emoji`; macOS Terminal.app / iTerm2 universally; modern
     * Linux terminals — see [hasEmojiFallback]).
     */
    fun isEmojiSafe(): Boolean = isStdoutUtf8() && hasEmojiFallback()

    /**
     * True on Windows when the host is **not** Windows Terminal.
     * `conhost.exe` (Legacy Console) does not set `WT_SESSION`; Windows
     * Terminal always does. On non-Windows OSes there is no "legacy console"
     * concept — return false.
     */
    private fun isLegacyConsole(): Boolean = isWindows() && envLookup("WT_SESSION").isNullOrBlank()

    /**
     * Heuristic for "the console will fall back to an emoji font when the
     * primary monospace font lacks a glyph".
     *
     *   - **Windows**: only Windows Terminal does this. `WT_SESSION` is the
     *     marker — set unconditionally by WT, never set by `conhost.exe`.
     *   - **macOS / Linux**: Terminal.app, iTerm2, GNOME Terminal, Konsole,
     *     Alacritty, kitty, WezTerm all do emoji fallback. Bare `tty` does
     *     not, but stdout-not-a-tty is already caught upstream. Return true.
     */
    private fun hasEmojiFallback(): Boolean =
        if (isWindows()) {
            !envLookup("WT_SESSION").isNullOrBlank()
        } else {
            true
        }

    private fun isWindows(): Boolean = (propLookup("os.name") ?: "").lowercase().contains("win")

    private fun isAsciiForced(): Boolean {
        val value = envLookup("UNIDRIVE_ASCII") ?: return false
        return value.isNotEmpty() && value != "0"
    }

    private fun isUtf(name: String): Boolean {
        val normalised = name.uppercase().replace("-", "")
        return normalised.startsWith("UTF8") || normalised.startsWith("UTF16")
    }

    // ── Status badges (Dingbats: ✓ ✗ ★ ☆) ────────────────────────────────────

    /** Success tick. Unicode U+2714 (Dingbats), ASCII `[OK]`. */
    fun tick(): String = if (isDingbatsSafe()) "\u2714" else "[OK]"

    /** Failure cross. Unicode U+2718 (Dingbats), ASCII `[X]`. */
    fun cross(): String = if (isDingbatsSafe()) "\u2718" else "[X]"

    /** Filled / empty star for ratings (Dingbats). */
    fun starFilled(): String = if (isDingbatsSafe()) "\u2605" else "*"

    fun starEmpty(): String = if (isDingbatsSafe()) "\u2606" else "."

    /** En-dash placeholder. Unicode U+2013 (General Punctuation), ASCII `-`. */
    fun dash(): String = if (isBoxDrawingSafe()) "\u2013" else "-"

    /**
     * Horizontal ellipsis. Unicode U+2026 (General Punctuation), ASCII `...`.
     * Encoding-only gate — every monospace font ships this glyph.
     */
    fun ellipsis(): String = if (isBoxDrawingSafe()) "\u2026" else "..."

    // ── Misc Symbols (☁ ⚠ ⚙ ⚡) — emoji tier, all carry VS15 ─────────────────

    /** Warning sign. Unicode U+26A0 + VS15 (text presentation), ASCII `[!]`. */
    fun warn(): String = if (isEmojiSafe()) "\u26A0$VS15" else "[!]"

    /** Provider-online cloud. Unicode U+2601 + VS15, ASCII `[CLD]`. */
    fun cloud(): String = if (isEmojiSafe()) "\u2601$VS15" else "[CLD]"

    /** Gear / settings. Unicode U+2699 + VS15, ASCII `[CFG]`. */
    fun gear(): String = if (isEmojiSafe()) "\u2699$VS15" else "[CFG]"

    /** High voltage / activity. Unicode U+26A1 + VS15, ASCII `[!]`. */
    fun lightning(): String = if (isEmojiSafe()) "\u26A1$VS15" else "[!]"

    // ── Box drawing (U+2500–U+257F) — encoding-only gate ─────────────────────

    fun boxHorizontal(): String = if (isBoxDrawingSafe()) "\u2500" else "-"

    fun boxVertical(): String = if (isBoxDrawingSafe()) "\u2502" else "|"

    fun boxTopLeft(): String = if (isBoxDrawingSafe()) "\u250c" else "+"

    fun boxTopRight(): String = if (isBoxDrawingSafe()) "\u2510" else "+"

    fun boxBottomLeft(): String = if (isBoxDrawingSafe()) "\u2514" else "+"

    fun boxBottomRight(): String = if (isBoxDrawingSafe()) "\u2518" else "+"

    fun boxTeeDown(): String = if (isBoxDrawingSafe()) "\u252c" else "+"

    fun boxTeeUp(): String = if (isBoxDrawingSafe()) "\u2534" else "+"

    fun boxTeeRight(): String = if (isBoxDrawingSafe()) "\u251c" else "+"

    fun boxTeeLeft(): String = if (isBoxDrawingSafe()) "\u2524" else "+"

    fun boxCross(): String = if (isBoxDrawingSafe()) "\u253c" else "+"

    // ── Tree connectors (Box drawing) ────────────────────────────────────────

    /** Mid-branch connector. Unicode `├─`, ASCII `+--`. */
    fun treeBranch(): String = if (isBoxDrawingSafe()) "\u251c\u2500" else "+--"

    /** Last-branch connector. Unicode `└─`, ASCII `+--`. */
    fun treeLast(): String = if (isBoxDrawingSafe()) "\u2514\u2500" else "+--"

    // ── Composite labels ─────────────────────────────────────────────────────

    /** `[<tick> OK]` or `[OK]`. */
    fun okLabel(): String = if (isDingbatsSafe()) "[\u2714 OK]" else "[OK]"

    /** `[<cross> AUTH]` or `[AUTH]`. */
    fun authFailLabel(): String = if (isDingbatsSafe()) "[\u2718 AUTH]" else "[AUTH]"

    /** `[<cross> ERR]` or `[ERR]`. */
    fun errLabel(): String = if (isDingbatsSafe()) "[\u2718 ERR]" else "[ERR]"

    /** `[<dash> <dash>]` or `[--]`. */
    fun inactiveLabel(): String = if (isBoxDrawingSafe()) "[\u2013 \u2013]" else "[--]"

    /** `[<warn> <msg>]` or `[! <msg>]`. */
    fun warnLabel(msg: String): String = if (isEmojiSafe()) "[\u26A0$VS15 $msg]" else "[! $msg]"

    /**
     * `[? ORPHAN]` status label for a profile directory that has no matching
     * `[providers.*]` section in config.toml (#117). A question-mark is used
     * because the provider type is unknown (no config.toml declaration to read
     * it from), making ticked-OK, crossed-AUTH, and other typed glyphs wrong.
     */
    fun orphanLabel(): String = "[? ORPHAN]"

    // ── Compatibility shim ───────────────────────────────────────────────────

    /**
     * Legacy single-knob predicate kept for backward compatibility with any
     * out-of-tree caller wired before UD-259. Returns the most permissive
     * tier (emoji-safe). New code should use the tier-specific predicates
     * above.
     */
    @Deprecated(
        "Use isBoxDrawingSafe() / isDingbatsSafe() / isEmojiSafe() per glyph family.",
        ReplaceWith("isEmojiSafe()"),
    )
    fun isUnicodeSafeStdout(): Boolean = isEmojiSafe()
}
