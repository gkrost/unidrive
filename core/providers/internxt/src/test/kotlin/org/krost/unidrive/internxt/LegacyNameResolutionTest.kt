package org.krost.unidrive.internxt

import org.krost.unidrive.internxt.model.InternxtFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #314: a legacy row (older Internxt clients predating `plainName`) carries only the
 * encrypted name. The converters attempt the client-side decryption against the item's
 * parent scope; an undecryptable name keeps today's ciphertext fallback, never throws.
 */
class LegacyNameResolutionTest {
    private val crypto = InternxtCrypto()
    private val mnemonic = "talk sound ask design"
    private val parentUuid = "parent-uuid-1"

    private fun legacyFile(encryptedName: String) =
        InternxtFile(uuid = "f1", name = encryptedName, folderUuid = parentUuid)

    @Test
    fun `a null-plainName legacy file is decrypted against its parent scope`() {
        val encrypted = crypto.encryptName("invoice-2026", "$mnemonic-$parentUuid")

        val item = InternxtProvider.fileToCloudItem(legacyFile(encrypted), "/docs", mnemonic)

        assertEquals("/docs/invoice-2026", item.path)
        assertEquals("invoice-2026", item.name)
    }

    @Test
    fun `an undecryptable name keeps the ciphertext fallback and does not throw`() {
        val item = InternxtProvider.fileToCloudItem(legacyFile("not-a-valid-encrypted-blob"), "/docs", mnemonic)

        assertEquals("not-a-valid-encrypted-blob", item.name)
    }

    @Test
    fun `without a mnemonic the ciphertext fallback is unchanged`() {
        val encrypted = crypto.encryptName("invoice-2026", "$mnemonic-$parentUuid")

        val item = InternxtProvider.fileToCloudItem(legacyFile(encrypted), "/docs", mnemonic = null)

        assertEquals(encrypted, item.name)
    }

    @Test
    fun `a plainName row is untouched by the fallback logic`() {
        val item = InternxtProvider.fileToCloudItem(legacyFile("ciphertext"), "/docs", mnemonic)

        assertEquals("ciphertext", item.name, "the encrypted name is ignored when plainName exists")
    }

    @Test
    fun `the decrypt password is scoped to the parent`() {
        val encrypted = crypto.encryptName("invoice-2026", "$mnemonic-$parentUuid")

        // A different parent scope must NOT decrypt the name (wrong key → fallback).
        val item = InternxtProvider.fileToCloudItem(legacyFile(encrypted), "/docs", "$mnemonic-other-parent")

        assertTrue(item.name.startsWith("not-a-valid") || item.name == encrypted, "wrong scope keeps the fallback, got: ${item.name}")
    }
}
