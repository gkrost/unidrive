package org.krost.unidrive.localfs

import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class RootAccessTest {
    @Test
    fun `without create a missing root under a writable folder passes and is not created`() {
        val base = Files.createTempDirectory("root-access")
        try {
            val missing = base.resolve("a").resolve("b")
            assertNull(RootAccess.validate(missing, create = false))
            assertFalse(Files.exists(missing))
            assertFalse(Files.exists(base.resolve("a")))
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `without create a missing root under a file is not creatable and a file root is not a directory`() {
        val base = Files.createTempDirectory("root-access")
        try {
            val file = Files.createFile(base.resolve("f"))
            assertEquals("root_not_creatable", RootAccess.validate(file.resolve("sub"), create = false))
            assertEquals("root_not_directory", RootAccess.validate(file, create = false))
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `without create an existing usable root passes`() {
        val base = Files.createTempDirectory("root-access")
        try {
            assertNull(RootAccess.validate(base, create = false))
        } finally {
            base.toFile().deleteRecursively()
        }
    }

    // The real probes against a directory the OS actually denies (DenyAccess.kt verifies the deny bites).

    @Test
    fun `a root the user cannot list is root_not_readable and nothing is created`() {
        withDenied(::denyRead) { root ->
            assertEquals("root_not_readable", RootAccess.validate(root, create = false))
            assertEquals("root_not_readable", RootAccess.validate(root, create = true))
        }
    }

    @Test
    fun `a root the user cannot write is root_not_writable and no probe file is left`() {
        withDenied(::denyWrite) { root ->
            assertEquals("root_not_writable", RootAccess.validate(root, create = false))
            assertEquals("root_not_writable", RootAccess.validate(root, create = true))
        }
    }

    /** Skips (never silently passes) where the platform cannot deny access; always restores and cleans up. */
    private fun withDenied(
        deny: (Path) -> (() -> Unit)?,
        body: (Path) -> Unit,
    ) {
        val root = Files.createTempDirectory("root-access-deny")
        var restore: (() -> Unit)? = null
        try {
            restore = deny(root)
            assumeTrue("cannot deny access on this platform/user (root, no ACL view, or elevated)", restore != null)
            body(root)
            restore!!()
            restore = null
            assertEquals(emptyList<String>(), root.toFile().list()!!.toList(), "nothing may be created or left in the root")
        } finally {
            restore?.invoke()
            root.toFile().deleteRecursively()
        }
    }
}
