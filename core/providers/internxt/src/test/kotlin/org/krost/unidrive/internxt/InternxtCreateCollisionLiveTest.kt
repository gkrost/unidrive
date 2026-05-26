package org.krost.unidrive.internxt

import kotlinx.coroutines.runBlocking
import org.krost.unidrive.io.defaultTokenPath
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * UD-366 LIVE data-loss drive (Internxt) — Unit 1 acceptance.
 *
 * Drives the exact silent-overwrite mechanism against the real `internxt_test` account:
 * a pre-existing remote, then a NEW create (`existingRemoteId == null`) at the same path with
 * DIFFERENT bytes. The hard invariant: the pre-existing remote MUST survive untouched; the local
 * copy is kept-both under a different path.
 *
 * If this test is removed or loosened, the create-collision blind-overwrite can silently regress.
 *
 * Targets `internxt_test` explicitly (defaultTokenPath), NEVER the primary profile — the primary
 * Internxt account forbids destructive operations (see critical-high-fix-sequence Unit 8).
 *
 * Gated: requires UNIDRIVE_INTEGRATION_TESTS=true and the internxt_test credentials.
 */
class InternxtCreateCollisionLiveTest {
    private val profile = System.getenv("UNIDRIVE_LIVE_INTERNXT_PROFILE") ?: "internxt_test"
    private val tokenPath = defaultTokenPath(profile)

    private fun enabled(): Boolean {
        if (System.getenv("UNIDRIVE_INTEGRATION_TESTS")?.toBoolean() != true) return false
        return Files.exists(tokenPath.resolve("credentials.json"))
    }

    @Test
    fun `create collision preserves the pre-existing remote and keeps both`() {
        if (!enabled()) return
        val provider = InternxtProvider(InternxtConfig(tokenPath = tokenPath))
        runBlocking {
            provider.authenticate()
            val stamp = System.currentTimeMillis()
            val path = "/ud-collision-drive-$stamp.txt"
            val originalBytes = "REMOTE-ORIGINAL-$stamp-must-not-be-overwritten".toByteArray()
            val localBytes = "LOCAL-CREATE-$stamp-different-content".toByteArray()
            val a = Files.createTempFile("ud-coll-a", ".txt").also { Files.write(it, originalBytes) }
            val b = Files.createTempFile("ud-coll-b", ".txt").also { Files.write(it, localBytes) }
            var conflictPath: String? = null
            try {
                // 1. Pre-existing remote (simulates an out-of-band upload): a fresh create, no collision.
                provider.upload(a, path, existingRemoteId = null)
                // 2. THE DRIVE: a NEW create at the same path with different bytes and no lineage.
                val conflict = provider.upload(b, path, existingRemoteId = null)
                conflictPath = "/" + conflict.path.removePrefix("/")
                // 3. Data-loss invariant: the ORIGINAL path must still hold the original bytes.
                val dest = Files.createTempFile("ud-coll-dl", ".txt")
                provider.download(path, dest)
                assertContentEquals(
                    originalBytes,
                    Files.readAllBytes(dest),
                    "DATA LOSS: the pre-existing remote at $path was overwritten by the create",
                )
                Files.deleteIfExists(dest)
                // 4. Keep-both invariant: the local copy landed under a DIFFERENT path (not a silent overwrite).
                assertNotEquals(
                    path.removePrefix("/"),
                    conflict.path.removePrefix("/"),
                    "keep-both must land the local copy under a conflict path, not overwrite the original",
                )
                assertTrue(conflict.path.isNotBlank(), "keep-both copy must have a real path")
            } finally {
                runCatching { provider.delete(path) }
                conflictPath?.let { cp -> runCatching { provider.delete(cp) } }
                Files.deleteIfExists(a)
                Files.deleteIfExists(b)
            }
        }
    }
}
