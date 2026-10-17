package org.krost.unidrive.hydration

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.PermanentDownloadFailureException
import org.krost.unidrive.RemoteContentRefusedDownloadException
import org.krost.unidrive.RemoteIncompleteDownloadException
import org.krost.unidrive.RemoteObjectMissingDownloadException
import org.krost.unidrive.UnsupportedRemoteObjectDownloadException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #941: every PermanentDownloadFailureException used to be reported as `not_found`, so a file that exists in the cloud
 * (refused content, an object stored in several parts, a short stored object) showed "Failed: not found". The cause is
 * told apart by the exception's TYPE, never by its message, and each cause has its own wire token. Only the plain class
 * (a stable 404) and a provider 404 are `not_found`. The quarantine is unchanged: every cause is still a
 * [PermanentDownloadFailureException], which is what every engine quarantine site catches.
 */
class HydrationDownloadFailureCauseTest {
    /** A message that would give the file away if a token echoed it. */
    private val secretName = "Hannover Leben Nachtrag Versicherungsschein.PDF"

    private class Cause(
        val label: String,
        val failure: () -> Throwable,
        val token: String,
    )

    private val causes =
        listOf(
            Cause(
                "gone (the plain class)",
                { PermanentDownloadFailureException("Internxt bucket entry for /$secretName is permanently gone (404)") },
                "not_found",
            ),
            Cause(
                "refused content",
                {
                    RemoteContentRefusedDownloadException(
                        "Internxt shard RIPEMD-160(SHA-256) mismatch for $secretName; refusing unverified content",
                        reason = "RIPEMD-160(SHA-256) mismatch",
                    )
                },
                "content_refused: RIPEMD-160(SHA-256) mismatch",
            ),
            Cause(
                "an object stored in several parts",
                {
                    UnsupportedRemoteObjectDownloadException(
                        "multi-shard Internxt files are not supported (2 shards): /$secretName",
                        reason = "it is stored in several parts, which this client cannot read",
                    )
                },
                "unsupported_object: it is stored in several parts, which this client cannot read",
            ),
            Cause(
                "a stored object shorter than the drive reports",
                {
                    RemoteIncompleteDownloadException(
                        "Stored object for $secretName is shorter than the size the drive reports: 298844160 of 360951317 bytes",
                        storedBytes = 298_844_160L,
                        declaredBytes = 360_951_317L,
                    )
                },
                "remote_incomplete: got 298844160 of 360951317 bytes",
            ),
            Cause(
                "a listed item whose stored object is absent",
                { RemoteObjectMissingDownloadException("remote_object_missing: Internxt still lists $secretName, but its object is absent") },
                "remote_object_missing: drive item exists, but its stored cloud object is missing",
            ),
        )

    @Test
    fun `open_for_read reports each cause with its own token and never echoes a file name`() =
        runTest {
            for (cause in causes) {
                val env = HydrationTestEnv()
                env.stateDb.insertUnhydratedEntry("/cause.bin", remoteSize = 5)
                env.syncEngine.makeNextDownloadThrow(cause.failure())

                val result = env.hydration.openForRead("conn1", "h1", "/cause.bin")

                assertTrue(result is OpenResult.Failed, "${cause.label}: $result")
                assertEquals(cause.token, result.error.message, cause.label)
                assertFalse(secretName in result.error.message, "${cause.label}: the token must not echo the file name")
            }
        }

    @Test
    fun `hydrate reports each cause with the same token as open_read`() =
        runTest {
            for (cause in causes) {
                val env = HydrationTestEnv()
                env.stateDb.insertUnhydratedEntry("/cause.bin", remoteSize = 5)
                env.syncEngine.makeNextDownloadThrow(cause.failure())

                val result = env.hydration.hydrate("/cause.bin")

                assertTrue(result is HydrateResult.Failed, "${cause.label}: $result")
                assertEquals(cause.token, result.error.message, cause.label)
            }
        }

    @Test
    fun `the IPC replies carry the tokens verbatim`() =
        runTest {
            for (cause in causes) {
                val env = HydrationTestEnv()
                env.stateDb.insertUnhydratedEntry("/wire.bin", remoteSize = 5)
                env.syncEngine.makeNextDownloadThrow(cause.failure())
                val handler = HydrationIpcHandler(env.hydration)

                val reply = handler.handle("conn1", """{"verb":"hydration.open_read","handle_id":"h1","path":"/wire.bin"}""")

                assertEquals("""{"ok":false,"error":"${cause.token}"}""", reply.trim(), cause.label)
            }
        }

    // A provider 404 that is not the typed class still means gone (OneDrive's GraphApiException is read by status).
    @Test
    fun `a provider 404 stays not_found`() =
        runTest {
            val env = HydrationTestEnv()
            env.stateDb.insertUnhydratedEntry("/gone404.bin", remoteSize = 5)
            env.syncEngine.makeNextDownloadThrow(StatusException("itemNotFound", statusCode = 404))

            val result = env.hydration.openForRead("conn1", "h1", "/gone404.bin")

            assertTrue(result is OpenResult.Failed)
            assertEquals("not_found", result.error.message)
        }

    @Test
    fun `every cause is still a permanent download failure, so the quarantine is unchanged`() {
        for (cause in causes) {
            assertTrue(
                PermanentDownloadFailureException::class.java.isAssignableFrom(cause.failure().javaClass),
                "${cause.label} must stay catchable as the quarantining base type",
            )
        }
    }

    private class StatusException(
        message: String,
        @Suppress("unused") val statusCode: Int,
    ) : RuntimeException(message)
}
