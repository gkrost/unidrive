package org.krost.unidrive.localfs

import org.krost.unidrive.ConfigurationException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * #910: `profile add` must refuse an unusable `root_path` with a token the wizard can map,
 * instead of accepting the profile and failing forever on the first scan. Drive roots are
 * their own token: a two-way mirror over `X:\` would pull in the system folders and
 * everything else on the volume.
 */
class LocalFsProviderFactoryTest {
    private val factory = LocalFsProviderFactory()
    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("localfs-factory")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun create(
        root: Path,
        vararg extra: Pair<String, String>,
    ) {
        factory.create(mapOf("root_path" to root.toString()) + extra.toMap(), dir.resolve("unused-token"))
    }

    @Test
    fun `missing root_path is refused`() {
        val e = assertFailsWith<ConfigurationException> { factory.create(emptyMap(), dir.resolve("unused-token")) }
        assertTrue("root_path" in e.message, "got: ${e.message}")
    }

    @Test
    fun `a drive root is refused with root_is_drive_root unless allow_drive_root is set`() {
        val root = Path.of(System.getProperty("user.home")).root
        val e = assertFailsWith<ConfigurationException> { create(root) }
        assertTrue("root_is_drive_root" in e.message, "got: ${e.message}")
        // The override gets past the policy refusal; the access probes then decide on their own.
        try {
            create(root, "allow_drive_root" to "true")
        } catch (e2: ConfigurationException) {
            assertTrue(
                "root_is_drive_root" !in e2.message,
                "the override must bypass the policy refusal; got: ${e2.message}",
            )
        }
    }

    @Test
    fun `a path that exists but is a file is refused with root_not_directory`() {
        val file = Files.writeString(dir.resolve("a-file"), "x")
        val e = assertFailsWith<ConfigurationException> { create(file) }
        assertTrue("root_not_directory" in e.message, "got: ${e.message}")
    }

    @Test
    fun `a path that cannot be created under a file parent is refused with root_not_creatable`() {
        val file = Files.writeString(dir.resolve("blocker"), "x")
        val e = assertFailsWith<ConfigurationException> { create(file.resolve("child")) }
        assertTrue("root_not_creatable" in e.message, "got: ${e.message}")
    }

    @Test
    fun `an unreadable directory is refused with root_not_readable`() {
        val root = Files.createDirectories(dir.resolve("locked"))
        val restore = denyRead(root)
            org.junit.Assume.assumeTrue("this platform cannot deny access to a directory", restore != null)
        try {
            val e = assertFailsWith<ConfigurationException> { create(root) }
            assertTrue("root_not_readable" in e.message, "got: ${e.message}")
        } finally {
            restore?.invoke()
        }
    }

    @Test
    fun `an unwritable directory is refused with root_not_writable`() {
        val root = Files.createDirectories(dir.resolve("readonly"))
        val restore = denyWrite(root)
        org.junit.Assume.assumeTrue("this platform cannot deny access to a directory", restore != null)
        try {
            val e = assertFailsWith<ConfigurationException> { create(root) }
            assertTrue("root_not_writable" in e.message, "got: ${e.message}")
        } finally {
            restore?.invoke()
        }
    }
}
