package org.krost.unidrive.internxt

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.internxt.model.FolderContentResponse
import org.krost.unidrive.internxt.model.InternxtFile
import org.krost.unidrive.internxt.model.InternxtFolder
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * UD-361 regression coverage for the /files-fallback recursion in
 * [InternxtProvider.collectFilesFromFoldersImpl]. The recursion drives the
 * provider's `delta()` when the flat `/files` endpoint returns 500/503, and
 * it MUST NOT silently drop subtrees — see the audit at
 * docs/audits/internxt-api-vs-spi.md §4 for the full chain that ends in
 * spurious del-local actions.
 */
class InternxtFolderRecursionTest {
    private val log = LoggerFactory.getLogger(InternxtFolderRecursionTest::class.java)

    private fun file(
        uuid: String,
        name: String,
    ) = InternxtFile(uuid = uuid, plainName = name)

    private fun folder(
        uuid: String,
        plainName: String,
    ) = InternxtFolder(uuid = uuid, plainName = plainName)

    @Test
    fun `walks tree and accumulates files - happy path`() =
        runTest {
            // Tree:
            //   root -> [a (folder), file1.txt]
            //   a    -> [file2.txt, file3.txt]
            val tree =
                mapOf(
                    "root" to
                        FolderContentResponse(
                            children = listOf(folder("a", "a")),
                            files = listOf(file("f1", "file1.txt")),
                        ),
                    "a" to
                        FolderContentResponse(
                            children = emptyList(),
                            files = listOf(file("f2", "file2.txt"), file("f3", "file3.txt")),
                        ),
                )
            val acc = mutableListOf<InternxtFile>()
            val scanned = AtomicInteger(0)
            val skipped = AtomicInteger(0)
            InternxtProvider.collectFilesFromFoldersImpl(
                getContents = { uuid -> tree[uuid] ?: error("unexpected uuid $uuid") },
                folderUuid = "root",
                accumulator = acc,
                depth = 0,
                scanned = scanned,
                skipped = skipped,
                log = log,
            )
            assertEquals(3, acc.size, "all three files should be accumulated")
            assertEquals(2, scanned.get(), "root + a")
            assertEquals(0, skipped.get(), "no skips on happy path")
        }

    @Test
    fun `503 on a subtree increments skipped and continues siblings`() =
        runTest {
            // Tree:
            //   root  -> [inbox (folder), other (folder), file1.txt]
            //   inbox -> 503  (the regression scenario — /_INBOX itself fails)
            //   other -> [file2.txt]
            val acc = mutableListOf<InternxtFile>()
            val scanned = AtomicInteger(0)
            val skipped = AtomicInteger(0)
            InternxtProvider.collectFilesFromFoldersImpl(
                getContents = { uuid ->
                    when (uuid) {
                        "root" ->
                            FolderContentResponse(
                                children = listOf(folder("inbox", "_INBOX"), folder("other", "other")),
                                files = listOf(file("f1", "file1.txt")),
                            )
                        "inbox" -> throw InternxtApiException("API error: 503", 503)
                        "other" ->
                            FolderContentResponse(
                                children = emptyList(),
                                files = listOf(file("f2", "file2.txt")),
                            )
                        else -> error("unexpected uuid $uuid")
                    }
                },
                folderUuid = "root",
                accumulator = acc,
                depth = 0,
                scanned = scanned,
                skipped = skipped,
                log = log,
            )
            assertEquals(2, acc.size, "files from root + other; inbox dropped")
            assertEquals(2, scanned.get(), "root + other; inbox does not count as scanned")
            assertEquals(1, skipped.get(), "inbox should register as one skip")
        }

    @Test
    fun `500 also counts as transient skip`() =
        runTest {
            val acc = mutableListOf<InternxtFile>()
            val scanned = AtomicInteger(0)
            val skipped = AtomicInteger(0)
            InternxtProvider.collectFilesFromFoldersImpl(
                getContents = { uuid ->
                    when (uuid) {
                        "root" ->
                            FolderContentResponse(
                                children = listOf(folder("a", "a")),
                                files = emptyList(),
                            )
                        "a" -> throw InternxtApiException("API error: 500", 500)
                        else -> error("unexpected uuid $uuid")
                    }
                },
                folderUuid = "root",
                accumulator = acc,
                depth = 0,
                scanned = scanned,
                skipped = skipped,
                log = log,
            )
            assertEquals(0, acc.size)
            assertEquals(1, skipped.get())
        }

    @Test
    fun `non-transient status code propagates`() =
        runTest {
            val skipped = AtomicInteger(0)
            try {
                InternxtProvider.collectFilesFromFoldersImpl(
                    getContents = { _ -> throw InternxtApiException("API error: 401", 401) },
                    folderUuid = "root",
                    accumulator = mutableListOf(),
                    depth = 0,
                    scanned = AtomicInteger(0),
                    skipped = skipped,
                    log = log,
                )
                fail("expected InternxtApiException")
            } catch (e: InternxtApiException) {
                assertEquals(401, e.statusCode)
            }
            assertEquals(0, skipped.get(), "401 must not be counted as a transient skip")
        }

    @Test
    fun `removed or deleted children are not recursed into`() =
        runTest {
            // Children with removed=true / deleted=true / status!=EXISTS must not
            // trigger a recursive fetch — they're already tombstoned, and recursing
            // would either 404 or surface stale entries. Pin this so the `if`
            // guard at line ~394 doesn't get accidentally relaxed.
            val visited = mutableListOf<String>()
            InternxtProvider.collectFilesFromFoldersImpl(
                getContents = { uuid ->
                    visited += uuid
                    when (uuid) {
                        "root" ->
                            FolderContentResponse(
                                children =
                                    listOf(
                                        folder("alive", "alive"),
                                        InternxtFolder(uuid = "removed", plainName = "removed", removed = true),
                                        InternxtFolder(uuid = "deleted-flag", plainName = "x", deleted = true),
                                        InternxtFolder(uuid = "trashed", plainName = "x", status = "TRASHED"),
                                    ),
                                files = emptyList(),
                            )
                        "alive" -> FolderContentResponse(emptyList(), emptyList())
                        else -> error("should not recurse into $uuid")
                    }
                },
                folderUuid = "root",
                accumulator = mutableListOf(),
                depth = 0,
                scanned = AtomicInteger(0),
                skipped = AtomicInteger(0),
                log = log,
            )
            assertEquals(listOf("root", "alive"), visited)
            assertTrue("removed" !in visited)
            assertTrue("deleted-flag" !in visited)
            assertTrue("trashed" !in visited)
        }
}
