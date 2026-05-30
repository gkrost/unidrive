package org.krost.unidrive.localfs

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.CapabilityResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalFsProviderTest {
    private fun newRoot(): Path = Files.createTempDirectory("localfs-remote")

    @Test
    fun upload_then_list_and_download_roundtrips() =
        runTest {
            val p = LocalFsProvider(newRoot())
            p.authenticate()
            assertTrue(p.isAuthenticated)

            val src = Files.createTempFile("src", ".txt")
            Files.writeString(src, "hello")
            val item = p.upload(src, "/dir/a.txt")
            assertEquals("/dir/a.txt", item.path)
            assertFalse(item.isFolder)
            assertEquals(5L, item.size)

            assertEquals(listOf("a.txt"), p.listChildren("/dir").map { it.name })

            val dest = Files.createTempDirectory("out").resolve("out.txt")
            assertEquals(5L, p.download("/dir/a.txt", dest))
            assertEquals("hello", Files.readString(dest))
        }

    @Test
    fun delta_returns_full_inventory() =
        runTest {
            val p = LocalFsProvider(newRoot())
            p.authenticate()
            p.createFolder("/sub")
            val src = Files.createTempFile("x", ".txt")
            Files.writeString(src, "y")
            p.upload(src, "/sub/x.txt")

            val page = p.delta(null)
            assertTrue(page.complete)
            assertFalse(page.hasMore)
            val paths = page.items.map { it.path }.toSet()
            assertTrue(paths.contains("/sub"), "expected the folder; got $paths")
            assertTrue(paths.contains("/sub/x.txt"), "expected the file; got $paths")
        }

    @Test
    fun delete_removes_file() =
        runTest {
            val p = LocalFsProvider(newRoot())
            p.authenticate()
            val src = Files.createTempFile("d", ".txt")
            Files.writeString(src, "z")
            p.upload(src, "/d.txt")
            assertTrue((p.verifyItemExists("/d.txt") as CapabilityResult.Success).value)
            p.delete("/d.txt")
            assertFalse((p.verifyItemExists("/d.txt") as CapabilityResult.Success).value)
        }

    @Test
    fun traversal_outside_root_is_rejected() =
        runTest {
            val p = LocalFsProvider(newRoot())
            p.authenticate()
            assertFailsWith<IllegalArgumentException> { p.getMetadata("/../escape.txt") }
        }
}
