package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.ScanContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Every successful Graph delta call stamps `delta_last_seen` in the token directory; it is the clock
 * behind the "cursor is stale, deletions may have aged out" warning. A dry-run is a preview (it tells the
 * provider through [ScanContext.readOnly]) and must neither write that file nor restart the clock.
 */
class DeltaReadOnlyTest {
    private fun providerWithTokenDir(tokenDir: Path): OneDriveProvider {
        val config = OneDriveConfig(tokenPath = tokenDir)
        val engine =
            MockEngine { _ ->
                respond(
                    content = """{"value":[],"@odata.deltaLink":"https://graph.microsoft.com/v1.0/me/drive/root/delta?token=T"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        val provider = OneDriveProvider(config)
        val graphApi = GraphApiService(config = config, tokenProvider = { _ -> "test-token" })
        val httpClientField = GraphApiService::class.java.getDeclaredField("httpClient")
        httpClientField.isAccessible = true
        (httpClientField.get(graphApi) as? HttpClient)?.close()
        httpClientField.set(graphApi, HttpClient(engine))
        val graphApiField = OneDriveProvider::class.java.getDeclaredField("graphApi")
        graphApiField.isAccessible = true
        (graphApiField.get(provider) as? GraphApiService)?.close()
        graphApiField.set(provider, graphApi)
        return provider
    }

    private fun context(readOnly: Boolean) =
        ScanContext(resumeMarker = null, resumedItems = emptyList(), persistPage = { _, _ -> }, readOnly = readOnly)

    @Test
    fun `a read-only delta does not stamp delta_last_seen`() =
        runTest {
            val tokenDir = Files.createTempDirectory("ud-400-token")
            val provider = providerWithTokenDir(tokenDir)

            provider.delta(null, scanContext = context(readOnly = true))

            assertFalse(Files.exists(tokenDir.resolve("delta_last_seen")), "a preview must not restart the cursor-age clock")
            provider.close()
        }

    @Test
    fun `a normal delta still stamps delta_last_seen`() =
        runTest {
            val tokenDir = Files.createTempDirectory("ud-400-token")
            val provider = providerWithTokenDir(tokenDir)

            provider.delta(null, scanContext = context(readOnly = false))

            assertTrue(Files.exists(tokenDir.resolve("delta_last_seen")), "a real pass records that the delta feed was seen")
            provider.close()
        }
}
