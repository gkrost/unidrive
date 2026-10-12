package org.krost.unidrive.onedrive

import java.security.MessageDigest
import java.text.Normalizer

/**
 * OneDrive stores a name exactly but judges the uniqueness of siblings on a key that ignores some
 * characters (N'Ko, Tifinagh, Vai, Ethiopic, Adlam, Syriac, Canadian Syllabics, emoji; the full set is not
 * documented). Two siblings whose names differ only in such characters are "similar", and the second create
 * is refused with 409 nameAlreadyExists. The way round is a server name that carries an ASCII discriminator
 * the key cannot ignore.
 */
internal object OneDriveServerNames {
    private const val MAX_NAME_UTF16 = 255
    private const val MAX_EXTENSION = 16
    private const val TAG = "~ud"
    private const val DIGEST_BYTES = 4

    /**
     * The server name for [name]: its base, `~ud` plus eight hex digits of the SHA-256 of its NFC form,
     * then its extension (`name.txt` becomes `name~ud3f9a1c2.txt`). A function of the name alone, so a retry, a
     * second run or a lost mapping lands on the same name, and distinct names get distinct discriminators.
     * The base is cut when the result would pass the 255 character limit.
     */
    fun disambiguate(name: String): String {
        val nfc = Normalizer.normalize(name, Normalizer.Form.NFC)
        val digest = MessageDigest.getInstance("SHA-256").digest(nfc.toByteArray(Charsets.UTF_8))
        val tag = TAG + digest.take(DIGEST_BYTES).joinToString("") { "%02x".format(it) }
        val dot = nfc.lastIndexOf('.')
        val hasExtension = dot > 0 && nfc.length - dot <= MAX_EXTENSION && ' ' !in nfc.substring(dot)
        val base = if (hasExtension) nfc.substring(0, dot) else nfc
        val extension = if (hasExtension) nfc.substring(dot) else ""
        return truncated(base, MAX_NAME_UTF16 - tag.length - extension.length) + tag + extension
    }

    private fun truncated(base: String, room: Int): String {
        if (base.length <= room) return base
        var end = room
        if (end > 0 && Character.isHighSurrogate(base[end - 1])) end--
        return base.substring(0, end)
    }

    /**
     * The ASCII part of [name], lower-cased: what survives in the drive's comparison key whatever else it
     * ignores. Two names with the same skeleton are the candidates for a similar-name conflict; it names a
     * sibling for a message, it never decides anything.
     */
    fun skeleton(name: String): String = name.filter { it.code < 0x80 }.lowercase()

    /** Whether two names are the same name to the drive's exact comparison: NFC, case-insensitive. */
    fun sameName(a: String, b: String): Boolean =
        Normalizer.normalize(a, Normalizer.Form.NFC).equals(Normalizer.normalize(b, Normalizer.Form.NFC), ignoreCase = true)
}
