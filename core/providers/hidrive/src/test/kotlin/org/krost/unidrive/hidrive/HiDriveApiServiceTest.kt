package org.krost.unidrive.hidrive

import kotlin.test.Test
import kotlin.test.assertEquals

class HiDriveApiServiceTest {
    private val service = HiDriveApiService(HiDriveConfig()) { "fake-token" }

    @Test
    fun `toAbsolutePath prepends home directory`() {
        assertEquals("/users/notte/Documents/file.txt", service.toAbsolutePath("/Documents/file.txt", "/users/notte"))
        assertEquals("/users/notte/file.txt", service.toAbsolutePath("/file.txt", "/users/notte"))
    }

    @Test
    fun `toAbsolutePath handles root path`() {
        assertEquals("/users/notte", service.toAbsolutePath("/", "/users/notte"))
        assertEquals("/users/notte", service.toAbsolutePath("", "/users/notte"))
    }

    @Test
    fun `toRelativePath strips home directory`() {
        assertEquals("/Documents/file.txt", service.toRelativePath("/users/notte/Documents/file.txt", "/users/notte"))
        assertEquals("/file.txt", service.toRelativePath("/users/notte/file.txt", "/users/notte"))
    }

    @Test
    fun `toRelativePath returns root for home directory itself`() {
        assertEquals("/", service.toRelativePath("/users/notte", "/users/notte"))
    }

    @Test
    fun `toRelativePath returns path unchanged if not under home`() {
        assertEquals("/other/path", service.toRelativePath("/other/path", "/users/notte"))
    }
}
