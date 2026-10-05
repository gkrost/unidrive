package org.krost.unidrive.io

import java.security.SecureRandom

private val hexRandom = SecureRandom()

/**
 * A staging sibling name for a download whose final name is [finalName]: [finalName] + [suffix]
 * when that stays inside a path component's 255-byte limit (NTFS counts characters, ext4 bytes —
 * the UTF-8 byte count is the conservative check for both), otherwise a short
 * `.ud-<8 hex>` + [suffix] name in the same folder. The long form is what every sweeper of
 * [suffix] matches (`*.unidrive-tmp`, `*.hydrating-*`), so the short form keeps the suffix — only
 * the prefix shrinks. A 255-character name (#529) cannot be staged as `name + suffix` at all
 * (13-48 extra characters put it over the limit), and failed every download, every time.
 *
 * The fallback carries 8 random hex characters so concurrent downloads of long-named files into
 * one folder do not stage onto each other.
 */
public fun stagingSiblingName(finalName: String, suffix: String): String {
    val usedBytes = finalName.toByteArray(Charsets.UTF_8).size + suffix.toByteArray(Charsets.UTF_8).size
    return if (usedBytes <= 255) {
        finalName + suffix
    } else {
        val hex = StringBuilder(8)
        repeat(4) { hex.append("%02x".format(hexRandom.nextInt(256))) }
        ".ud-$hex$suffix"
    }
}
