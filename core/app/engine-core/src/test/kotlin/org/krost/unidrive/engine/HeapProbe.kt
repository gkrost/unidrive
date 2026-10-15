package org.krost.unidrive.engine

import kotlinx.coroutines.runBlocking
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.ProviderException
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
 * pass runs over that database, as the 2026-10-14 capture resumed). During each gather, after the same
 * item count, a forced GC reads the retained heap; the task fails when resumed retains more than
 * [RESUMED_RATIO_FAIL] times the fresh one. Not part of `check`: run with `./gradlew :app:engine-core:heapProbe [sizes...]`.
 */
private const val RESUMED_RATIO_FAIL = 1.5

private const val PAGE_SIZE = 1_000

// The explicit collections are the probe's whole point: the reading must be the live set, not whatever
// the last natural collection left. The double call with the sleeps lets finalizers and reference
// processing settle between passes.
@Suppress("ExplicitGarbageCollectionCall")
private fun retainedMib(): Long {
    // The caller is inside gather's progress callback, while its item map and any resumed staging list
    // are still reachable. A completed scan has already released the latter and hides the resume cost.
    System.gc()
    Thread.sleep(200)
    System.gc()
    Thread.sleep(100)
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
        val stagedHalf = minOf(items, ((items / 2 + PAGE_SIZE - 1) / PAGE_SIZE) * PAGE_SIZE)
        val sampleAt = minOf(items, stagedHalf + PAGE_SIZE)
        var sampledMib: Long? = null
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
                                override fun onScanProgress(count: Int) {
                                    // Gather has admitted the same number of items in both variants. The resumed
                                    // pass still holds its loaded staging list here; completion would release it.
                                    if (count >= sampleAt && sampledMib == null) sampledMib = retainedMib()
                                }

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
                // Stop after persisting half the pages, leaving the actual checkpoint and staged rows.
                provider.interruptAt = stagedHalf
                check(!enumeration.enumerate(reset = true).ok)
                provider.interruptAt = null
                check(enumeration.enumerate(reset = false).ok)
                check(provider.resumedFrom == stagedHalf && provider.resumedCount == stagedHalf) {
                    "the probe did not resume from its staged half"
                }
            } else {
                check(enumeration.enumerate(reset = true).ok)
            }
        }
        val mib = checkNotNull(sampledMib) { "the gather never reached the sampling point" }
        return mib
    } finally {
        db.close()
        tmp.toFile().deleteRecursively()
    }
}

/** A full-listing provider that hands out [total] files in pages of [pageSize], newest page last. */
private class PagedProvider(
    private val total: Int,
    private val pageSize: Int,
) : CloudProvider {
    var interruptAt: Int? = null
    var resumedFrom: Int = 0
    var resumedCount: Int = 0

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
        val offset = cursor?.toIntOrNull() ?: scanContext?.resumeMarker?.toIntOrNull() ?: 0
        if (cursor == null && scanContext?.resumeMarker != null) {
            resumedFrom = offset
            resumedCount = scanContext.resumedItems.size
        }
        val end = minOf(offset + pageSize, total)
        val newItems =
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
        scanContext?.persistPage?.invoke(newItems, end.toString())
        interruptAt?.let { if (end >= it) throw ProviderException("probe interrupted after staging $end items") }
        val page = if (cursor == null) scanContext?.resumedItems.orEmpty() + newItems else newItems
        return DeltaPage(items = page, cursor = end.toString(), hasMore = end < total)
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
                String.format(java.util.Locale.ROOT, "%.2f", ratio),
        )
        if (ratio > RESUMED_RATIO_FAIL) {
            println("FAIL: the resumed variant retains more than $RESUMED_RATIO_FAIL x the fresh variant at $n items")
            failures++
        }
    }
    if (failures > 0) exitProcess(1)
}
