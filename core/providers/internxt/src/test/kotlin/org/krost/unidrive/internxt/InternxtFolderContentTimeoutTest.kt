package org.krost.unidrive.internxt

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.HttpDefaults
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A folder's content (`GET /folders/content/{uuid}`) is a listing whose cost grows with the folder: a flat folder of
 * 29,000 files needed 106 s and 31 MB on a live account. The default 60 s timer cut that call, so the tree walk skipped
 * the folder, dropped its files and came back incomplete. The content call and the sync-listing pages run on the listing
 * timeouts.
 *
 * Real CIO engine against a loopback socket over TLS, the shape of production: there the engine's own timer reads as a
 * closed connection, the 503 the walk skips (over plain HTTP it is a SocketTimeoutException and would abort the walk).
 * A socket timeout of half a second and an answer that takes three times as long, instead of 60 s and 106 s.
 */
class InternxtFolderContentTimeoutTest {
    private val log = LoggerFactory.getLogger(InternxtFolderContentTimeoutTest::class.java)

    private val bigFolderFiles = 2_000

    // drive root -> [pdfs] and top.txt; pdfs is a flat folder of many files
    private val rootContent =
        """{"children":[{"uuid":"pdfs","plainName":"pdfs","status":"EXISTS"}],""" +
            """"files":[{"uuid":"t","plainName":"top","type":"txt","size":"1","status":"EXISTS"}]}"""
    private val bigFolderContent =
        """{"children":[],"files":[""" +
            (1..bigFolderFiles).joinToString(",") { """{"uuid":"f$it","plainName":"f$it","type":"pdf","size":"1","status":"EXISTS"}""" } +
            "]}"

    // The root answers at once, the big folder and the sync page after [bigFolderDelayMs].
    private fun driveServer(bigFolderDelayMs: Long) =
        LoopbackServer(tls = true) { exchange ->
            val request = exchange.readRequestLine()
            when {
                request.contains("/folders/content/root") -> exchange.respond(200, "OK", rootContent)
                request.contains("/folders/content/pdfs") -> {
                    Thread.sleep(bigFolderDelayMs)
                    exchange.respond(200, "OK", bigFolderContent)
                }
                request.contains("/folders/pdfs/meta") -> {
                    Thread.sleep(bigFolderDelayMs)
                    exchange.respond(200, "OK", """{"uuid":"pdfs","plainName":"pdfs","status":"EXISTS"}""")
                }
                request.contains("/files/sync") -> {
                    Thread.sleep(bigFolderDelayMs)
                    exchange.respond(200, "OK", """{"files":[{"uuid":"s","plainName":"s","type":"txt","size":"1","status":"EXISTS"}],"nextCursor":null}""")
                }
                else -> exchange.respond(404, "Not Found", "{}")
            }
        }

    // The walk a whole-drive gather falls back to, on the service under test.
    private suspend fun walk(
        api: InternxtApiService,
        skipped: AtomicInteger,
    ): ScopedInventory =
        InternxtProvider.collectScopedInventoryImpl(
            getContents = api::getFolderContents,
            driveRootUuid = "root",
            scopeRoots = listOf("/"),
            scanned = AtomicInteger(0),
            skipped = skipped,
            log = log,
        )

    @Test
    fun `a folder's content that needs longer than the default socket timeout completes on the listing timeouts`() =
        runTest {
            driveServer(bigFolderDelayMs = 1_500).use { server ->
                loopbackService(loopbackClient(server, socketTimeoutMs = 500), listingSocketMs = 8_000).use { api ->
                    val content = api.getFolderContents("pdfs")

                    assertEquals(bigFolderFiles, content.files.size, "the answer came after three default socket timeouts")
                    assertEquals(1, server.connections)
                }
            }
        }

    @Test
    fun `a cheap call keeps the default socket timeout while the folder content gets the long one`() =
        runTest {
            driveServer(bigFolderDelayMs = 1_500).use { server ->
                loopbackService(loopbackClient(server, socketTimeoutMs = 500), listingSocketMs = 8_000).use { api ->
                    // the same slow answer, asked for as a metadata call: cut by the default timer
                    assertFailsWith<InternxtApiException> { api.getFolderMeta("pdfs") }
                }
            }
        }

    @Test
    fun `a sync listing page that needs longer than the default socket timeout completes on the listing timeouts`() =
        runTest {
            driveServer(bigFolderDelayMs = 1_500).use { server ->
                loopbackService(loopbackClient(server, socketTimeoutMs = 500), listingSocketMs = 8_000).use { api ->
                    val page = api.getFilesSync(updatedAt = null, cursor = null)

                    assertEquals(1, page.items.size, "the answer came after three default socket timeouts")
                    assertEquals(1, server.connections)
                }
            }
        }

    @Test
    fun `the walk includes a folder whose content needs longer than the default socket timeout instead of skipping it`() =
        runTest {
            driveServer(bigFolderDelayMs = 1_500).use { server ->
                loopbackService(loopbackClient(server, socketTimeoutMs = 500), listingSocketMs = 8_000).use { api ->
                    val skipped = AtomicInteger(0)

                    val inventory = walk(api, skipped)

                    assertEquals(0, skipped.get(), "no folder skipped: the gather is complete")
                    assertEquals(listOf("pdfs"), inventory.folders.map { it.uuid })
                    assertEquals(bigFolderFiles + 1, inventory.files.size, "the file at the top and every file of the big folder")
                    assertEquals(bigFolderFiles, inventory.files.count { it.folderUuid == "pdfs" })
                }
            }
        }

    @Test
    fun `with the listing timeout left at the default the walk skips that folder and drops its files, as it did`() =
        runTest {
            driveServer(bigFolderDelayMs = 1_500).use { server ->
                loopbackService(loopbackClient(server, socketTimeoutMs = 500), listingSocketMs = HttpDefaults.SOCKET_TIMEOUT_MS).use { api ->
                    val skipped = AtomicInteger(0)

                    val inventory = walk(api, skipped)

                    assertEquals(1, skipped.get(), "the big folder is skipped, which makes the gather incomplete")
                    assertEquals(listOf("t"), inventory.files.map { it.uuid }, "only the file at the top is left")
                    assertTrue(inventory.folders.map { it.uuid } == listOf("pdfs"), "the folder itself is known, its content is not")
                }
            }
        }
}
