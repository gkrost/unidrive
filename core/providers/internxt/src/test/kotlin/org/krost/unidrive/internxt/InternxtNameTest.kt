package org.krost.unidrive.internxt

import kotlin.test.*

class InternxtNameTest {
    @Test
    fun `normal file gets extension appended`() {
        val name = reassemble(plainName = "document", type = "pdf")
        assertEquals("document.pdf", name)
    }

    @Test
    fun `file already has extension is not doubled`() {
        val name = reassemble(plainName = "document.pdf", type = "pdf")
        assertEquals("document.pdf", name)
    }

    @Test
    fun `null type leaves name unchanged`() {
        val name = reassemble(plainName = "README", type = null)
        assertEquals("README", name)
    }

    @Test
    fun `empty type leaves name unchanged`() {
        val name = reassemble(plainName = "LICENSE", type = "")
        assertEquals("LICENSE", name)
    }

    @Test
    fun `dotfile is handled correctly`() {
        val name = reassemble(plainName = ".hidden_file", type = null)
        assertEquals(".hidden_file", name)
    }

    @Test
    fun `multi-dot file gets extension`() {
        val name = reassemble(plainName = "file...with...dots", type = "txt")
        assertEquals("file...with...dots.txt", name)
    }

    @Test
    fun `manifest jsonl extension`() {
        val name = reassemble(plainName = "manifest.sha3-512", type = "jsonl")
        assertEquals("manifest.sha3-512.jsonl", name)
    }

    @Test
    fun `null plainName falls back to name`() {
        val name = reassemble(plainName = null, encryptedName = "encrypted123", type = "pdf")
        assertEquals("encrypted123.pdf", name)
    }

    // Helper that replicates the logic from InternxtProvider
    private fun reassemble(
        plainName: String? = null,
        encryptedName: String? = null,
        type: String? = null,
    ): String {
        val baseName = plainName ?: encryptedName ?: ""
        return if (!type.isNullOrEmpty() && !baseName.endsWith(".$type")) "$baseName.$type" else baseName
    }
}
