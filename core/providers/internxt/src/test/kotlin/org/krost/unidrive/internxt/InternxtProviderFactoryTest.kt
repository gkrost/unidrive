package org.krost.unidrive.internxt

import kotlinx.serialization.encodeToString
import org.krost.unidrive.UnidriveJson
import org.krost.unidrive.internxt.model.InternxtCredentials
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class InternxtProviderFactoryTest {
    private val profileDir = Files.createTempDirectory("internxt-factory-test")
    private val factory = InternxtProviderFactory(credentialProtector = null)

    @AfterTest
    fun tearDown() {
        profileDir.toFile().deleteRecursively()
    }

    @Test
    fun `description identifies the stored account without authenticating`() {
        val credentials =
            InternxtCredentials(
                jwt = "jwt",
                mnemonic = "mnemonic",
                rootFolderId = "root",
                email = "person@example.com",
            )
        Files.writeString(profileDir.resolve("credentials.json"), UnidriveJson.encodeToString(credentials))

        assertEquals("internxt (person@example.com)", factory.describeConnection(emptyMap(), profileDir))
    }

    @Test
    fun `description handles missing and unreadable credentials`() {
        assertEquals("internxt (no credentials)", factory.describeConnection(emptyMap(), profileDir))
        Files.writeString(profileDir.resolve("credentials.json"), "not json")
        assertEquals("internxt (credentials unreadable)", factory.describeConnection(emptyMap(), profileDir))
    }
}
