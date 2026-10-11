package org.krost.unidrive.internxt

import org.krost.unidrive.auth.CredentialProtectionException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import kotlin.test.BeforeTest

class LinuxSecretServiceCredentialProtectorTest {
    private val dir = Files.createTempDirectory("fake-secret-tool")
    private val state = Files.createDirectory(dir.resolve("items"))
    private val unavailable = dir.resolve("unavailable")
    private val hang = dir.resolve("hang")
    private val executable = dir.resolve("secret-tool")

    @BeforeTest
    fun setUp() {
        assumeTrue("Fake secret-tool harness requires Linux", System.getProperty("os.name").lowercase().contains("linux"))
        Files.writeString(
            executable,
            """
            #!/bin/sh
            set -eu
            [ ! -e '$unavailable' ] || exit 1
            [ ! -e '$hang' ] || exec sleep 20
            command="${'$'}1"
            shift
            case "${'$'}command" in
              store)
                revision="${'$'}7"
                cat > '$state'/"${'$'}revision"
                ;;
              lookup)
                revision="${'$'}6"
                [ -f '$state'/"${'$'}revision" ] || exit 1
                cat '$state'/"${'$'}revision"
                ;;
              clear)
                if [ "${'$'}#" -ge 6 ]; then
                  rm -f '$state'/"${'$'}6"
                else
                  rm -f '$state'/*
                fi
                ;;
              *) exit 2 ;;
            esac
            """.trimIndent(),
        )
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"))
    }

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `stores each version by reference and resolves the referenced secret`() {
        val protector = LinuxSecretServiceCredentialProtector(executable.toString())
        val value = "{\"jwt\":\"private-token\"}"
        val first = protector.protect("/profile/credentials.json", value)
        val second = protector.protect("/profile/credentials.json", "rotated")

        assertNotEquals(first, second)
        assertEquals(value, protector.decode("/profile/credentials.json", first).plaintext)
        assertEquals("rotated", protector.decode("/profile/credentials.json", second).plaintext)
        assertTrue(first.startsWith("unidrive-secret-service-v1:"))
    }

    @Test
    fun `missing or locked Secret Service fails closed`() {
        Files.createFile(unavailable)
        val protector = LinuxSecretServiceCredentialProtector(executable.toString())

        val error = assertFailsWith<CredentialProtectionException> {
            protector.protect("/profile/credentials.json", "secret")
        }

        assertTrue(error.message.orEmpty().contains("unavailable or locked"))
        assertFalse(Files.list(state).use { it.findAny().isPresent })
    }

    @Test
    fun `missing keyring item for a protected marker never falls back to marker text`() {
        val protector = LinuxSecretServiceCredentialProtector(executable.toString())
        val marker = "unidrive-secret-service-v1:00000000-0000-0000-0000-000000000001"

        assertFailsWith<CredentialProtectionException> {
            protector.decode("/profile/credentials.json", marker)
        }
    }

    @Test
    fun `hung Secret Service process is terminated and reported`() {
        Files.createFile(hang)
        val protector = LinuxSecretServiceCredentialProtector(executable.toString(), timeoutSeconds = 1)

        val error = assertFailsWith<CredentialProtectionException> {
            protector.protect("/profile/credentials.json", "secret")
        }

        assertTrue(error.message.orEmpty().contains("timed out"))
    }

    @Test
    fun `delete succeeds repeatedly when no item matches`() {
        val protector = LinuxSecretServiceCredentialProtector(executable.toString())

        protector.delete("/profile/credentials.json")
        protector.delete("/profile/credentials.json")

        assertFalse(Files.list(state).use { it.findAny().isPresent })
    }
}
