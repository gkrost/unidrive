package org.krost.unidrive.engine

import kotlinx.coroutines.runBlocking
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.ScanContext
import org.krost.unidrive.sync.EnumerationTracker
import org.krost.unidrive.sync.StateDatabase
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * #891: the retained-heap probe of a full enumeration. A fake provider streams N items in cursor-sized
 * pages through the real [RemoteGather]/[RemoteEnumeration] into a temp `state.db`, in two variants:
 * fresh (one full pass) and resumed (a first pass over N/2 items leaves its rows staged, then the full
 * pass runs over that database, as the 2026-10-14 capture resumed). After a forced GC the retained heap
 * is read; the task fails when the resumed variant retains more than [RESUMED_RATIO_FAIL] times the
 * fresh one. Not part of `check`: run with `./gradlew :app:engine-core:heapProbe [sizes...]`.
 */
private const val RESUMED_RATIO_FAIL = 1.5

private const val PAGE_SIZE = 1_000

private fun retainedMib(keepAlive: Any, block: () -> Unit): Long {
    block()
    // A forced collection so the reading is the live set, not whatever the last collection left. The
    // holders stay reachable through [keepAlive]: the question is what the running engine retains.
    System.gc()
    Thread.sleep(200)
    System.gc()
    Thread.sleep(100)
    check(keepAlive.toString().isNotEmpty())
    return heap().used / (1024L * 1024L)
}

private fun heap() = java.lang.management.ManagementFactory.getMemoryMXBean().heapMemoryUsage

private fun runVariant(
    items: Int,
    resume: Boolean,
): Long {
    val tmp = Files.createTempDirectory("heap-probe")
    val db = StateDatabase(tmp.resolve("state.db"))
    db.initialize()
    try {
        val provider = PagedProvider(total = items, pageSize = PAGE_SIZE)
        val enumeration =
            RemoteEnumeration(
                gather =
                    RemoteGather(
                        provider = provider,
                        db = db,
                        guard =
                            RemoteOperationGuard(
                                standingScope = emptyList(),
                                syncPaths = emptyList(),
                                excludePatterns = emptyList(),
                                maxConcurrentTransfers = 2,
                                matchesGlob = { _, _ -> false },
                            ),
                        options = RemoteGather.Options(providerId = "probe"),
                        localTopAliases = { emptyMap() },
                        listener =
                            object : RemoteGather.Listener {
                                override fun onViewInvalidated(
                                    changedPaths: Set<String>,
                                    full: Boolean,
                                    moved: List<RemoteGather.RemoteMerge.Move>,
                                ) {}
                            },
                        log = LoggerFactory.getLogger("probe"),
                    ),
                db = db,
                tracker = EnumerationTracker(),
                reapGuards =
                    RemoteEnumeration.ReapGuards(
                        uploadInFlight = { false },
                        cachePathOf = { path -> tmp.resolve("cache").resolve(path.removePrefix("/")) },
                    ),
                log = LoggerFactory.getLogger("probe"),
            )
        runBlocking {
            if (resume) {
                // The first pass stages half the account, as an interrupted scan left it.
                provider.limit = items / 2
                enumeration.enumerate(reset = true)
                provider.limit = items
                enumeration.enumerate(reset = false)
            } else {
                enumeration.enumerate(reset = true)
            }
        }
        // The holders themselves, not their descriptions: only a reference to the objects keeps them
        // through the collection.
        val keepAlive = Triple(provider, enumeration, db)
        val mib = retainedMib(keepAlive) { keepAlive.second.status().state }
        db.close()
        return mib
    } finally {
        tmp.toFile().deleteRecursively()
    }
}

/** A full-listing provider that hands out [total] files in pages of [pageSize], newest page last. */
private class PagedProvider(
    private val total: Int,
    private val pageSize: Int,
) : CloudProvider {
    var limit: Int = total

    override val id = "probe"
    override val displayName = "probe"
    override var isAuthenticated = true

    override fun capabilities(): Set<Capability> = setOf(Capability.Delta)

    override suspend fun authenticate() {}

    override suspend fun delta(
        cursor: String?,
        onPageProgress: ((itemsSoFar: Int) -> Unit)?,
        scanContext: ScanContext?,
    ): DeltaPage {
        val offset = cursor?.toIntOrNull() ?: 0
        val end = minOf(offset + pageSize, limit)
        val page =
            (offset until end).map { i ->
                CloudItem(
                    id = "id-$i",
                    name = "file-$i.bin",
                    path = "/dir${i / 100}/file-$i.bin",
                    size = 1,
                    isFolder = false,
                    modified = null,
                    created = null,
                    hash = null,
                    mimeType = null,
                )
            }
        onPageProgress?.invoke(end)
        return DeltaPage(items = page, cursor = end.toString(), hasMore = end < limit)
    }

    override val deltaIsFullListing: Boolean get() = true

    override suspend fun listChildren(path: String): List<CloudItem> = error("not used")

    override suspend fun getMetadata(path: String): CloudItem = error("not used")

    override suspend fun download(
        remotePath: String,
        destination: Path,
    ): Long = error("not used")

    override suspend fun upload(
        localPath: Path,
        remotePath: String,
        existingRemoteId: String?,
        ifMatchETag: String?,
        onProgress: ((Long, Long) -> Unit)?,
    ): CloudItem = error("not used")

    override suspend fun delete(
        remotePath: String,
        ifMatchETag: String?,
    ) = error("not used")

    override suspend fun createFolder(path: String): CloudItem = error("not used")

    override suspend fun move(
        fromPath: String,
        toPath: String,
    ): CloudItem = error("not used")

    override suspend fun quota(): QuotaInfo = error("not used")
}

fun main(args: Array<String>) {
    val sizes = args.map { it.toInt() }.ifEmpty { listOf(100_000, 200_000, 400_000) }
    println("# heap probe: a full enumeration through RemoteGather/RemoteEnumeration into a temp state.db")
    println("# columns: items | fresh MiB | fresh B/item | resumed MiB | resumed B/item | resumed/fresh")
    println("# JVM: ${System.getProperty("java.vm.version")} maxHeap=${heap().max / (1024L * 1024L)}MiB")
    var failures = 0
    for (n in sizes) {
        val fresh = runVariant(items = n, resume = false)
        val resumed = runVariant(items = n, resume = true)
        val ratio = if (fresh == 0L) Double.NaN else resumed.toDouble() / fresh
        println(
            "$n | $fresh | ${fresh * 1_048_576 / n} | $resumed | ${resumed * 1_048_576 / n} | " +
                String.format("%.2f", ratio),
        )
        if (ratio > RESUMED_RATIO_FAIL) {
            println("FAIL: the resumed variant retains more than $RESUMED_RATIO_FAIL x the fresh variant at $n items")
            failures++
        }
    }
    if (failures > 0) exitProcess(1)
}
