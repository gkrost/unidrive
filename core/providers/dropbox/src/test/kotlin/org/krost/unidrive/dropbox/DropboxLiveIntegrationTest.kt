package org.krost.unidrive.dropbox

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The live Dropbox checks (#867): run them by hand with a TEST app and account — never a real one:
 * ```
 * UNIDRIVE_INTEGRATION_TESTS=true UNIDRIVE_TEST_ACCESS_TOKEN=... ./gradlew :providers:dropbox:test --tests '*DropboxLiveIntegrationTest'
 * ```
 * Everything is skipped unless both the integration gate and a Dropbox OAuth access token are set.
 *
 * Two open questions the issue asked to check live — answer them in comments HERE when the class has run:
 * 1. Does a deleted folder report its CHILDREN's deletions in the delta, or only its own deletion entry?
 *    (Delete a folder with files inside, then read the delta from the cursor before the delete and record
 *    which paths come back as deleted entries.)
 * 2. Does `move_v2` accept a case-only rename directly (`/readme.txt` → `/Readme.txt`)? The provider goes
 *    through a temporary name today; if Dropbox accepts the direct move, that detour can go.
 */
class DropboxLiveIntegrationTest {
    private lateinit var dir: Path
    private lateinit var provider: DropboxProvider

    private val token: String? = System.getenv("UNIDRIVE_TEST_ACCESS_TOKEN")

    @BeforeTest
    fun setUp() {
        assumeTrue(
            "live Dropbox calls need UNIDRIVE_INTEGRATION_TESTS=true and a TEST access token",
            System.getenv("UNIDRIVE_INTEGRATION_TESTS") == "true" && !token.isNullOrBlank(),
        )
        dir = Files.createTempDirectory("dropbox-live")
        // The live flow signs in with the token the runner exported; the auth surface itself is P34's.
        provider = DropboxProvider(DropboxConfig(appKey = System.getenv("UNIDRIVE_TEST_APP_KEY") ?: "", tokenPath = dir))
        runBlocking { provider.authenticate() }
    }

    @AfterTest
    fun tearDown() {
        // The assume in setUp aborts before dir exists when the live environment is missing;
        // tearing down nothing must not fail the skip.
        if (this::dir.isInitialized) dir.toFile().deleteRecursively()
    }

    @Test
    fun `delta from null lists the account and an incremental cursor answers`() =
        runBlocking {
            // delta(null) is a full listing; the cursor answers incrementally.
            val full = provider.delta(null)
            assertTrue(full.items.isNotEmpty() || full.cursor != null, "a full listing with a cursor")
            val incremental = provider.delta(full.cursor)
            assertTrue(incremental.items.isEmpty(), "nothing changed since the full listing: ${incremental.items.size}")
        }

    @Test
    fun `simple and session upload, a guarded conflict, a case-only rename and a delete behave`() =
        runBlocking {
            val small = dir.resolve("small.txt").also { Files.writeString(it, "hello") }
            provider.upload(small, "/unidrive-live/small.txt")

            val big = dir.resolve("big.bin").also { Files.write(it, ByteArray(129 * 1024 * 1024)) }
            provider.upload(big, "/unidrive-live/big.bin")

            val expected = "cafe".repeat(64) // the content hash of "hello" differs; see the live output
            try {
                provider.upload(small, "/unidrive-live/small.txt", ifMatchETag = expected)
                error("a guarded upload against a different content must conflict")
            } catch (_: org.krost.unidrive.RemoteConflictException) {
                // expected
            }

            provider.move("/unidrive-live/small.txt", "/unidrive-live/Small.txt")
            // QUESTION 2: did the case-only rename land directly (check the listing), or did the
            // temporary-name detour run? Record the wire calls here.

            provider.delete("/unidrive-live/big.bin")
            // QUESTION 1: delete the whole folder next, then read the delta from a cursor taken before
            // the delete, and record which paths come back deleted (the folder only, or the children too).
            provider.delete("/unidrive-live/small.txt")
        }
}
