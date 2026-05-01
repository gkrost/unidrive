package org.krost.unidrive.auth

import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * UD-344: covers the lifted credential-store invariants.
 *
 * The `validate` hook mirrors OneDrive's `hasPlausibleAccessTokenShape`
 * UD-312 lineage; the atomic-write invariant is asserted weakly via
 * "no `.tmp` residue after save" — a stronger concurrent-reader race
 * harness lives in OneDrive's `OAuthServiceTokenShapeTest` and exists
 * for that wire-format only.
 */
class CredentialStoreTest {
    @Serializable
    data class Sample(
        val accessToken: String,
        val expiresAt: Long,
    )

    private lateinit var dir: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("unidrive-credstore-test")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun store(validate: (Sample) -> Boolean = { true }): CredentialStore<Sample> =
        CredentialStore(dir, "creds.json", serializer<Sample>(), validate)

    @Test
    fun `load returns null when no file exists`() {
        assertNull(store().load())
    }

    @Test
    fun `save then load round-trips the value`() {
        val s = store()
        val saved = Sample(accessToken = "abc.def.ghi", expiresAt = 12345L)
        s.save(saved)
        val loaded = s.load()
        assertNotNull(loaded)
        assertEquals(saved, loaded)
    }

    @Test
    fun `load returns null on corrupt JSON`() {
        Files.writeString(dir.resolve("creds.json"), "not json at all")
        assertNull(store().load())
    }

    @Test
    fun `load discards a value that fails the validate hook`() {
        val s =
            store(validate = { it.accessToken.length >= 32 })
        s.save(Sample(accessToken = "too-short", expiresAt = 0L))
        // Saved fine (we trust the caller for save-time correctness),
        // but on load the validate hook discards it.
        assertNull(s.load())
    }

    @Test
    fun `load accepts a value that passes the validate hook`() {
        val s = store(validate = { it.accessToken.length >= 32 })
        val sample = Sample(accessToken = "x".repeat(64), expiresAt = 0L)
        s.save(sample)
        assertEquals(sample, s.load())
    }

    @Test
    fun `save leaves no tmp residue after a successful atomic move`() {
        store().save(Sample(accessToken = "x".repeat(64), expiresAt = 0L))
        assertFalse(
            Files.exists(dir.resolve("creds.json.tmp")),
            "creds.json.tmp must not linger — atomic move should have consumed it",
        )
        assertTrue(Files.exists(dir.resolve("creds.json")))
    }

    @Test
    fun `save twice leaves the second write visible without partial state`() {
        val s = store()
        s.save(Sample(accessToken = "a".repeat(64), expiresAt = 1L))
        s.save(Sample(accessToken = "b".repeat(64), expiresAt = 2L))
        val loaded = s.load()
        assertNotNull(loaded)
        assertEquals("b".repeat(64), loaded.accessToken)
        assertEquals(2L, loaded.expiresAt)
        assertFalse(Files.exists(dir.resolve("creds.json.tmp")))
    }

    @Test
    fun `delete removes an existing file`() {
        val s = store()
        s.save(Sample(accessToken = "x".repeat(64), expiresAt = 0L))
        assertTrue(Files.exists(dir.resolve("creds.json")))
        s.delete()
        assertFalse(Files.exists(dir.resolve("creds.json")))
    }

    @Test
    fun `delete is a no-op when the file does not exist`() {
        store().delete() // no exception
    }
}
