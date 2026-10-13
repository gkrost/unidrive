package org.krost.unidrive.onedrive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OneDriveNameRulesTest {
    @Test
    fun `reports published OneDrive name restrictions`() {
        val cases = mapOf(
            "/bad:name.txt" to "name contains a character OneDrive forbids",
            "/ leading.txt" to "name starts or ends with a space",
            "/trailing .txt" to null,
            "/trailing " to "name starts or ends with a space",
            "/trailing." to "name ends with a period",
            "/...." to "name ends with a period",
            "/.lock" to "name is reserved: .lock",
            "/con.txt" to "name is a reserved device name",
            "/COM9" to "name is a reserved device name",
            "/COM10" to null,
            "/_VTI_folder/file.txt" to "name contains the reserved _vti_ token",
            "/desktop.ini" to "name is reserved: desktop.ini",
            "/forms" to "name is reserved: forms at the drive root",
            "/forms/report.txt" to null,
            "/~\$draft.docx" to "name starts with the reserved ~\$ prefix",
            "/${"x".repeat(400)}" to null,
            "/${"x".repeat(401)}" to "OneDrive cloud path exceeds 400 characters",
            "/folder/${"x".repeat(395)}" to "OneDrive cloud path exceeds 400 characters",
            "/ordinary/report.txt" to null,
        )
        cases.forEach { (path, expected) -> assertEquals(expected, OneDriveNameRules.issue(path), path) }
        assertNull(OneDriveNameRules.issue("/"))
    }
}
