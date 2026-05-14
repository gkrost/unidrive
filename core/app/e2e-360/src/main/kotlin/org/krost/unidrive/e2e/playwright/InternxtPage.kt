package org.krost.unidrive.e2e.playwright

import com.microsoft.playwright.Page
import java.nio.file.Path

class InternxtPage(private val page: Page) : CloudProviderPage {
    override fun login() {
        page.navigate("https://drive.internxt.com/")
        val email = System.getenv("INTERNXT_EMAIL") ?: error("INTERNXT_EMAIL env required")
        val password = System.getenv("INTERNXT_PASSWORD") ?: error("INTERNXT_PASSWORD env required")
        page.fill("input[type='email']", email)
        page.fill("input[type='password']", password)
        page.click("button[type='submit']")
        page.waitForSelector("[data-testid='drive-list']",
            Page.WaitForSelectorOptions().setTimeout(60_000.0))
    }

    override fun navigateToFolder(path: String) {
        for (segment in path.split("/").filter { it.isNotBlank() }) {
            page.locator("[data-testid='drive-list'] >> text=$segment").first().dblclick()
            page.waitForTimeout(3000.0)
        }
    }

    override fun createFolder(name: String) {
        page.click("text=New folder")
        page.fill("input[placeholder]", name)
        page.keyboard().press("Enter")
        page.waitForTimeout(3000.0)
    }

    override fun uploadFile(localPath: Path) {
        val fileInput = page.locator("input[type='file'][multiple]")
        fileInput.setInputFiles(localPath)
        page.waitForTimeout(3000.0)
    }

    override fun getVisibleFiles(): List<FileInfo> {
        val items = page.locator("[data-testid='drive-list'] [data-testid='file-name']").all()
        return items.map { FileInfo(it.textContent()?.trim() ?: "", null) }
    }

    override fun fileExists(name: String): Boolean =
        page.locator("[data-testid='drive-list'] >> text=$name").count() > 0

    override fun close() {}
}
