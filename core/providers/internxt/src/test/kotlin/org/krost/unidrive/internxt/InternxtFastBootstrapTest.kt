package org.krost.unidrive.internxt

import kotlinx.coroutines.runBlocking
import org.krost.unidrive.Capability
import org.krost.unidrive.CapabilityResult
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * UD-223 booster: fast-bootstrap parity for Internxt. The engine's
 * `gatherStreamingChanges` / `gatherRemoteChanges` opt into this path when
 * the user passes `--fast-bootstrap` on a first sync; the provider returns
 * a cursor representing "now" without enumerating, the engine stamps it,
 * and subsequent syncs receive only post-bootstrap changes.
 *
 * Verified safe for Internxt because the cursor model is a plain ISO-8601
 * `updatedAt` timestamp — `Instant.now()` is a structurally valid cursor
 * with no server round-trip needed (unlike OneDrive Graph's `?token=latest`
 * which requires a real API call). The same `rewindCursor` window the
 * delta path applies absorbs any wall-clock skew between this host and
 * Internxt's storage.
 */
class InternxtFastBootstrapTest {
    private fun provider(): InternxtProvider {
        val tmpToken = Files.createTempDirectory("unidrive-internxt-fastboot-test-")
        return InternxtProvider(InternxtConfig(tokenPath = tmpToken))
    }

    @Test
    fun `capabilities advertises FastBootstrap`() {
        assertTrue(
            Capability.FastBootstrap in provider().capabilities(),
            "Internxt must advertise FastBootstrap so SyncEngine takes the UD-223 path on --fast-bootstrap",
        )
    }

    @Test
    fun `deltaFromLatest returns Success with a current ISO-8601 cursor and no items`() =
        runBlocking {
            val before = Instant.now()
            val result = provider().deltaFromLatest()
            val after = Instant.now()

            val page =
                when (result) {
                    is CapabilityResult.Success -> result.value
                    is CapabilityResult.Unsupported ->
                        error("deltaFromLatest must succeed when FastBootstrap is declared (got Unsupported: ${result.reason})")
                }
            assertTrue(page.items.isEmpty(), "bootstrap page must not enumerate items")
            assertEquals(false, page.hasMore, "bootstrap page is single + complete; hasMore=true would re-trigger gather loop")
            assertTrue(page.complete, "bootstrap is by definition a complete enumeration of (the empty) post-bootstrap set")
            val cursor = Instant.parse(page.cursor)
            assertTrue(
                !cursor.isBefore(before) && !cursor.isAfter(after),
                "cursor must be 'now' (between $before and $after), got $cursor",
            )
        }
}
