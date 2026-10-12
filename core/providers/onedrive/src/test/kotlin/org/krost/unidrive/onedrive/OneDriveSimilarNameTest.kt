package org.krost.unidrive.onedrive

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.NameRefusedException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * OneDrive stores names exactly but judges the uniqueness of siblings on a key that ignores some
 * characters (N'Ko, Tifinagh, Vai, Ethiopic, Adlam, Syriac ...). A second sibling whose name differs from an
 * existing one only in such characters is refused with 409 nameAlreadyExists, whatever the conflict behaviour.
 * The provider recognises that refusal as its own class: it does not retry it, it lands the entry under a
 * stable disambiguated server name, and when that is refused too it raises [NameRefusedException].
 *
 * The drive is [SimilarNameGraph]: the two measured 409 shapes, a configurable set of ignored code points.
 */
class OneDriveSimilarNameTest {
    private lateinit var tmp: Path

    // Tifinagh (U+2D5C) and Vai (U+A559): both ignored, so "g2-c-<either>.txt" is the same key as "g2-c-.txt".
    private val tifinagh = "g2-c-ⵜ.txt"
    private val vai = "g2-c-ꕙ.txt"

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("ud-similar-name")
    }

    @AfterTest
    fun tearDown() {
        tmp.toFile().deleteRecursively()
    }

    private fun providerWith(graph: SimilarNameGraph): OneDriveProvider {
        // A private token folder: a large upload keeps its session there, and the default is the user's own.
        val config = OneDriveConfig(tokenPath = tmp.resolve("token"))
        val provider = OneDriveProvider(config)
        val api = GraphApiService(config = config, tokenProvider = { _ -> "test-token" })
        val httpClientField = GraphApiService::class.java.getDeclaredField("httpClient")
        httpClientField.isAccessible = true
        (httpClientField.get(api) as? io.ktor.client.HttpClient)?.close()
        httpClientField.set(api, graph.client())
        val graphApiField = OneDriveProvider::class.java.getDeclaredField("graphApi")
        graphApiField.isAccessible = true
        (graphApiField.get(provider) as? GraphApiService)?.close()
        graphApiField.set(provider, api)
        return provider
    }

    private fun local(content: String = "hello", size: Int? = null): Path {
        val f = Files.createTempFile(tmp, "src", ".bin")
        if (size != null) Files.write(f, ByteArray(size)) else Files.write(f, content.toByteArray())
        return f
    }

    private fun graphWithSibling(sibling: String = tifinagh): SimilarNameGraph =
        SimilarNameGraph().also {
            it.seed("/african", isFolder = true)
            it.seed("/african/$sibling", bytes = "old".toByteArray())
        }

    // ── files ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a similar-name refusal of a file lands it under a disambiguated name and keeps the sibling`() =
        runTest {
            val graph = graphWithSibling()
            val provider = providerWith(graph)
            val serverName = OneDriveServerNames.disambiguate(vai)

            val item = provider.upload(local(), "/african/$vai", existingRemoteId = null)

            assertTrue(item.nameOverridden, "the provider placed the file under another name than asked")
            assertEquals("/african/$serverName", item.path)
            assertEquals(serverName, item.name)
            assertNotNull(graph.find("/african/$serverName"), "landed under the disambiguated name")
            assertNull(graph.find("/african/$vai"), "the refused name is not created")
            assertEquals("old", String(graph.find("/african/$tifinagh")!!.bytes), "the sibling is untouched")
            assertEquals(
                listOf("PUT /v1.0/me/drive/root:/african/$vai:/content?conflictBehavior=fail", "PUT /v1.0/me/drive/root:/african/$serverName:/content?conflictBehavior=fail"),
                graph.requestsOf("PUT"),
                "two attempts, both with fail: never rename (it is refused too), never replace",
            )
            provider.close()
        }

    @Test
    fun `the disambiguated name is a function of the original name`() {
        val a = OneDriveServerNames.disambiguate(vai)
        assertEquals(a, OneDriveServerNames.disambiguate(vai), "same input, same name")
        assertNotEquals(a, OneDriveServerNames.disambiguate(tifinagh), "another original name gets another discriminator")
        assertTrue(Regex("^g2-c-ꕙ~ud[0-9a-f]{8}\\.txt$").matches(a), "ASCII discriminator before the extension: $a")
        assertTrue(Regex("^ⵜ~ud[0-9a-f]{8}$").matches(OneDriveServerNames.disambiguate("ⵜ")), "no extension, no dot")
        assertTrue(OneDriveServerNames.disambiguate("x".repeat(300) + ".txt").length <= 255, "stays inside the 255 limit")
        assertEquals(
            OneDriveServerNames.disambiguate("é.txt"),
            OneDriveServerNames.disambiguate("é.txt"),
            "composed and decomposed spellings of one name give one server name",
        )
    }

    @Test
    fun `a second run lands on the same server name`() =
        runTest {
            val first = providerWith(graphWithSibling()).upload(local(), "/african/$vai", existingRemoteId = null)
            val second = providerWith(graphWithSibling()).upload(local(), "/african/$vai", existingRemoteId = null)

            assertEquals(first.path, second.path)
        }

    @Test
    fun `a true duplicate file still takes the keep-both path`() =
        runTest {
            val graph = SimilarNameGraph().also { it.seed("/doc.txt", bytes = "old".toByteArray()) }
            val provider = providerWith(graph)

            val item = provider.upload(local(), "/doc.txt", existingRemoteId = null)

            assertFalse(item.nameOverridden, "an exact duplicate is the ordinary keep-both, not a name override")
            assertEquals("doc 1.txt", item.name)
            assertEquals(
                listOf("PUT /v1.0/me/drive/root:/doc.txt:/content?conflictBehavior=fail", "PUT /v1.0/me/drive/root:/doc.txt:/content?conflictBehavior=rename"),
                graph.requestsOf("PUT"),
            )
            assertEquals("old", String(graph.find("/doc.txt")!!.bytes), "never overwritten")
            provider.close()
        }

    @Test
    fun `a refusal of the disambiguated name too raises a name refusal naming the sibling, without retries`() =
        runTest {
            val graph = graphWithSibling()
            // The disambiguated name is taken by an exact duplicate: the second attempt is refused as well.
            graph.seed("/african/${OneDriveServerNames.disambiguate(vai)}", bytes = "taken".toByteArray())
            val provider = providerWith(graph)

            val refusal =
                assertFailsWith<NameRefusedException> {
                    provider.upload(local(), "/african/$vai", existingRemoteId = null)
                }

            assertEquals(NameRefusedException.SIMILAR_NAME, refusal.reason)
            assertEquals(tifinagh, refusal.sibling, "the sibling whose name OneDrive treats as the same")
            assertEquals(2, graph.requestsOf("PUT").size, "no retry storm: one attempt per name")
            assertEquals("taken", String(graph.find("/african/${OneDriveServerNames.disambiguate(vai)}")!!.bytes), "nothing overwritten")
            provider.close()
        }

    @Test
    fun `a similar-name refusal of an update raises a name refusal at once`() =
        runTest {
            // The row says the file exists remotely, but under this name only a similar sibling does.
            val graph = graphWithSibling()
            val provider = providerWith(graph)

            assertFailsWith<NameRefusedException> {
                provider.upload(local(), "/african/$vai", existingRemoteId = "some-id", ifMatchETag = "etag")
            }

            assertEquals(1, graph.requestsOf("PUT").size, "an update is never moved to another name")
            provider.close()
        }

    @Test
    fun `a large file takes the same way through its upload session`() =
        runTest {
            val graph = graphWithSibling()
            val provider = providerWith(graph)
            val serverName = OneDriveServerNames.disambiguate(vai)

            val item = provider.upload(local(size = 5 * 1024 * 1024), "/african/$vai", existingRemoteId = null)

            assertTrue(item.nameOverridden)
            assertEquals("/african/$serverName", item.path)
            assertEquals(2, graph.requestsOf("POST", "createUploadSession").size, "one session per name, no more")
            provider.close()
        }

    // ── folders ───────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a similar-name refusal of a folder lands it under a disambiguated name`() =
        runTest {
            val graph = SimilarNameGraph().also {
                it.seed("/african", isFolder = true)
                it.seed("/african/አአ", isFolder = true)
            }
            val provider = providerWith(graph)
            val serverName = OneDriveServerNames.disambiguate("ߒ")

            val item = provider.createFolder("/african/ߒ")

            assertTrue(item.nameOverridden)
            assertTrue(item.isFolder)
            assertEquals("/african/$serverName", item.path)
            assertNotNull(graph.find("/african/$serverName"))
            assertNull(graph.find("/african/ߒ"))
            assertEquals(2, graph.requestsOf("POST").size, "the refused create and the disambiguated one")
            assertEquals(1, graph.requestsOf("GET", "/children").size, "the parent is listed once to tell the refusal from a duplicate")
            provider.close()
        }

    @Test
    fun `a true duplicate folder is not treated as a similar name`() =
        runTest {
            val graph = SimilarNameGraph().also {
                it.seed("/african", isFolder = true)
                it.seed("/african/አአ", isFolder = true)
            }
            val provider = providerWith(graph)

            val failure = assertFailsWith<GraphApiException> { provider.createFolder("/african/አአ") }

            assertEquals(409, failure.statusCode)
            assertEquals(1, graph.requestsOf("POST").size, "an exact duplicate stays on the existing path: no second create")
            provider.close()
        }

    @Test
    fun `a disambiguated folder that already exists is adopted`() =
        runTest {
            val serverName = OneDriveServerNames.disambiguate("ߒ")
            val graph = SimilarNameGraph().also {
                it.seed("/african", isFolder = true)
                it.seed("/african/አአ", isFolder = true)
                it.seed("/african/$serverName", isFolder = true)
            }
            val existing = graph.find("/african/$serverName")!!
            val provider = providerWith(graph)

            val item = provider.createFolder("/african/ߒ")

            assertEquals(existing.id, item.id, "a retry after a lost response finds its own folder")
            assertTrue(item.nameOverridden)
            provider.close()
        }

    @Test
    fun `a refusal of the disambiguated folder too raises a name refusal naming the sibling`() =
        runTest {
            val serverName = OneDriveServerNames.disambiguate("ߒ")
            val graph = SimilarNameGraph().also {
                it.seed("/african", isFolder = true)
                it.seed("/african/አአ", isFolder = true)
                // A FILE holds the disambiguated name: not adoptable as a folder.
                it.seed("/african/$serverName", bytes = "x".toByteArray())
            }
            val provider = providerWith(graph)

            val refusal = assertFailsWith<NameRefusedException> { provider.createFolder("/african/ߒ") }

            assertEquals("አአ", refusal.sibling)
            assertEquals(2, graph.requestsOf("POST").size)
            provider.close()
        }

    // ── a path lookup must not reach a similar sibling ──────────────────────────────────────────

    @Test
    fun `delete, metadata and move address the exact name even when the drive resolves a similar one`() =
        runTest {
            val graph = graphWithSibling() // pathLookupByKey: GET /african/<vai> answers the Tifinagh sibling
            val provider = providerWith(graph)

            provider.delete("/african/$vai") // treated as already gone, the sibling is not deleted
            assertNotNull(graph.find("/african/$tifinagh"), "DATA LOSS: the delete reached the similar sibling")
            assertTrue(graph.requestsOf("DELETE").isEmpty())

            val missing = assertFailsWith<GraphApiException> { provider.getMetadata("/african/$vai") }
            assertEquals(404, missing.statusCode)
            assertFailsWith<GraphApiException> { provider.move("/african/$vai", "/african/other.txt") }
            assertTrue(graph.requestsOf("PATCH").isEmpty(), "DATA LOSS: the move reached the similar sibling")

            // The exact name still resolves, including a case-only difference (the drive is case-insensitive).
            assertEquals(tifinagh, provider.getMetadata("/african/$tifinagh").name)
            assertEquals(tifinagh, provider.getMetadata("/AFRICAN/G2-C-ⵜ.TXT").name)
            provider.close()
        }
}
