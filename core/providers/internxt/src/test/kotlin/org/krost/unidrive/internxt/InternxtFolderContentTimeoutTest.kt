package org.krost.unidrive.internxt

import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A folder's content (`GET /folders/content/{uuid}`) is a listing whose cost grows with the folder: a flat folder of
 * 29,000 files needed 106 s and 31 MB on a live account. The 60 s timer cut that call three times, so the tree walk
 * skipped the folder, dropped its files and came back incomplete. The content call now runs on the listing timeouts.
 *
 * Real CIO engine against a loopback socket, with a socket timeout of half a second and an answer that takes three times
 * as long, instead of 60 s and 106 s.
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

    // The root answers at once, the big folder after [bigFolderDelayMs].
    private fun driveServer(bigFolderDelayMs: Long) =
        LoopbackServer(tls = false) { exchange ->
            val request = exchange.readHead().requestLine
            when {
                request.contains("/folders/content/root") -> exchange.respond(200, "OK", rootContent)
                request.contains("/folders/content/pdfs") -> {
                    Thread.sleep(bigFolderDelayMs)
                    exchange.respond(200, "OK", bigFolderContent)
                }
                else -> exchange.respond(404, "Not Found", "{}")
            }
        }

    // The walk a whole-drive gather falls back to, on the service under test.
    private fun walk(
        api: InternxtApiService,
        skipped: AtomicInteger,
    ): ScopedInventory =
        runBlocking {
            InternxtProvider.collectScopedInventoryImpl(
                getContents = api::getFolderContents,
                driveRootUuid = "root",
                scopeRoots = listOf("/"),
                scanned = AtomicInteger(0),
                skipped = skipped,
                log = log,
            )
        }

    @Test
    fun `a folder's content that needs longer than the default socket timeout completes on the listing timeouts`() {
        driveServer(bigFolderDelayMs = 1_500).use { server ->
            loopbackService(loopbackClient(server, socketTimeoutMs = 500), socketMs = 500, listingSocketMs = 8_000, listingRequestMs = 10_000).use { api ->
                runBlocking {
                    val content = api.getFolderContents("pdfs")

                    assertEquals(bigFolderFiles, content.files.size, "the answer came after three default socket timeouts")
                    assertEquals(1, server.connections)
                }
            }
        }
    }

    @Test
    fun `the walk includes a folder whose content needs longer than the default socket timeout instead of skipping it`() {
        driveServer(bigFolderDelayMs = 1_500).use { server ->
            loopbackService(loopbackClient(server, socketTimeoutMs = 500), socketMs = 500, listingSocketMs = 8_000, listingRequestMs = 10_000).use { api ->
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
    fun `with only the default timeout the walk skips that folder and drops its files, once, as it did`() {
        driveServer(bigFolderDelayMs = 1_500).use { server ->
            loopbackService(loopbackClient(server, socketTimeoutMs = 500), socketMs = 500, listingSocketMs = 500).use { api ->
                val skipped = AtomicInteger(0)

                val inventory = walk(api, skipped)

                assertEquals(1, skipped.get(), "the big folder is skipped, which makes the gather incomplete")
                assertEquals(listOf("t"), inventory.files.map { it.uuid }, "only the file at the top is left")
                assertEquals(2, server.connections, "the root once and the big folder once: a timer failure is not retried")
                assertTrue(inventory.folders.map { it.uuid } == listOf("pdfs"), "the folder itself is known, its content is not")
            }
        }
    }
}
