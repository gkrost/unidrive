package org.krost.unidrive.localfs

import java.nio.file.Files
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
}
