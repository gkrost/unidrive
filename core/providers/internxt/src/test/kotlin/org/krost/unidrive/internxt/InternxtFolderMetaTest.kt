package org.krost.unidrive.internxt

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.http.HttpRetryBudget
import org.krost.unidrive.internxt.model.InternxtCredentials
import org.slf4j.LoggerFactory
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `GET /folders/{uuid}/meta` on the shapes the server sends, and the first scan that depends on it.
 *
 * After the cursor listing a first scan fetches the meta of every folder that holds live items and is not itself in the live
 * set (trashed, or the top of a tree of its own), one request each, to build the paths of what lies below it. The live
 * account answered one of them with `"parentId":null,"parentUuid":null` (HTTP 200, 507 bytes), the model took the null for
 * a number, and the one folder ended the scan for good.
 *
 * The bodies below are built after the public server source (drive-server-wip `folder.domain.ts`): a `Folder` serialised
 * in the order its constructor assigns the fields, with `parentId` and `parentUuid` typed `| null`. The live 507-byte body
 * could not be captured; the log of the failing request gives its start (`{"type":"folder","id":190068608,"parentId":null,
 * "parentUuid":null,"name":`) and the offset of the failure, and the rest here is a plausible one.
 */
class InternxtFolderMetaTest {
    private val jsonType = "Content-Type" to listOf("application/json")

    private fun serviceOn(engine: MockEngine) =
        InternxtApiService(
            InternxtConfig(credentialProtector = null),
            credentialsProvider = { _ ->
                InternxtCredentials(
                    jwt = "test-jwt",
                    mnemonic = "test-mnemonic",
                    rootFolderId = "root",
                    email = "test@example.invalid",
                )
            },
            driveBudget = HttpRetryBudget(maxConcurrency = 2, minSpacingMs = 0, stormSpacingMs = 0),
            httpClient = HttpClient(engine),
        )

    // A folder as the server serialises it. A value of null drops the key, the text "null" is the JSON null.
    private fun folderMeta(
        vararg changes: Pair<String, String?>,
    ): String {
        val fields =
            linkedMapOf<String, String?>(
                "type" to "\"folder\"",
                "id" to "190068608",
                "parentId" to "190068500",
                "parentUuid" to "\"$PARENT\"",
                "name" to "\"U2FsdGVkX19mbG9yaWRhLXBsYWludGV4dC1uYW1l\"",
                "bucket" to "\"6928426c1a2316b856c9ab81\"",
                "userId" to "4242",
                "encryptVersion" to "\"03-aes\"",
                "deleted" to "false",
                "deletedAt" to "null",
                "createdAt" to "\"2025-03-01T09:30:00.000Z\"",
                "updatedAt" to "\"2025-03-02T10:00:00.000Z\"",
                "uuid" to "\"$FOLDER\"",
                "plainName" to "\"Archive\"",
                "size" to "0",
                "removed" to "false",
                "removedAt" to "null",
                "creationTime" to "\"2025-03-01T09:30:00.000Z\"",
                "modificationTime" to "\"2025-03-02T10:00:00.000Z\"",
                "status" to "\"EXISTS\"",
            )
        changes.forEach { (key, value) -> fields[key] = value }
        return fields.entries
            .filter { it.value != null }
            .joinToString(",", "{", "}") { "\"${it.key}\":${it.value}" }
    }

    private val atTheTop = arrayOf("parentId" to "null", "parentUuid" to "null")

    private fun answering(body: String) = MockEngine { respond(body, HttpStatusCode.OK, headersOf(jsonType, "x-request-id" to listOf("srv-1"))) }

    // ---- the decoding --------------------------------------------------------------------------------------------------

    @Test
    fun `a folder meta whose parentId and parentUuid are null is the top of a tree`() =
        runTest {
            val service = serviceOn(answering(folderMeta(*atTheTop)))

            val folder = service.getFolderMeta(FOLDER)

            assertEquals(FOLDER, folder.uuid)
            assertEquals("Archive", folder.plainName)
            assertNull(folder.parentId, "no parent number is not parent number 0")
            assertNull(folder.parentUuid, "no parent is what the path building reads as the top of a tree")
            service.close()
        }

    @Test
    fun `a folder meta without the parentId key reads the same`() =
        runTest {
            val service = serviceOn(answering(folderMeta("parentId" to null, "parentUuid" to null)))

            val folder = service.getFolderMeta(FOLDER)

            assertNull(folder.parentId)
            assertNull(folder.parentUuid)
            service.close()
        }

    @Test
    fun `a folder meta with a parent keeps its numbers`() =
        runTest {
            val service = serviceOn(answering(folderMeta()))

            val folder = service.getFolderMeta(FOLDER)

            assertEquals(190068500L, folder.parentId)
            assertEquals(PARENT, folder.parentUuid)
            assertEquals("EXISTS", folder.status)
            service.close()
        }

    @Test
    fun `a null where the model has a default takes the default, on a folder and on a file`() =
        runTest {
            val unset = arrayOf("id", "size", "status", "removed", "deleted").map { it to "null" }.toTypedArray()
            val folderService = serviceOn(answering(folderMeta(*unset)))

            val folder = folderService.getFolderMeta(FOLDER)

            assertEquals(0L, folder.id)
            assertEquals(0L, folder.size)
            assertEquals("EXISTS", folder.status)
            assertFalse(folder.removed)
            assertFalse(folder.deleted)
            folderService.close()

            val file =
                """{"id":null,"uuid":"$FILE","fileId":null,"name":"enc","plainName":"a","type":"txt","size":null,"bucket":"b",
                    "folderId":null,"folderUuid":"$PARENT","status":null,"removed":null,"deleted":null}"""
            val fileService = serviceOn(answering(file))

            val read = fileService.getFileMeta(FILE)

            assertEquals(0L, read.id)
            assertEquals("0", read.size)
            assertNull(read.folderId)
            assertEquals(PARENT, read.folderUuid)
            assertEquals("EXISTS", read.status)
            assertFalse(read.removed)
            assertFalse(read.deleted)
            fileService.close()
        }

    @Test
    fun `a folder meta that cannot be read is told apart, names the server request id, quotes no body and is asked once`() =
        runTest {
            val calls = AtomicInteger()
            val engine =
                MockEngine {
                    calls.incrementAndGet()
                    respond(folderMeta("uuid" to "null", "plainName" to "\"Quarterly secrets\""), HttpStatusCode.OK, headersOf(jsonType, "x-request-id" to listOf("srv-odd")))
                }
            val service = serviceOn(engine)

            val failure = assertFailsWith<InternxtUnreadableResponse> { service.getFolderMeta(FOLDER) }

            assertEquals("srv-odd", failure.requestId)
            assertTrue(failure.message.orEmpty().contains(FOLDER), failure.message)
            assertTrue(failure.message.orEmpty().contains("uuid"), "it says which field: ${failure.message}")
            assertFalse(failure.message.orEmpty().contains("Quarterly"), "the lines of the decoder that quote the body are left out: ${failure.message}")
            assertEquals(1, calls.get(), "the same body comes back on every attempt: not retried")
            service.close()

            val html = serviceOn(MockEngine { respond("<html>maintenance</html>", HttpStatusCode.OK, headersOf("Content-Type", "text/html")) })
            val notJson = assertFailsWith<InternxtUnreadableResponse> { html.getFolderMeta(FOLDER) }
            assertNull(notJson.requestId, "no x-request-id sent: no id is made up")
            html.close()
        }

    @Test
    fun `a transport failure on a folder meta keeps the retry ladder and is no unreadable response`() =
        runTest {
            val calls = AtomicInteger()
            val service =
                serviceOn(
                    MockEngine {
                        if (calls.incrementAndGet() <= 2) {
                            respond("""{"statusCode":503}""", HttpStatusCode.ServiceUnavailable, headersOf(jsonType))
                        } else {
                            respond(folderMeta(*atTheTop), HttpStatusCode.OK, headersOf(jsonType))
                        }
                    },
                )
            assertNull(service.getFolderMeta(FOLDER).parentId, "the third attempt answered")
            assertEquals(3, calls.get())
            service.close()

            val down = AtomicInteger()
            val unavailable = serviceOn(MockEngine { down.incrementAndGet(); respond("{}", HttpStatusCode.ServiceUnavailable, headersOf(jsonType)) })
            val failure = assertFailsWith<InternxtApiException> { unavailable.getFolderMeta(FOLDER) }
            assertEquals(503, failure.statusCode)
            assertEquals(3, down.get(), "the whole ladder, as before")
            unavailable.close()
        }

    // ---- the first scan -------------------------------------------------------------------------------------------------

    private class Reply(
        val status: HttpStatusCode,
        val body: String,
        val serverRequestId: String? = null,
    )

    /** The account: one live folder with a file, and files whose folders are not live, each answered by [meta]. */
    private inner class Account(
        private val orphanFiles: List<Pair<String, String>>,
        private val meta: (uuid: String, call: Int) -> Reply,
    ) {
        val metaCalls = ConcurrentHashMap<String, AtomicInteger>()

        private fun fileRow(
            name: String,
            folder: String,
        ) = """{"id":9,"uuid":"file-$name","fileId":"fid-$name","name":"enc-$name","plainName":"$name","type":"txt","size":"12","bucket":"b",""" +
            """"folderId":90210,"folderUuid":"$folder","status":"EXISTS","modificationTime":"2025-03-02T10:00:00.000Z","updatedAt":"2025-03-02T10:00:00.000Z"}"""

        val engine =
            MockEngine { request ->
                val path = request.url.encodedPath
                when {
                    path.endsWith("/drive/folders/sync") ->
                        respond(
                            """{"folders":[${folderMeta("uuid" to "\"$LIVE\"", "plainName" to "\"Docs\"", "parentId" to "1", "parentUuid" to "\"root\"")}],"nextCursor":null}""",
                            HttpStatusCode.OK,
                            headersOf(jsonType),
                        )
                    path.endsWith("/drive/files/sync") ->
                        respond(
                            """{"files":[${(listOf("a" to LIVE) + orphanFiles).joinToString(",") { (name, folder) -> fileRow(name, folder) }}],"nextCursor":null}""",
                            HttpStatusCode.OK,
                            headersOf(jsonType),
                        )
                    else -> {
                        val uuid = Regex(".*/drive/folders/([^/]+)/meta").matchEntire(path)?.groupValues?.get(1) ?: error("unexpected request: ${request.url}")
                        val reply = meta(uuid, metaCalls.getOrPut(uuid) { AtomicInteger() }.incrementAndGet())
                        val ids = reply.serverRequestId?.let { arrayOf("x-request-id" to listOf(it)) } ?: emptyArray()
                        respond(reply.body, reply.status, headersOf(jsonType, *ids))
                    }
                }
            }
    }

    private fun provider(engine: MockEngine): InternxtProvider {
        val provider = InternxtProvider()
        val auth = InternxtProvider::class.java.getDeclaredField("authService").also { it.isAccessible = true }.get(provider)
        val credsField = auth.javaClass.getDeclaredField("credentials").also { it.isAccessible = true }
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("""{"exp":9999999999}""".toByteArray())
        credsField.set(
            auth,
            InternxtCredentials(
                jwt = "header.$payload.signature",
                mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
                rootFolderId = "root",
                email = "test@example.invalid",
            ),
        )
        val api = InternxtProvider::class.java.getDeclaredField("api").also { it.isAccessible = true }.get(provider)
        val clientField = InternxtApiService::class.java.getDeclaredField("httpClient").also { it.isAccessible = true }
        (clientField.get(api) as? HttpClient)?.close()
        clientField.set(api, HttpClient(engine))
        return provider
    }

    private fun paths(page: DeltaPage) = page.items.map { it.path }.toSet()

    private fun warningsOf(run: suspend () -> DeltaPage): Pair<DeltaPage, List<String>> {
        val logger = LoggerFactory.getLogger(InternxtProvider::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val before = logger.level
        logger.level = Level.WARN
        logger.addAppender(appender)
        try {
            var page: DeltaPage? = null
            runTest { page = run() }
            return page!! to appender.list.filter { it.level == Level.WARN }.map { it.formattedMessage }
        } finally {
            logger.detachAppender(appender)
            logger.level = before
        }
    }

    @Test
    fun `a first scan builds the paths below a folder that is the top of a tree and completes`() {
        val account = Account(listOf("b" to TOP)) { uuid, _ -> Reply(HttpStatusCode.OK, folderMeta("uuid" to "\"$uuid\"", *atTheTop)) }

        val (page, _) = warningsOf { provider(account.engine).delta(null, null, null) }

        assertEquals(setOf("/Docs", "/Docs/a.txt", "/Archive/b.txt"), paths(page), "the ancestor itself is not an item of the scan")
        assertTrue(page.complete, "nothing was left unresolved")
        assertEquals(1, account.metaCalls.getValue(TOP).get())
    }

    @Test
    fun `a first scan finishes when one folder meta cannot be read and says what it left out`() {
        val account =
            Account(listOf("b" to TOP, "c" to ODD, "d" to ODD, "e" to OLD)) { uuid, _ ->
                when (uuid) {
                    ODD -> Reply(HttpStatusCode.OK, folderMeta("uuid" to "null"), serverRequestId = "srv-odd")
                    OLD -> Reply(HttpStatusCode.OK, folderMeta("uuid" to "\"$uuid\"", "plainName" to "\"Old\"", "parentUuid" to "\"root\"", "status" to "\"TRASHED\"", "deleted" to "true"))
                    else -> Reply(HttpStatusCode.OK, folderMeta("uuid" to "\"$uuid\"", *atTheTop))
                }
            }

        val (page, warnings) = warningsOf { provider(account.engine).delta(null, null, null) }

        assertEquals(
            setOf("/Docs", "/Docs/a.txt", "/Archive/b.txt", "/Old/e.txt", "/Old"),
            paths(page),
            "everything that could be placed is delivered, the files below the unreadable folder are not guessed at",
        )
        assertEquals(
            setOf("/Old/e.txt", "/Old"),
            page.items.filter { it.trashed }.map { it.path }.toSet(),
            "the trashed folder is told by its status (HTTP 200 from the meta), and what hangs below it with it",
        )
        assertFalse(page.complete, "the result says that something stayed unresolved, so no deletion is inferred from it")
        assertEquals(1, account.metaCalls.getValue(ODD).get(), "two files lie below it and it was asked once")
        val unreadable = warnings.filter { ODD in it && "srv-odd" in it }
        assertEquals(1, unreadable.size, "logged once, with the folder uuid and the server request id: $warnings")
        assertTrue(warnings.any { it.startsWith("Internxt delta gather dropped 2 item(s)") }, "and counted: $warnings")
    }

    @Test
    fun `a first scan keeps the old behaviour for a folder meta that stays unavailable`() {
        val account = Account(listOf("b" to TOP, "c" to ODD)) { uuid, _ ->
            if (uuid == ODD) Reply(HttpStatusCode.ServiceUnavailable, "{}") else Reply(HttpStatusCode.OK, folderMeta("uuid" to "\"$uuid\"", *atTheTop))
        }

        val (page, warnings) = warningsOf { provider(account.engine).delta(null, null, null) }

        assertEquals(setOf("/Docs", "/Docs/a.txt", "/Archive/b.txt"), paths(page))
        assertFalse(page.complete)
        assertEquals(3, account.metaCalls.getValue(ODD).get(), "a transport-level failure is retried by the call, not given up on the first answer")
        assertTrue(warnings.any { "unfetchable (503)" in it && ODD in it }, "$warnings")
    }

    private companion object {
        const val FOLDER = "7a1c0e52-3b6d-4f0a-9d84-5e2f6b1c9a10"
        const val PARENT = "c3d8f1a4-2e7b-4b19-8a60-1d94e0b5f7c2"
        const val FILE = "e91b4d70-6f3a-4c28-b5d1-08a7c2e94f36"
        const val LIVE = "0f5a9c13-8d42-4e67-a1b0-3c7e92d5b84f"
        const val TOP = "52a7d0e9-1c84-4f36-9b25-a8e03f61d7c4"
        const val ODD = "b64e2f08-9a31-4d7c-8e50-17c3a9d2f6b5"
        const val OLD = "1d93c7a6-4b50-4e82-a7f9-6e08b2d41c35"
    }
}
