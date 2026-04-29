package org.krost.unidrive.sync

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration

class LocalWatcher(
    private val syncRoot: Path,
) {
    private val log = LoggerFactory.getLogger(LocalWatcher::class.java)
    private val suppressedPaths = ConcurrentHashMap.newKeySet<String>()
    private val changedPaths = ConcurrentLinkedQueue<String>()
    private var watchService: WatchService? = null
    private var watchThread: Thread? = null

    @Volatile private var running = false
    private val watchKeys = ConcurrentHashMap<WatchKey, Path>()
    private val changeSignal = Channel<Unit>(Channel.CONFLATED)

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
                        val key = ws.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS) ?: continue
                        val dir = watchKeys[key] ?: continue

                        for (event in key.pollEvents()) {
                            val kind = event.kind()
                            if (kind == StandardWatchEventKinds.OVERFLOW) continue

                            @Suppress("UNCHECKED_CAST")
                            val ev = event as WatchEvent<Path>
                            val child = dir.resolve(ev.context())
                            val relativePath = "/" + syncRoot.relativize(child).toString().replace('\\', '/')

                            if (relativePath in suppressedPaths) continue

                            changedPaths.add(relativePath)
                            changeSignal.trySend(Unit)

                            // Register new directories
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
        watchService?.close()
        watchThread?.join(2000)
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
}
