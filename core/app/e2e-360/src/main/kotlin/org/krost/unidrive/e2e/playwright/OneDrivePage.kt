package org.krost.unidrive.e2e.playwright

import com.microsoft.playwright.Page
import java.nio.file.Path

class OneDrivePage(private val page: Page) : CloudProviderPage {
    override fun login() {
        page.navigate("https://onedrive.live.com/")
        page.waitForSelector("[data-automationid='FileList']",
            Page.WaitForSelectorOptions().setTimeout(60_000.0))
    }

    override fun navigateToFolder(path: String) {
        for (segment in path.split("/").filter { it.isNotBlank() }) {
            page.locator("[data-automationid='FileList'] >> text=$segment").first().dblclick()
            page.waitForTimeout(2000.0)
        }
    }

    override fun createFolder(name: String) {
        page.click("[data-automationid='newCommand']")
        page.click("text=Folder")
        page.fill("input[type='text']", name)
        page.keyboard().press("Enter")
        page.waitForTimeout(2000.0)
    }

    override fun uploadFile(localPath: Path) {
        val input = page.locator("input[type='file']")
        input.setInputFiles(localPath)
        page.waitForSelector(".ms-MessageBar--success",
            Page.WaitForSelectorOptions().setTimeout(30_000.0))
    }

    override fun getVisibleFiles(): List<FileInfo> {
        val rows = page.locator("[data-automationid='DetailsRow']").all()
        return rows.map { row ->
            val name = row.locator("[data-automationid='ListCell']").first().textContent() ?: ""
            FileInfo(name.trim(), null)
        }
    }

    override fun fileExists(name: String): Boolean =
        page.locator("[data-automationid='FileList'] >> text=$name").count() > 0

    override fun close() {}
}
