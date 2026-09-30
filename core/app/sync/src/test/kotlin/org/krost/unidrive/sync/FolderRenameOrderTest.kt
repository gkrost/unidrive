package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.Capability
import org.krost.unidrive.CapabilityResult
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.ProviderException
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.sync.model.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.*

/**
 * #421: renaming a synced local folder (`d1` -> `d2`, same parent) planned `mkdir-remote d2`,
 * `del-remote d1`, then the children's moves. The folder delete trashed the remote folder first,
 * so every move out of it failed with "Folder not found", the files fell out of the live remote
 * tree and the same move failed on every later sync.
 */
class FolderRenameOrderTest {
    private lateinit var db: StateDatabase
    private lateinit var dbPath: Path
    private lateinit var syncRoot: Path
    private lateinit var reconciler: Reconciler

    @BeforeTest
    fun setUp() {
        syncRoot = Files.createTempDirectory("unidrive-rename-order-test")
        dbPath = Files.createTempDirectory("unidrive-rename-order-db").resolve("state.db")
        db = StateDatabase(dbPath)
        db.initialize()
        reconciler = Reconciler(db, syncRoot, ConflictPolicy.KEEP_BOTH)
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    // ---- reconciler level: the sorted plan --------------------------------------------------------------

    private fun trackedFolder(path: String) =
        db.upsertEntry(
            SyncEntry(
                path = path,
                remoteId = "id-$path",
                remoteHash = null,
                remoteSize = 0,
                remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
                localMtime = 0,
                localSize = 0,
                isFolder = true,
                isPinned = false,
                // a folder this client created (or fully synced) is hydrated; an unhydrated folder row would
                // have its DeleteRemote stripped by dropUnhydratedFolderDeletes and hide the bug
                isHydrated = true,
                lastSynced = Instant.now(),
            ),
        )

    private fun trackedFile(
        path: String,
        size: Long = 100,
    ) = db.upsertEntry(
        SyncEntry(
            path = path,
            remoteId = "id-$path",
            remoteHash = "hash-$path",
            remoteSize = size,
            remoteModified = Instant.parse("2026-03-28T12:00:00Z"),
            localMtime = 1711627200000,
            localSize = size,
            isFolder = false,
            isPinned = false,
            isHydrated = true,
            lastSynced = Instant.now(),
        ),
    )

    private fun localFile(
        path: String,
        size: Int = 100,
    ) {
        val p = syncRoot.resolve(path.removePrefix("/"))
        Files.createDirectories(p.parent)
        Files.write(p, ByteArray(size) { 'x'.code.toByte() })
    }

    /** One entry per action in the way the CLI prints a plan (moves as `from -> to`). */
    private fun plan(actions: List<SyncAction>): List<String> =
        actions.map {
            when (it) {
                is SyncAction.CreateRemoteFolder -> "mkdir-remote ${it.path}"
                is SyncAction.DeleteRemote -> "del-remote ${it.path}"
                is SyncAction.MoveRemote -> "move ${it.fromPath} -> ${it.path}"
                is SyncAction.Upload -> "up ${it.path}"
                is SyncAction.DeleteLocal -> "del-local ${it.path}"
                is SyncAction.MoveLocal -> "move-local ${it.fromPath} -> ${it.path}"
                is SyncAction.CreatePlaceholder -> "placeholder ${it.path}"
                is SyncAction.DownloadContent -> "down ${it.path}"
                else -> "${it::class.simpleName} ${it.path}"
            }
        }

    private fun folderRenameLocalChanges() =
        mapOf(
            "/d1" to ChangeState.DELETED,
            "/d1/x.txt" to ChangeState.DELETED,
            "/d1/sub" to ChangeState.DELETED,
            "/d1/sub/y.txt" to ChangeState.DELETED,
            "/d2" to ChangeState.NEW,
            "/d2/x.txt" to ChangeState.NEW,
            "/d2/sub" to ChangeState.NEW,
            "/d2/sub/y.txt" to ChangeState.NEW,
        )

    private fun seedD1Tree() {
        trackedFolder("/d1")
        trackedFile("/d1/x.txt")
        trackedFolder("/d1/sub")
        trackedFile("/d1/sub/y.txt")
    }

    @Test
    fun `local folder rename plans the folder delete after the moves out of it`() {
        seedD1Tree()
        // local: d1 was renamed to d2
        localFile("/d2/x.txt")
        localFile("/d2/sub/y.txt")

        val actions = reconciler.reconcile(remoteChanges = emptyMap(), localChanges = folderRenameLocalChanges())

        assertEquals(
            listOf(
                "mkdir-remote /d2",
                "move /d1/sub -> /d2/sub",
                "move /d1/x.txt -> /d2/x.txt",
                "del-remote /d1",
            ),
            plan(actions),
        )
    }

    @Test
    fun `local folder rename with a renamed subfolder deletes deepest first after all moves`() {
        seedD1Tree()
        // local: d1 -> d2 AND d1/sub -> d2/sub2, so the subfolder is not a same-basename folder move either
        localFile("/d2/x.txt")
        localFile("/d2/sub2/y.txt")

        val actions =
            reconciler.reconcile(
                remoteChanges = emptyMap(),
                localChanges =
                    mapOf(
                        "/d1" to ChangeState.DELETED,
                        "/d1/x.txt" to ChangeState.DELETED,
                        "/d1/sub" to ChangeState.DELETED,
                        "/d1/sub/y.txt" to ChangeState.DELETED,
                        "/d2" to ChangeState.NEW,
                        "/d2/x.txt" to ChangeState.NEW,
                        "/d2/sub2" to ChangeState.NEW,
                        "/d2/sub2/y.txt" to ChangeState.NEW,
                    ),
            )

        assertEquals(
            listOf(
                "mkdir-remote /d2",
                "mkdir-remote /d2/sub2",
                "move /d1/x.txt -> /d2/x.txt",
                "move /d1/sub/y.txt -> /d2/sub2/y.txt",
                "del-remote /d1/sub",
                "del-remote /d1",
            ),
            plan(actions),
        )
    }

    @Test
    fun `moving a subfolder out and deleting its old parent plans the delete after the move`() {
        trackedFolder("/a")
        trackedFolder("/a/b")
        trackedFile("/a/b/f.txt")
        // local: a/b was moved to /b and the (now empty) /a was removed
        localFile("/b/f.txt")

        val actions =
            reconciler.reconcile(
                remoteChanges = emptyMap(),
                localChanges =
                    mapOf(
                        "/a" to ChangeState.DELETED,
                        "/a/b" to ChangeState.DELETED,
                        "/a/b/f.txt" to ChangeState.DELETED,
                        "/b" to ChangeState.NEW,
                        "/b/f.txt" to ChangeState.NEW,
                    ),
            )

        assertEquals(listOf("move /a/b -> /b", "del-remote /a"), plan(actions))
    }

    @Test
    fun `remote move out of a folder that is deleted too plans the local delete after the move`() {
        // the mirror case: another device moved d1/x.txt into d3 and trashed d1. The delta carries the
        // file at its new path (same id) and a tombstone for d1; d1 itself is not matched to a move.
        trackedFolder("/d1")
        trackedFile("/d1/x.txt")
        trackedFolder("/d3")
        localFile("/d1/x.txt")
        Files.createDirectories(syncRoot.resolve("d3"))

        val actions =
            reconciler.reconcile(
                remoteChanges =
                    mapOf(
                        "/d3/x.txt" to
                            CloudItem(
                                id = "id-/d1/x.txt",
                                name = "x.txt",
                                path = "/d3/x.txt",
                                size = 100,
                                isFolder = false,
                                modified = Instant.parse("2026-03-28T12:00:00Z"),
                                created = null,
                                hash = "hash-/d1/x.txt",
                                mimeType = null,
                            ),
                        "/d1" to
                            CloudItem(
                                id = "id-/d1",
                                name = "d1",
                                path = "/d1",
                                size = 0,
                                isFolder = true,
                                modified = null,
                                created = null,
                                hash = null,
                                mimeType = null,
                                deleted = true,
                            ),
                    ),
                localChanges = emptyMap(),
            )

        assertEquals(listOf("move-local /d1/x.txt -> /d3/x.txt", "del-local /d1"), plan(actions))
    }

    // ---- engine level: a fake remote that trashes folders like the real one ------------------------------

    /**
     * An in-memory remote tree. Like the real provider, every operation resolves the folder it names
     * first and fails with "Folder not found" when that folder is gone, and deleting a folder takes its
     * whole subtree with it (the real delete is a trash call on the folder).
     */
    private class TreeProvider : CloudProvider {
        override val id = "tree"
        override val displayName = "Tree"
        override var isAuthenticated = true

        private class Node(
            val id: String,
            val isFolder: Boolean,
            var content: ByteArray = ByteArray(0),
        )

        private val nodes = linkedMapOf<String, Node>()
        private var nextId = 0

        /** Paths whose move must fail once with a transient error (then work again). */
        val failMoveFrom: MutableSet<String> = mutableSetOf()

        /** Paths whose move applies on the remote and THEN fails (a later leg / bookkeeping step). */
        val failMoveAfterApplyFrom: MutableSet<String> = mutableSetOf()
        val calls = mutableListOf<String>()

        /** Live (not trashed) remote paths. */
        fun live(): Set<String> = nodes.keys.toSet()

        /** The delta items a live drive would report for [path] after another device moved it there. */
        fun deltaItemAt(path: String): List<CloudItem> = listOf(item(path, nodes.getValue(path)))

        fun content(path: String): String? = nodes[path]?.content?.toString(Charsets.UTF_8)

        private fun parentOf(path: String) = path.substringBeforeLast('/')

        private fun requireFolder(path: String) {
            if (path.isEmpty()) return
            var built = ""
            for (segment in path.removePrefix("/").split('/')) {
                built += "/$segment"
                if (nodes[built]?.isFolder != true) throw ProviderException("Folder not found: $segment in $built")
            }
        }

        private fun item(
            path: String,
            node: Node,
        ) = CloudItem(
            id = node.id,
            name = path.substringAfterLast('/'),
            path = path,
            size = node.content.size.toLong(),
            isFolder = node.isFolder,
            modified = Instant.parse("2026-03-28T12:00:00Z"),
            created = null,
            hash = if (node.isFolder) null else "h-${node.content.size}",
            mimeType = null,
        )

        override fun capabilities() = setOf(Capability.Delta, Capability.VerifyItem)

        override suspend fun authenticate() {}

        override suspend fun listChildren(path: String): List<CloudItem> {
            requireFolder(path)
            return nodes.filterKeys { it != path && parentOf(it) == path }.map { (p, n) -> item(p, n) }
        }

        override suspend fun getMetadata(path: String) = item(path, nodes[path] ?: throw ProviderException("Item not found: $path"))

        override suspend fun download(
            remotePath: String,
            destination: Path,
        ): Long {
            val n = nodes[remotePath] ?: throw ProviderException("Item not found: $remotePath")
            Files.write(destination, n.content)
            return n.content.size.toLong()
        }

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            ifMatchETag: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem {
            calls += "upload $remotePath"
            requireFolder(parentOf(remotePath))
            val node = nodes.getOrPut(remotePath) { Node("f${nextId++}", false) }
            node.content = Files.readAllBytes(localPath)
            return item(remotePath, node)
        }

        override suspend fun delete(
            remotePath: String,
            ifMatchETag: String?,
        ) {
            calls += "delete $remotePath"
            requireFolder(parentOf(remotePath))
            if (nodes[remotePath] == null) throw ProviderException("Item not found: $remotePath")
            nodes.keys.filter { it == remotePath || it.startsWith("$remotePath/") }.forEach { nodes.remove(it) }
        }

        override suspend fun createFolder(path: String): CloudItem {
            calls += "mkdir $path"
            requireFolder(parentOf(path))
            return item(path, nodes.getOrPut(path) { Node("d${nextId++}", true) })
        }

        override suspend fun move(
            fromPath: String,
            toPath: String,
        ): CloudItem {
            calls += "move $fromPath -> $toPath"
            if (failMoveFrom.remove(fromPath)) throw ProviderException("Simulated transient failure moving $fromPath")
            requireFolder(parentOf(fromPath))
            val node = nodes[fromPath] ?: throw ProviderException("Item not found: $fromPath")
            requireFolder(parentOf(toPath))
            val moved = nodes.keys.filter { it == fromPath || it.startsWith("$fromPath/") }
            val entries = moved.map { it to nodes.remove(it)!! }
            for ((p, n) in entries) nodes[toPath + p.removePrefix(fromPath)] = n
            if (fromPath in failMoveAfterApplyFrom) {
                failMoveAfterApplyFrom.remove(fromPath)
                throw ProviderException("Simulated post-apply failure moving $fromPath")
            }
            return item(toPath, node)
        }

        /** What the next delta() reports (a live drive reports what changed on it). */
        var deltaItems: List<CloudItem> = emptyList()

        /** Puts a node on the remote as if another device had created it, and returns its delta item. */
        fun seed(
            path: String,
            content: String? = null,
        ): CloudItem {
            val node = Node("s${nextId++}", content == null, (content ?: "").toByteArray())
            nodes[path] = node
            return item(path, node)
        }

        /** Moves on the remote as if another device had, returning the delta item at the new path. */
        fun moveElsewhere(
            from: String,
            to: String,
        ): List<CloudItem> {
            val node = nodes.remove(from)!!
            nodes[to] = node
            return listOf(item(to, node))
        }

        /** Trashes on the remote as if another device had, returning the delta tombstone. */
        fun deleteElsewhere(path: String): List<CloudItem> {
            val node = nodes.remove(path)!!
            return listOf(item(path, node).copy(deleted = true))
        }

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((itemsSoFar: Int) -> Unit)?,
            scanContext: org.krost.unidrive.ScanContext?,
        ) = DeltaPage(items = deltaItems, cursor = "cursor-1", hasMore = false)

        override suspend fun quota() = QuotaInfo(total = 1000, used = 100, remaining = 900)

        override suspend fun verifyItemExists(remoteId: String): CapabilityResult<Boolean> =
            CapabilityResult.Success(nodes.values.any { it.id == remoteId })
    }

    /** Records the plan (label + path per applied action) and the failed count of the last pass. */
    private class PlanReporter : ProgressReporter {
        val applied = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        var planned = -1
        var failed = -1

        fun reset() {
            applied.clear()
            warnings.clear()
            planned = -1
            failed = -1
        }

        override fun onScanProgress(
            phase: String,
            count: Int,
        ) {}

        override fun onActionCount(
            total: Int,
            preFilterTotal: Int,
            filterReason: String?,
        ) {
            planned = total
        }

        override fun onActionProgress(
            index: Int,
            total: Int,
            action: String,
            path: String,
        ) {
            applied += "$action $path"
        }

        override fun onTransferProgress(
            path: String,
            bytesTransferred: Long,
            totalBytes: Long,
        ) {}

        override fun onSyncComplete(
            downloaded: Int,
            uploaded: Int,
            conflicts: Int,
            durationMs: Long,
            actionCounts: Map<String, Int>,
            failed: Int,
        ) {
            this.failed = failed
        }

        override fun onWarning(message: String) {
            warnings += message
        }
    }

    private fun engineFor(
        provider: CloudProvider,
        reporter: ProgressReporter,
        database: StateDatabase = db,
    ) = SyncEngine(
        provider = provider,
        db = database,
        syncRoot = syncRoot,
        conflictPolicy = ConflictPolicy.KEEP_BOTH,
        reporter = reporter,
    )

    private fun write(
        rel: String,
        text: String,
    ) {
        val p = syncRoot.resolve(rel)
        Files.createDirectories(p.parent)
        Files.writeString(p, text)
    }

    /** Baseline sync, then d1/x.txt and d1/sub/y.txt created locally and synced to [provider]. */
    private suspend fun syncD1Tree(
        provider: TreeProvider,
        reporter: PlanReporter,
    ): SyncEngine {
        val engine = engineFor(provider, reporter)
        engine.syncOnce()
        write("d1/x.txt", "content-of-x")
        write("d1/sub/y.txt", "content-of-y")
        engine.syncOnce()
        assertEquals(setOf("/d1", "/d1/sub", "/d1/x.txt", "/d1/sub/y.txt"), provider.live(), "setup: the tree is on the remote")
        assertEquals(0, reporter.failed, "setup: nothing failed")
        return engine
    }

    @Test
    fun `renaming a synced folder moves its files into the new remote folder and converges`() =
        runTest {
            val provider = TreeProvider()
            val reporter = PlanReporter()
            val engine = syncD1Tree(provider, reporter)

            Files.move(syncRoot.resolve("d1"), syncRoot.resolve("d2"))
            reporter.reset()
            engine.syncOnce()

            assertEquals(
                setOf("/d2", "/d2/sub", "/d2/x.txt", "/d2/sub/y.txt"),
                provider.live(),
                "the renamed folder holds both files and d1 is gone; calls: ${provider.calls}",
            )
            assertEquals("content-of-x", provider.content("/d2/x.txt"))
            assertEquals("content-of-y", provider.content("/d2/sub/y.txt"))
            assertEquals(0, reporter.failed, "no action failed; warnings: ${reporter.warnings}")
            assertNotNull(db.getEntry("/d2/x.txt")?.remoteId)
            assertNull(db.getEntry("/d1/x.txt"), "no row is left at the old path")

            // the next sync has nothing left to do (on the baseline it plans the same failing move again)
            reporter.reset()
            engine.syncOnce()
            assertEquals(0, reporter.planned, "second sync plans nothing; applied: ${reporter.applied}")
            assertEquals(
                setOf("/d2", "/d2/sub", "/d2/x.txt", "/d2/sub/y.txt"),
                provider.live(),
            )
        }

    @Test
    fun `remote move out of a folder that is deleted too keeps the local file's content`() =
        runTest {
            val provider = TreeProvider()
            val reporter = PlanReporter()
            provider.deltaItems =
                listOf(provider.seed("/d1"), provider.seed("/d1/x.txt", "content-of-x"), provider.seed("/d3"))
            val engine = engineFor(provider, reporter)
            engine.syncOnce()
            assertEquals("content-of-x", Files.readString(syncRoot.resolve("d1/x.txt")), "setup: downloaded")

            // another device moves d1/x.txt into d3 and then trashes d1
            provider.deltaItems = provider.moveElsewhere("/d1/x.txt", "/d3/x.txt") + provider.deleteElsewhere("/d1")
            reporter.reset()
            engine.syncOnce()

            assertEquals(0, reporter.failed, "warnings: ${reporter.warnings}")
            assertFalse(Files.exists(syncRoot.resolve("d1")), "the deleted folder is gone locally")
            // the file was moved out before the folder went; on the baseline the folder delete ran first,
            // the move found nothing to move and left an empty placeholder to be re-downloaded
            assertEquals("content-of-x", Files.readString(syncRoot.resolve("d3/x.txt")), "applied: ${reporter.applied}")
        }

    @Test
    fun `a folder rename whose move fails transiently does not trash the folder holding the file`() =
        runTest {
            val provider = TreeProvider()
            val reporter = PlanReporter()
            val engine = syncD1Tree(provider, reporter)

            Files.move(syncRoot.resolve("d1"), syncRoot.resolve("d2"))
            provider.failMoveFrom += "/d1/x.txt"
            reporter.reset()
            engine.syncOnce()

            assertEquals(1, reporter.failed, "the failed move is the one failure; warnings: ${reporter.warnings}")
            assertTrue("/d1/x.txt" in provider.live(), "the file whose move failed is still in the live remote tree: ${provider.calls}")
            assertEquals("content-of-x", provider.content("/d1/x.txt"))

            // the transient error is over: the next sync finishes the rename
            reporter.reset()
            engine.syncOnce()
            assertEquals(0, reporter.failed, "warnings: ${reporter.warnings}")
            assertEquals(
                setOf("/d2", "/d2/sub", "/d2/x.txt", "/d2/sub/y.txt"),
                provider.live(),
                "calls: ${provider.calls}",
            )

            reporter.reset()
            engine.syncOnce()
            assertEquals(0, reporter.planned, "converged; applied: ${reporter.applied}")
        }

    @Test
    fun `a move that failed after the remote applied it does not block the folder delete forever`() =
        runTest {
            val provider = TreeProvider()
            val reporter = PlanReporter()
            val engine = syncD1Tree(provider, reporter)

            Files.move(syncRoot.resolve("d1"), syncRoot.resolve("d2"))
            provider.failMoveAfterApplyFrom += "/d1/x.txt"
            reporter.reset()
            engine.syncOnce()

            // The move of x.txt threw after the remote had already applied it, so the source is
            // gone. The delete of /d1 must not be guarded by that move: the guard would meet the
            // same "Item not found" on every later pass and keep the folder undeletable forever.
            assertEquals(1, reporter.failed, "the failed move is the one failure; warnings: ${reporter.warnings}")
            assertEquals(
                setOf("/d2", "/d2/sub", "/d2/x.txt", "/d2/sub/y.txt"),
                provider.live(),
                "calls: ${provider.calls}",
            )
            assertEquals("content-of-x", provider.content("/d2/x.txt"))
            assertEquals("content-of-x", Files.readString(syncRoot.resolve("d2/x.txt")))

            // The delta re-reports the moved file by id; the engine adopts it and converges.
            provider.deltaItems = provider.deltaItemAt("/d2/x.txt")
            reporter.reset()
            engine.syncOnce()
            assertEquals(0, reporter.failed, "warnings: ${reporter.warnings}")

            // Settling: at most the one recovery download that restores hydration of the
            // adopted row (same bytes), then nothing.
            reporter.reset()
            engine.syncOnce()
            assertTrue(reporter.planned <= 1, "at most the one recovery download, got: ${reporter.applied}")
            assertEquals("content-of-x", Files.readString(syncRoot.resolve("d2/x.txt")))
            assertEquals("content-of-x", provider.content("/d2/x.txt"))
            reporter.reset()
            engine.syncOnce()
            assertEquals(0, reporter.planned, "converged; applied: ${reporter.applied}")
        }
}
