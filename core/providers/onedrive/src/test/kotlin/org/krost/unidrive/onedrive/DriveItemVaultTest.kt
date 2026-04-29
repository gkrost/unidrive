package org.krost.unidrive.onedrive

import org.krost.unidrive.onedrive.model.DriveItem
import org.krost.unidrive.onedrive.model.FileDetails
import org.krost.unidrive.onedrive.model.FolderDetails
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * UD-315: unit tests for the `DriveItem.isPersonalVault` discriminator.
 *
 * The rule must catch OneDrive's facet-less Personal Vault stub in two ways:
 *   (a) by locale-specific display name (en, de, fr, es, it, pt-BR, ja, zh-Hans)
 *   (b) by the zero-facets + size=0 signature, so future-locale vaults still get filtered
 *
 * It must NOT false-positive on ordinary empty folders (which carry `folder != null`).
 */
class DriveItemVaultTest {
    @Test
    fun `no-facets zero-size stub matches the fallback rule`() {
        val vault =
            DriveItem(
                id = "vault-id",
                name = "SomeUnknownLocaleVault",
                size = 0,
                folder = null,
                file = null,
            )
        assertTrue(vault.isPersonalVault, "Zero-facet + size=0 stub must be detected as vault")
    }

    @Test
    fun `german locale name matches`() {
        val vault =
            DriveItem(
                id = "vault-de",
                name = "Persönlicher Tresor",
                size = 0,
                folder = null,
                file = null,
            )
        assertTrue(vault.isPersonalVault, "German 'Persönlicher Tresor' must match")
    }

    @Test
    fun `english locale name matches`() {
        val vault =
            DriveItem(
                id = "vault-en",
                name = "Personal Vault",
                size = 0,
                folder = null,
                file = null,
            )
        assertTrue(vault.isPersonalVault, "English 'Personal Vault' must match")
    }

    @Test
    fun `name match is case insensitive`() {
        val vault =
            DriveItem(
                id = "vault-mixed",
                name = "personal VAULT",
                size = 0,
                folder = null,
                file = null,
            )
        assertTrue(vault.isPersonalVault, "Case-insensitive name match expected")
    }

    @Test
    fun `romance and asian locales match`() {
        listOf(
            "Coffre-fort personnel",
            "Almacén personal",
            "Cassaforte personale",
            "Cofre Pessoal",
            "個人用 Vault",
            "个人保管库",
        ).forEach { localizedName ->
            val vault =
                DriveItem(
                    id = "vault-$localizedName",
                    name = localizedName,
                    size = 0,
                    folder = null,
                    file = null,
                )
            assertTrue(vault.isPersonalVault, "Locale name '$localizedName' must match")
        }
    }

    @Test
    fun `ordinary empty folder is NOT flagged as vault`() {
        val emptyFolder =
            DriveItem(
                id = "folder-id",
                name = "My Documents",
                size = 0,
                folder = FolderDetails(childCount = 0),
                file = null,
            )
        assertFalse(emptyFolder.isPersonalVault, "Empty folder with folder facet is not a vault")
    }

    @Test
    fun `zero-byte file is NOT flagged as vault`() {
        val emptyFile =
            DriveItem(
                id = "file-id",
                name = "empty.txt",
                size = 0,
                folder = null,
                file = FileDetails(mimeType = "text/plain"),
            )
        assertFalse(emptyFile.isPersonalVault, "Zero-byte file with file facet is not a vault")
    }

    @Test
    fun `nameless facet-less stub is NOT flagged`() {
        // Defensive: don't misclassify a genuinely broken Graph response with null name.
        val weird =
            DriveItem(
                id = "weird",
                name = null,
                size = 0,
                folder = null,
                file = null,
            )
        assertFalse(weird.isPersonalVault, "Null-name stub must not match — too broad")
    }

    @Test
    fun `regular non-empty folder is NOT flagged`() {
        val folder =
            DriveItem(
                id = "folder-id",
                name = "Photos",
                size = 1024,
                folder = FolderDetails(childCount = 5),
                file = null,
            )
        assertFalse(folder.isPersonalVault, "Normal folder with children is not a vault")
    }
}
