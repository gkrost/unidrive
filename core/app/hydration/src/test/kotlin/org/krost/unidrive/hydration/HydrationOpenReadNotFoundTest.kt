package org.krost.unidrive.hydration

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.PermanentDownloadFailureException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * After the download re-resolve, a read that is STILL not-found means the
 * remote item is genuinely gone. It used to surface to the mount as a
 * catch-all EIO; it must now surface as a STABLE typed `not_found` token so
 * the Rust co-daemon can map it to ENOENT instead of EIO.
 *
 * The token string `not_found` is a wire contract shared verbatim with the
 * mount crate's `ipc_error_to_errno`. Two orthogonal invariants:
 *  1. A genuinely-gone read (provider download fails not-found) surfaces
 *     `OpenResult.Failed` whose error message is exactly `not_found`, for
 *     both the typed provider-agnostic [PermanentDownloadFailureException]
 *     and a provider exception carrying `statusCode == 404`.
 *  2. A non-not-found download failure still surfaces a generic error (NOT
 *     `not_found`) so the catch-all EIO mapping is preserved.
 */
class HydrationOpenReadNotFoundTest {
    /** Local stand-in for a provider exception that carries an HTTP status code
     *  (mirrors OneDrive's GraphApiException, which the hydration module cannot
     *  reference directly — it is provider-agnostic). */
    private class FakeStatusException(message: String, @Suppress("unused") val statusCode: Int) :
        RuntimeException(message)

    @Test
    fun `open_for_read_of_a_gone_file_returns_typed_not_found`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertUnhydratedEntry("/gone.txt", remoteSize = 5)
        env.syncEngine.makeNextDownloadThrow(
            PermanentDownloadFailureException("remote object is gone (stable 404)"),
        )

        val result = env.hydration.openForRead("conn1", "h1", "/gone.txt")

        assertTrue(result is OpenResult.Failed, "a genuinely-gone read must fail: $result")
        assertEquals(
            "not_found",
            (result as OpenResult.Failed).error.message,
            "a genuinely-gone read must surface the stable not_found token",
        )
    }

    @Test
    fun `open_for_read_of_a_404_provider_failure_returns_typed_not_found`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertUnhydratedEntry("/gone404.txt", remoteSize = 5)
        env.syncEngine.makeNextDownloadThrow(
            FakeStatusException("itemNotFound: The resource could not be found.", statusCode = 404),
        )

        val result = env.hydration.openForRead("conn1", "h1", "/gone404.txt")

        assertTrue(result is OpenResult.Failed, "a 404 provider download must fail: $result")
        assertEquals(
            "not_found",
            (result as OpenResult.Failed).error.message,
            "a provider 404 must surface the stable not_found token",
        )
    }

    @Test
    fun `open_for_read_non_not_found_failure_stays_generic`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertUnhydratedEntry("/flaky.txt", remoteSize = 5)
        // A 503 (transient server error) is NOT a genuine not-found.
        env.syncEngine.makeNextDownloadThrow(
            FakeStatusException("serviceUnavailable", statusCode = 503),
        )

        val result = env.hydration.openForRead("conn1", "h1", "/flaky.txt")

        assertTrue(result is OpenResult.Failed)
        assertTrue(
            (result as OpenResult.Failed).error.message != "not_found",
            "a non-not-found failure must NOT be tagged not_found (it maps to EIO): ${result.error.message}",
        )
    }

    @Test
    fun `open_read IPC reply carries the not_found token verbatim`() = runTest {
        val env = HydrationTestEnv()
        env.stateDb.insertUnhydratedEntry("/wire.txt", remoteSize = 5)
        env.syncEngine.makeNextDownloadThrow(
            PermanentDownloadFailureException("gone"),
        )
        val handler = HydrationIpcHandler(env.hydration)

        val reply = handler.handle(
            "conn1",
            """{"verb":"hydration.open_read","handle_id":"h1","path":"/wire.txt"}""",
        )

        assertEquals("""{"ok":false,"error":"not_found"}""", reply.trim())
    }
}
