package org.krost.unidrive.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `trash_folder_name` in the profile section: the name of the read-only folder that shows the provider's trash. */
class SyncConfigTrashFolderNameTest {
    @Test
    fun `absent means the engine's default name, read as null here`() {
        val config = SyncConfig.parse("[providers.p]\ntype = \"internxt\"\n", "p")

        assertNull(config.trashFolderName("p"))
        assertNull(config.trashFolderName("unknown"))
    }

    @Test
    fun `a name is read per profile`() {
        val config =
            SyncConfig.parse(
                "[providers.a]\ntype = \"internxt\"\ntrash_folder_name = \"Corbeille\"\n" +
                    "[providers.b]\ntype = \"internxt\"\n",
                "a",
            )

        assertEquals("Corbeille", config.trashFolderName("a"))
        assertNull(config.trashFolderName("b"))
    }
}
