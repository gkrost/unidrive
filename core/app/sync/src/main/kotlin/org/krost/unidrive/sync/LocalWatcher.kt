package org.krost.unidrive.sync

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

class LocalWatcher(
    private val syncRoot: Path,
    private val debounceMs: Long = readDebounceFromEnv(),
) {
    private val log = LoggerFactory.getLogger(LocalWatcher::class.java)
    private val suppressedPaths = ConcurrentHashMap.newKeySet<String>()
    private val changedPaths = ConcurrentLinkedQueue<String>()
    private var watchService: WatchService? = null
    private var watchThread: Thread? = null

    @Volatile private var running = false
    private val watchKeys = ConcurrentHashMap<WatchKey, Path>()
    private val changeSignal = Channel<Unit>(Channel.CONFLATED)

    // UD-211 hold-and-emit state. Key = relative path (already normalised with leading "/").
    // Every observed event for a path arms (or re-arms) a single scheduled emit; the
    // last event in a burst wins on the schedule clock, and one emit is enqueued per
    // path per quiet window.
    private val pendingEmits = ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val coalescer: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "unidrive-watcher-coalescer").apply { isDaemon = true }
        }

    fun start() {
        if (running) return
        val ws = FileSystems.getDefault().newWatchService()
        try {
            registerRecursive(syncRoot, ws)
        } catch (e: Exception) {
            ws.close()
            throw e
        }
        watchService = ws
        running = true

        watchThread =
            Thread({
                while (running) {
                    try {
                        val key = ws.poll(500, TimeUnit.MILLISECONDS) ?: continue
                        val dir = watchKeys[key] ?: continue

                        for (event in key.pollEvents()) {
                            val kind = event.kind()
                            if (kind == StandardWatchEventKinds.OVERFLOW) continue

                            @Suppress("UNCHECKED_CAST")
                            val ev = event as WatchEvent<Path>
                            val child = dir.resolve(ev.context())
                            val relativePath = "/" + syncRoot.relativize(child).toString().replace('\\', '/')

                            if (relativePath in suppressedPaths) continue

                            handleEvent(kind, relativePath)

                            if (kind == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(child)) {
                                try {
                                    registerRecursive(child, ws)
                                } catch (e: Exception) {
                                    log.warn("Failed to register new directory $child: ${e.message}")
                                }
                            }
                        }

                        if (!key.reset()) {
                            watchKeys.remove(key)
                        }
                    } catch (_: ClosedWatchServiceException) {
                        break
                    } catch (e: Exception) {
                        log.warn("Watch error: ${e.message}")
                    }
                }
            }, "unidrive-watcher")
        watchThread!!.isDaemon = true
        watchThread!!.start()
    }

    fun stop() {
        running = false
        try {
            watchService?.close()
        } catch (e: java.io.IOException) {
            log.warn("WatchService close failed during stop(): {}", e.message)
        }
        watchThread?.join(2000)
        if (watchThread?.isAlive == true) {
            log.warn("LocalWatcher thread did not exit within 2s of stop()")
        }
        // UD-211: cancel any in-flight emit schedules and shut down the coalescer.
        pendingEmits.values.forEach { it.cancel(false) }
        pendingEmits.clear()
        coalescer.shutdown()
        if (!coalescer.awaitTermination(2, TimeUnit.SECONDS)) {
            log.warn("Coalescer did not shut down gracefully within 2s; forcing shutdownNow()")
            coalescer.shutdownNow()
        }
    }

    fun suppress(remotePath: String) {
        suppressedPaths.add(remotePath)
    }

    fun unsuppress(remotePath: String) {
        suppressedPaths.remove(remotePath)
    }

    fun drainChanges(): List<String> {
        val result = mutableListOf<String>()
        while (true) {
            val path = changedPaths.poll() ?: break
            result.add(path)
        }
        return result.distinct()
    }

    suspend fun awaitChange(timeout: Duration): Boolean =
        withTimeoutOrNull(timeout.inWholeMilliseconds) {
            changeSignal.receive()
            true
        } ?: false

    /**
     * UD-211 hold-and-emit coalescer (v2: uniform debounce on every event kind).
     *
     * Every CREATE / MODIFY / DELETE for path P arms (or re-arms) a single scheduled
     * emit at `+debounceMs`. The schedule's clock resets on each new event, so an
     * editor's atomic-save burst (vim's `write .swp + ATOMIC_MOVE over orig`, which on
     * different filesystems surfaces as DELETE+CREATE, MODIFY-of-existing, or
     * RENAME_FROM+RENAME_TO) always coalesces to exactly one emit per quiet window.
     * The engine re-stats on dequeue and sees whatever final state the burst left
     * behind — there is no per-event side-effect to misorder.
     *
     * Trade-off: changes propagate at trailing-edge instead of leading-edge (~debounce
     * latency before the engine reacts). For a sync engine this is correct: it
     * eliminates the destructive "delete-then-recreate" cloud propagation that
     * leading-edge emit caused on editor saves. Mid-upload mutations are caught
     * separately by UD-210.
     */
    private fun handleEvent(
        @Suppress("UNUSED_PARAMETER") kind: WatchEvent.Kind<*>,
        relativePath: String,
    ) {
        // Cancel any in-flight schedule for this path and arm a fresh one. The last
        // observed event in a burst wins on the schedule clock.
        pendingEmits.remove(relativePath)?.cancel(false)
        val task =
            coalescer.schedule(
                {
                    if (pendingEmits.remove(relativePath) != null) {
                        enqueue(relativePath)
                    }
                },
                debounceMs,
                TimeUnit.MILLISECONDS,
            )
        pendingEmits[relativePath] = task
    }

    private fun enqueue(relativePath: String) {
        changedPaths.add(relativePath)
        changeSignal.trySend(Unit)
    }

    private fun registerRecursive(
        root: Path,
        ws: WatchService,
    ) {
        val isRoot = root == syncRoot
        try {
            Files.walkFileTree(
                root,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(
                        dir: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        try {
                            val key =
                                dir.register(
                                    ws,
                                    StandardWatchEventKinds.ENTRY_CREATE,
                                    StandardWatchEventKinds.ENTRY_MODIFY,
                                    StandardWatchEventKinds.ENTRY_DELETE,
                                )
                            watchKeys[key] = dir
                        } catch (e: Exception) {
                            if (dir == syncRoot) {
                                throw IOException("Failed to register watch on sync root $dir: ${e.message}", e)
                            }
                            log.warn("Cannot watch subdirectory {}: {}", dir, e.message)
                        }
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            if (isRoot) {
                throw IOException("Failed to register watches under sync root $root: ${e.message}", e)
            } else {
                log.warn("Failed to register watches under {}: {}", root, e.message)
            }
        }
    }

    companion object {
        private fun readDebounceFromEnv(): Long =
            System.getenv("UNIDRIVE_WATCHER_DEBOUNCE_MS")?.toLongOrNull()?.takeIf { it >= 0 } ?: 2000L
    }
}
