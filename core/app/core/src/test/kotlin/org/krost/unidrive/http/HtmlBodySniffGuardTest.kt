package org.krost.unidrive.http

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * UD-340 (UD-333 lineage): pin the assertion contract for the shared
 * `text/html` body-sniff guard.
 */
class HtmlBodySniffGuardTest {
    @Test
    fun `assertNotHtml passes through octet-stream`() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        content = "binary-bytes",
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", "application/octet-stream"),
                    )
                }
            val response = HttpClient(engine).get("https://example.invalid/binary")
            // No throw expected — the guard is a no-op for non-HTML.
            assertNotHtml(response)
        }

    @Test
    fun `assertNotHtml passes through binary with no Content-Type at all`() =
        runTest {
            val engine =
                MockEngine {
                    // Some servers omit Content-Type entirely; not HTML, so let it through.
                    respond(
                        content = "no-mime-bytes",
                        status = HttpStatusCode.OK,
                        headers = headersOf(),
                    )
                }
            val response = HttpClient(engine).get("https://example.invalid/no-mime")
            assertNotHtml(response)
        }

    @Test
    fun `assertNotHtml throws on text-html with status code in message`() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        content = "<!DOCTYPE html><html><body>Throttled</body></html>",
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", "text/html"),
                    )
                }
            val response = HttpClient(engine).get("https://example.invalid/throttled")
            val e =
                assertFailsWith<IOException> {
                    assertNotHtml(response)
                }
            assertTrue(e.message!!.contains("status=200"), "expected status code in message; got: ${e.message}")
            assertTrue(e.message!!.contains("text/html"), "expected Content-Type in message; got: ${e.message}")
        }

    @Test
    fun `assertNotHtml throws on text-html with charset suffix`() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        content = "<html><body>error</body></html>",
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", "text/html; charset=UTF-8"),
                    )
                }
            val response = HttpClient(engine).get("https://example.invalid/charset")
            assertFailsWith<IOException> {
                assertNotHtml(response)
            }
        }

    @Test
    fun `assertNotHtml includes context message when provided`() =
        runTest {
            val engine =
                MockEngine {
                    respond(
                        content = "<html>captive portal login</html>",
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", "text/html"),
                    )
                }
            val response = HttpClient(engine).get("https://example.invalid/captive")
            val e =
                assertFailsWith<IOException> {
                    assertNotHtml(response, contextMsg = "Download /Pictures/foo.jpg")
                }
            assertTrue(
                e.message!!.contains("[Download /Pictures/foo.jpg]"),
                "expected context label in message; got: ${e.message}",
            )
        }

    @Test
    fun `assertNotHtml caps body snippet at 200 chars`() =
        runTest {
            // The body snippet must be bounded — pre-UD-293 fix, a CDN
            // serving a multi-GB body with text/html could OOM the
            // diagnostic path. The guard reads at most 4 KiB and trims to
            // 200 chars for the message.
            val giantHtml = "<!DOCTYPE html><html><body>" + "X".repeat(20_000) + "</body></html>"
            val engine =
                MockEngine {
                    respond(
                        content = giantHtml,
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", "text/html"),
                    )
                }
            val response = HttpClient(engine).get("https://example.invalid/giant")
            val e =
                assertFailsWith<IOException> {
                    assertNotHtml(response)
                }
            // Message structure: "...): <snippet>" — the snippet portion must be short.
            val msg = e.message!!
            val snippetStart = msg.lastIndexOf(": ") + 2
            val snippet = msg.substring(snippetStart)
            assertTrue(
                snippet.length <= 200,
                "snippet exceeds 200-char cap: length=${snippet.length}",
            )
        }
}
