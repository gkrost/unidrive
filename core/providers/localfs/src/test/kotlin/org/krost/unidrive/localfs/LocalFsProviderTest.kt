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

    @Test
    fun rejects_paths_through_a_symlink_escaping_root() =
        runTest {
            val root = newRoot()
            val outside = Files.createTempDirectory("localfs-outside")
            Files.writeString(outside.resolve("secret.txt"), "secret")
            val link = root.resolve("link")
            try {
                Files.createSymbolicLink(link, outside)
            } catch (e: Exception) {
                // Windows without the symlink privilege / Developer Mode can't create one — skip.
                org.junit.Assume.assumeNoException("symlinks unsupported in this environment", e)
            }
            val p = LocalFsProvider(root)
            p.authenticate()
            assertFalse(p.delta(null).items.any { it.path == "/link" }, "delta must not emit an escaping symlink")
            assertFalse(p.listChildren("/").any { it.path == "/link" }, "listChildren must not emit an escaping symlink")
            assertFailsWith<IllegalArgumentException> { p.getMetadata("/link/secret.txt") }
        }

    @Test
    fun factory_reports_unhealthy_without_root_path() {
        val factory = LocalFsProviderFactory()
        val dir = Files.createTempDirectory("localfs-auth")
        assertFalse(factory.isAuthenticated(emptyMap(), dir))
        assertTrue(factory.isAuthenticated(mapOf("root_path" to "/some/dir"), dir))
    }

    @Test
    fun delete_does_not_follow_symlinks_out_of_root() =
        runTest {
            val root = newRoot()
            val outside = Files.createTempDirectory("localfs-outside-del")
            Files.writeString(outside.resolve("keep.txt"), "keep")
            val p = LocalFsProvider(root)
            p.authenticate()
            p.createFolder("/sub")
            try {
                Files.createSymbolicLink(root.resolve("sub").resolve("lnk"), outside)
            } catch (e: Exception) {
                org.junit.Assume.assumeNoException("symlinks unsupported in this environment", e)
            }
            p.delete("/sub")
            assertTrue(Files.exists(outside.resolve("keep.txt")), "delete must not follow a symlink outside root")
        }
}
