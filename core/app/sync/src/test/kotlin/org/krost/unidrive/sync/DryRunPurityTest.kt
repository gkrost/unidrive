package org.krost.unidrive.sync

import kotlinx.coroutines.test.runTest
import org.krost.unidrive.sync.testfixtures.EngineOpts
import org.krost.unidrive.sync.testfixtures.TwinProvider
import org.krost.unidrive.sync.testfixtures.TwinResult
import org.krost.unidrive.sync.testfixtures.World
import org.krost.unidrive.sync.testfixtures.diffOf
import org.krost.unidrive.sync.testfixtures.dryRunTwin
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Dry-run purity ratchet. A dry-run must leave state.db, the sync tree, logs, caches and the provider exactly
 * as they were, and running it twice must give the same result. Each scenario builds a world, then dry-runs a
 * CLONE of it twice (the original keeps going, so a scenario can chain real passes) and reports every category
 * of difference.
 *
 * [knownImpurities] is a ratchet: it lists the categories a dry-run is still known to touch. The test fails when
 * a new category appears (a regression) and also when a listed one stops appearing (a fix that forgot to shrink
 * the list). Fixing an impurity therefore means deleting its entry. The target is an empty set.
 */
class DryRunPurityTest {
    private class Scenario(
        val name: String,
        val opts: EngineOpts = EngineOpts(),
        val createSyncRoot: Boolean = true,
        val arrange: suspend World.() -> Unit,
    )

    private fun World.seedRemote() {
        provider.deltaItems =
            listOf(
                TwinProvider.item("/docs", folder = true),
                TwinProvider.item("/docs/a.txt"),
                TwinProvider.item("/empty", folder = true),
                TwinProvider.item("/b.txt"),
            )
        listOf("/docs/a.txt", "/b.txt").forEach { provider.files[it] = ByteArray(10) { i -> i.toByte() } }
    }

    private val scenarios =
        listOf(
            Scenario("fresh profile, nothing synced yet") { seedRemote() },
            Scenario("remote file modified after a sync") {
                seedRemote()
                pass()
                provider.deltaCursor = "cursor-2"
                provider.incrementalItems = listOf(TwinProvider.item("/b.txt", size = 99, hash = "changed"))
                provider.files["/b.txt"] = ByteArray(99)
            },
            Scenario("local file added and another edited after a sync") {
                seedRemote()
                pass()
                Files.writeString(syncRoot.resolve("new.txt"), "brand new")
                Files.writeString(syncRoot.resolve("b.txt"), "edited locally, longer than before")
            },
            Scenario("remote deletion after a sync") {
                seedRemote()
                pass()
                provider.deltaCursor = "cursor-2"
                provider.incrementalItems = listOf(TwinProvider.item("/b.txt", deleted = true))
            },
            Scenario("scope widened after a scoped sync", opts = EngineOpts(standingScope = listOf("/docs", "/b.txt"))) {
                seedRemote()
                pass(EngineOpts(standingScope = listOf("/docs")))
                provider.deltaCursor = "cursor-2"
            },
            Scenario("streaming reconciliation over two pages", opts = EngineOpts(streaming = true)) {
                provider.deltaPages =
                    listOf(
                        listOf(TwinProvider.item("/docs", folder = true), TwinProvider.item("/docs/a.txt")) to "c1",
                        listOf(TwinProvider.item("/b.txt")) to "c2",
                    )
                listOf("/docs/a.txt", "/b.txt").forEach { provider.files[it] = ByteArray(10) }
            },
            Scenario("expired trash and versions", opts = EngineOpts(trash = true, versions = true)) {
                seedRemote()
                pass()
                Files.createDirectories(syncRoot.resolve(".unidrive-trash/20200101T000000Z"))
                Files.writeString(syncRoot.resolve(".unidrive-trash/20200101T000000Z/old.txt"), "expired")
                Files.createDirectories(syncRoot.resolve(".unidrive-versions/b.txt"))
                Files.writeString(syncRoot.resolve(".unidrive-versions/b.txt/20200101T000000Z"), "old version")
            },
            Scenario("sync_root does not exist yet", createSyncRoot = false) { seedRemote() },
            Scenario("fast bootstrap on first sync", opts = EngineOpts(fastBootstrap = true)) {
                provider.supportsFastBootstrap = true
                seedRemote()
            },
            Scenario("delta cursor expired") {
                seedRemote()
                pass()
                provider.expireResumedCursorOnce = true
            },
            Scenario("refresh left pending downloads") {
                seedRemote()
                pass(skipTransfers = true)
            },
            Scenario("streaming with a new local file waiting", opts = EngineOpts(streaming = true)) {
                seedRemote()
                pass()
                Files.writeString(syncRoot.resolve("waiting.txt"), "not uploaded yet")
                provider.deltaCursor = "cursor-2"
                provider.deltaPages = listOf(listOf(TwinProvider.item("/b.txt", size = 50, hash = "changed")) to "cursor-2")
                provider.files["/b.txt"] = ByteArray(50)
            },
            Scenario("streaming with a tracked file edited locally", opts = EngineOpts(streaming = true)) {
                seedRemote()
                pass()
                Files.writeString(syncRoot.resolve("b.txt"), "edited locally, longer than the synced 10 bytes")
                provider.deltaCursor = "cursor-2"
                provider.deltaPages = listOf(listOf(TwinProvider.item("/docs/a.txt", size = 40, hash = "changed")) to "cursor-2")
                provider.files["/docs/a.txt"] = ByteArray(40)
            },
            Scenario("state left behind by an earlier dry-run") {
                seedRemote()
                pass(dryRun = true)
            },
        )

    /** Categories a dry-run is still known to touch. Delete an entry when its fix lands. */
    private val knownImpurities: Set<String> =
        setOf(
            // Row and cursor writes before the dry-run branch, scanner pre-writes: #298.
            "db:sync_entries",
            "db:sync_state.delta_cursor",
            "db:sync_state.pending_cursor",
            "db:sync_state.pending_cursor_complete",
            "db:sync_state.last_full_scan",
            "db:sync_state.last_gather_full",
            "db:sync_state.last_scan_count_local",
            "db:sync_state.last_scan_count_remote",
            "db:sync_state.last_scan_secs_local",
            "db:sync_state.last_scan_secs_remote",
            "db:sync_state.sync_root",
            // A second dry-run differs from the first because the first left state behind: #298.
            "idempotence",
            // Streaming reconciliation runs real transfers mid-gather and flips its sentinel: #397.
            "db:sync_state.streaming_reconciliation_enabled",
            "provider:content",
            "provider:mutation",
            // Trash purge, version prune, sync_root creation, skipped-ops.jsonl: #399.
            "fs:sync",
            "fs:logs",
        )

    private fun run(s: Scenario): TwinResult =
        kotlinx.coroutines.runBlocking {
            val base = Files.createTempDirectory("dryrun-twin-")
            val world = World(base.resolve("world"), createSyncRoot = s.createSyncRoot)
            try {
                s.arrange(world)
                world.dryRunTwin(s.opts, base.resolve("clone"))
            } finally {
                world.close()
                base.toFile().deleteRecursively()
            }
        }

    @Test
    fun `dry-run impurities match the ratchet`() {
        val observed = scenarios.associate { it.name to run(it) }
        val categories = observed.values.flatMap { it.impurities.keys }.toSortedSet()
        val report =
            observed.entries.joinToString("\n") { (name, r) ->
                "  $name -> ${r.impurities.keys}" + (r.threw?.let { " (dry-run threw: $it)" } ?: "")
            }
        val detail =
            observed.entries.joinToString("\n") { (name, r) ->
                r.impurities.entries.joinToString("\n") { (cat, lines) -> "  [$name] $cat (${lines.size}): ${lines.first()}" }
            }
        assertEquals(
            knownImpurities.toSortedSet(),
            categories,
            "Dry-run side effects changed.\nPer scenario:\n$report\nFirst difference per category:\n$detail\n",
        )
    }

    private fun tempWorld(): Pair<Path, World> {
        val base = Files.createTempDirectory("dryrun-repro-")
        return base to World(base.resolve("world"))
    }

    /**
     * Pins the near-miss seen on a real account (issue #298): a dry-run persists the remote folders as
     * unhydrated rows and advances the cursor, so the following real run sees folders that "vanished
     * locally" and plans DeleteRemote for them. Only the unhydrated-folder guard stops the deletes, and the
     * mkdir actions the dry-run announced never run, so a folder without files is never created.
     *
     * When the #298 hotfix lands both assertions about the bug must be inverted (no skipped del-remote, empty
     * folder created).
     */
    @Test
    fun `dry-run then a real run plans folder deletes and skips mkdir - pins issue 298`() =
        runTest {
            val (base, world) = tempWorld()
            try {
                world.seedRemote()
                world.pass(dryRun = true)
                world.pass()

                val skippedOps = world.logDir.resolve("skipped-ops.jsonl")
                val skippedDeletes = if (Files.exists(skippedOps)) Files.readAllLines(skippedOps).count { it.contains("del-remote") } else 0
                assertTrue(skippedDeletes > 0, "expected the guard to have dropped folder del-remote actions; the bug may be fixed, invert this test")
                assertTrue(world.provider.mutations().none { it.startsWith("delete ") }, "the guard must keep holding: no remote delete")
                assertFalse(Files.isDirectory(world.syncRoot.resolve("empty")), "the empty remote folder is only created if mkdir ran")
            } finally {
                world.close()
                base.toFile().deleteRecursively()
            }
        }

    @Test
    fun `the harness notices every kind of side effect`() {
        val (base, world) = tempWorld()
        try {
            val quiet = world.capture(includeMutations = true)
            assertTrue(diffOf(quiet, world.capture(includeMutations = true)).isEmpty(), "two captures of an idle world must be equal")

            world.db.setSyncState("delta_cursor", "moved")
            Files.writeString(world.syncRoot.resolve("x.txt"), "x")
            Files.writeString(world.logDir.resolve("skipped-ops.jsonl"), "{}")
            world.provider.files["/p.txt"] = ByteArray(3)
            world.provider.calls += "upload /p.txt"

            val cats = diffOf(quiet, world.capture(includeMutations = true)).keys
            assertEquals(
                setOf("db:sync_state.delta_cursor", "fs:sync", "fs:logs", "provider:content", "provider:mutation"),
                cats,
            )
        } finally {
            world.close()
            base.toFile().deleteRecursively()
        }
    }

    @Test
    fun `a twin run leaves the original world untouched so scenarios can keep going`() =
        runTest {
            val (base, world) = tempWorld()
            try {
                world.seedRemote()
                world.pass()
                val before = world.capture(includeMutations = true)
                val callsBefore = world.provider.calls.toList()

                world.dryRunTwin(EngineOpts(streaming = true), base.resolve("clone"))

                assertTrue(diffOf(before, world.capture(includeMutations = true)).isEmpty(), "the original world changed")
                assertEquals(callsBefore, world.provider.calls.toList(), "the twin must not touch the original provider")
            } finally {
                world.close()
                base.toFile().deleteRecursively()
            }
        }
}
