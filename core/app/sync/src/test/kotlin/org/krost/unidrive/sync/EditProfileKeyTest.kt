package org.krost.unidrive.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class EditProfileKeyTest {
    private fun edit(
        text: String,
        key: String,
        line: String?,
        name: String = "p",
    ): String = assertIs<ProfileKeyEdit.Edited>(editProfileKey(text, name, key, line)).text

    @Test
    fun `an existing key is replaced in place`() {
        val text = "[providers.p]\ntype = \"x\"\nlabel = \"old\"\nroot_path = \"/r\"\n"
        assertEquals("[providers.p]\ntype = \"x\"\nlabel = \"new\"\nroot_path = \"/r\"\n", edit(text, "label", "label = \"new\""))
    }

    @Test
    fun `a missing key lands after the last body line, before a sub-table and trailing blanks`() {
        val text = "[providers.p]\ntype = \"x\"\n\n[providers.p.pin_patterns]\ninclude = [\"a\"]\n"
        assertEquals(
            "[providers.p]\ntype = \"x\"\nlabel = \"L\"\n\n[providers.p.pin_patterns]\ninclude = [\"a\"]\n",
            edit(text, "label", "label = \"L\""),
        )
    }

    @Test
    fun `a key of the same name in another section or sub-table is never touched`() {
        val text = "[providers.q]\nlabel = \"q\"\n[providers.p]\ntype = \"x\"\n[providers.p.sub]\nlabel = \"sub\"\n"
        assertEquals(
            "[providers.q]\nlabel = \"q\"\n[providers.p]\ntype = \"x\"\nlabel = \"L\"\n[providers.p.sub]\nlabel = \"sub\"\n",
            edit(text, "label", "label = \"L\""),
        )
    }

    @Test
    fun `a key that merely starts with the same letters is not the key`() {
        val text = "[providers.p]\nlabelled = 1\n"
        assertEquals("[providers.p]\nlabelled = 1\nlabel = \"L\"\n", edit(text, "label", "label = \"L\""))
    }

    @Test
    fun `a null line removes the key and an absent key stays absent`() {
        assertEquals("[providers.p]\ntype = \"x\"\n", edit("[providers.p]\ntype = \"x\"\nlabel = \"L\"\n", "label", null))
        assertEquals("[providers.p]\ntype = \"x\"\n", edit("[providers.p]\ntype = \"x\"\n", "label", null))
    }

    @Test
    fun `CRLF files keep their line endings`() {
        val text = "[providers.p]\r\ntype = \"x\"\r\n"
        assertEquals("[providers.p]\r\ntype = \"x\"\r\nlabel = \"L\"\r\n", edit(text, "label", "label = \"L\""))
    }

    @Test
    fun `a section at the end of a file without a trailing newline`() {
        assertEquals("[providers.p]\ntype = \"x\"\nlabel = \"L\"", edit("[providers.p]\ntype = \"x\"", "label", "label = \"L\""))
    }

    @Test
    fun `an unknown profile and a multi-line value are reported, not edited`() {
        assertIs<ProfileKeyEdit.NoSuchProfile>(editProfileKey("[providers.q]\n", "p", "label", "label = \"L\""))
        assertIs<ProfileKeyEdit.UnsupportedLayout>(
            editProfileKey("[providers.p]\nsync_path = [\n  \"/a\",\n]\n", "p", "sync_path", "sync_path = \"/b\""),
        )
        assertIs<ProfileKeyEdit.UnsupportedLayout>(
            editProfileKey("[providers.p]\nlabel = \"\"\"a\nb\"\"\"\n", "p", "label", "label = \"L\""),
        )
    }
}
