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
                folderAccumulator = mutableListOf(),
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
                folderAccumulator = mutableListOf(),
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
                folderAccumulator = mutableListOf(),
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
                    folderAccumulator = mutableListOf(),
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
                folderAccumulator = mutableListOf(),
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

    @Test
    fun `harvests folders into folderAccumulator and stamps parentUuid from recursion context`() =
        runTest {
            // Tree (parentUuid intentionally null on the API objects — the
            // /folders/:uuid/content endpoint omits it on the children
            // listing, so the recursion must stamp it from context):
            //   root -> a, b
            //   a    -> c
            //   b    -> (empty)
            //   c    -> (empty)
            val tree =
                mapOf(
                    "root" to FolderContentResponse(children = listOf(folder("a", "a"), folder("b", "b"))),
                    "a" to FolderContentResponse(children = listOf(folder("c", "c"))),
                    "b" to FolderContentResponse(),
                    "c" to FolderContentResponse(),
                )
            val folders = mutableListOf<InternxtFolder>()
            InternxtProvider.collectFilesFromFoldersImpl(
                getContents = { uuid -> tree[uuid] ?: error("unexpected uuid $uuid") },
                folderUuid = "root",
                accumulator = mutableListOf(),
                folderAccumulator = folders,
                depth = 0,
                scanned = AtomicInteger(0),
                skipped = AtomicInteger(0),
                log = log,
            )
            // Root itself is NOT in the accumulator (buildFolderPath treats
            // rootUuid as "" directly); every descendant is, with its parent
            // wired up so a subsequent buildFolderPath call can walk back to root.
            assertEquals(setOf("a", "b", "c"), folders.map { it.uuid }.toSet())
            assertEquals("root", folders.first { it.uuid == "a" }.parentUuid)
            assertEquals("root", folders.first { it.uuid == "b" }.parentUuid)
            assertEquals("a", folders.first { it.uuid == "c" }.parentUuid, "c's parent is a, not root — bug if recursion context is lost")
        }

    @Test
    fun `preserves parentUuid when API already populated it`() =
        runTest {
            // If a future API revision ever DOES populate parentUuid on the
            // children listing, the recursion must not clobber it — defensive
            // pin against an "always overwrite" simplification.
            val authoritative = InternxtFolder(uuid = "a", plainName = "a", parentUuid = "root-from-api")
            val folders = mutableListOf<InternxtFolder>()
            InternxtProvider.collectFilesFromFoldersImpl(
                getContents = { uuid ->
                    when (uuid) {
                        "root" -> FolderContentResponse(children = listOf(authoritative))
                        "a" -> FolderContentResponse()
                        else -> error("unexpected $uuid")
                    }
                },
                folderUuid = "root",
                accumulator = mutableListOf(),
                folderAccumulator = folders,
                depth = 0,
                scanned = AtomicInteger(0),
                skipped = AtomicInteger(0),
                log = log,
            )
            assertEquals("root-from-api", folders.single().parentUuid)
        }

    @Test
    fun `onProgress fires once per successfully fetched folder`() =
        runTest {
            // Heartbeat plumbing: the catch block in delta() relies on
            // onProgress firing after every successful getContents so the
            // scan count climbs honestly during the slow tree walk. If a
            // future refactor stops calling onProgress, the user sees the
            // "Scanning remote changes..." line freeze for the duration of
            // the fallback (potentially hours on a large drive).
            val tree =
                mapOf(
                    "root" to FolderContentResponse(children = listOf(folder("a", "a"), folder("b", "b"))),
                    "a" to FolderContentResponse(),
                    "b" to FolderContentResponse(),
                )
            var progressTicks = 0
            InternxtProvider.collectFilesFromFoldersImpl(
                getContents = { uuid -> tree[uuid] ?: error("unexpected $uuid") },
                folderUuid = "root",
                accumulator = mutableListOf(),
                folderAccumulator = mutableListOf(),
                depth = 0,
                scanned = AtomicInteger(0),
                skipped = AtomicInteger(0),
                log = log,
                onProgress = { progressTicks++ },
            )
            assertEquals(3, progressTicks, "root + a + b = 3 successful fetches")
        }

    @Test
    fun `503 subtree does not fire onProgress for the skipped folder`() =
        runTest {
            // The skip path returns early without touching scanned or
            // onProgress — pin this so the heartbeat count doesn't lie
            // about how much progress the fallback actually made.
            var progressTicks = 0
            InternxtProvider.collectFilesFromFoldersImpl(
                getContents = { uuid ->
                    when (uuid) {
                        "root" -> FolderContentResponse(children = listOf(folder("bad", "bad")))
                        "bad" -> throw InternxtApiException("API error: 503", 503)
                        else -> error("unexpected $uuid")
                    }
                },
                folderUuid = "root",
                accumulator = mutableListOf(),
                folderAccumulator = mutableListOf(),
                depth = 0,
                scanned = AtomicInteger(0),
                skipped = AtomicInteger(0),
                log = log,
                onProgress = { progressTicks++ },
            )
            assertEquals(1, progressTicks, "only root succeeded; bad 503'd and must not tick")
        }
}
