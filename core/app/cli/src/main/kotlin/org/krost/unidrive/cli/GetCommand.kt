package org.krost.unidrive.cli

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.sync.PlaceholderManager
import org.krost.unidrive.sync.StateDatabase
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import picocli.CommandLine.ParentCommand
import picocli.CommandLine.Spec
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

@Command(name = "get", description = ["Download file content (hydrate)"], mixinStandardHelpOptions = true)
class GetCommand : Runnable {
    @ParentCommand
    lateinit var parent: Main

    @Parameters(index = "0", description = ["Remote path to download"])
    lateinit var path: String

    @Spec
    lateinit var spec: CommandSpec

    @Option(names = ["--concurrency", "-c"], description = ["Number of parallel downloads (default: 4)"])
    var concurrency: Int = 4

    @Option(names = ["--delay-ms"], description = ["Delay between download starts in milliseconds (default: 0)"])
    var delayMs: Long = 0L

    override fun run() {
        if (concurrency <= 0) {
            throw CommandLine.ParameterException(
                spec.commandLine(),
                "--concurrency must be > 0 (got $concurrency)",
            )
        }
        if (delayMs < 0) {
            throw CommandLine.ParameterException(
                spec.commandLine(),
                "--delay-ms must be >= 0 (got $delayMs)",
            )
        }
        val lock = parent.acquireProfileLock()
        val provider = parent.createProvider()
        val config = parent.loadSyncConfig()
        val dbPath = parent.providerConfigDir().resolve("state.db")
        val db = StateDatabase(dbPath)
        db.initialize()

        val normalizedPath = if (path.startsWith("/")) path else "/$path"
        val entry = db.getEntry(normalizedPath)
        val placeholder = PlaceholderManager(config.syncRoot)

        try {
            runBlocking {
                provider.authenticate()

                val isFolder =
                    entry?.isFolder ?: java.nio.file.Files
                        .isDirectory(placeholder.resolveLocal(normalizedPath))

                if (isFolder) {
                    val prefix = if (normalizedPath.endsWith("/")) normalizedPath else "$normalizedPath/"
                    val children =
                        db.getEntriesByPrefix(prefix).filter { child ->
                            if (child.isFolder) return@filter false
                            if (!child.isHydrated) return@filter true
                            placeholder.isSparse(placeholder.resolveLocal(child.path), child.remoteSize)
                        }
                    if (children.isEmpty()) {
                        println("Already hydrated: $normalizedPath")
                        return@runBlocking
                    }
                    println("Hydrating ${children.size} file(s) under $normalizedPath...")
                    val totalBytes = AtomicLong(0)
                    val okCount =
                        java.util.concurrent.atomic
                            .AtomicInteger(0)
                    val skipCount =
                        java.util.concurrent.atomic
                            .AtomicInteger(0)
                    val semaphore = Semaphore(concurrency)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val dbMutex = Mutex()
                        for (child in children) {
                            if (delayMs > 0) delay(delayMs)
                            launch {
                                semaphore.withPermit {
                                    val localPath = placeholder.resolveLocal(child.path)
                                    val bytes =
                                        try {
                                            val rid = child.remoteId
                                            if (rid != null) {
                                                provider.downloadById(rid, child.path, localPath)
                                            } else {
                                                provider.download(child.path, localPath)
                                            }
                                        } catch (e: Exception) {
                                            println("  ${child.path}... skipped (${e.message})")
                                            skipCount.incrementAndGet()
                                            return@withPermit
                                        }
                                    placeholder.restoreMtime(child.path, child.remoteModified)
                                    dbMutex.withLock {
                                        db.upsertEntry(
                                            child.copy(
                                                isHydrated = true,
                                                localMtime =
                                                    java.nio.file.Files
                                                        .getLastModifiedTime(localPath)
                                                        .toMillis(),
                                                localSize =
                                                    java.nio.file.Files
                                                        .size(localPath),
                                                lastSynced = Instant.now(),
                                            ),
                                        )
                                    }
                                    totalBytes.addAndGet(bytes)
                                    okCount.incrementAndGet()
                                    val sizeLabel =
                                        if (child.remoteSize > 0) {
                                            "[${CliProgressReporter.formatSize(
                                                bytes,
                                            )} / ${CliProgressReporter.formatSize(child.remoteSize)}]"
                                        } else {
                                            CliProgressReporter.formatSize(bytes)
                                        }
                                    println("  ${child.path}... $sizeLabel")
                                }
                            }
                        }
                    }
                    val ok = okCount.get()
                    val skip = skipCount.get()
                    val total = children.size
                    println("Downloaded ${CliProgressReporter.formatSize(totalBytes.get())} total  ($ok/$total ok, $skip skipped)")
                    if (skip > 0) {
                        println("Tip: re-run the same command to retry skipped files.")
                    }
                } else {
                    val localPath = placeholder.resolveLocal(normalizedPath)
                    val remoteSize = entry?.remoteSize ?: 0L
                    val isSparse = placeholder.isSparse(localPath, remoteSize)
                    if (entry != null && entry.isHydrated && !isSparse) {
                        println("Already hydrated: $normalizedPath")
                        return@runBlocking
                    }

                    println("Downloading $normalizedPath...")
                    val entryRemoteId = entry?.remoteId
                    val bytes =
                        if (entryRemoteId != null) {
                            provider.downloadById(entryRemoteId, normalizedPath, localPath)
                        } else {
                            provider.download(normalizedPath, localPath)
                        }
                    val modified = entry?.remoteModified ?: provider.getMetadata(normalizedPath).modified
                    placeholder.restoreMtime(normalizedPath, modified)

                    if (entry != null) {
                        db.upsertEntry(
                            entry.copy(
                                isHydrated = true,
                                localMtime =
                                    java.nio.file.Files
                                        .getLastModifiedTime(localPath)
                                        .toMillis(),
                                localSize =
                                    java.nio.file.Files
                                        .size(localPath),
                                lastSynced = Instant.now(),
                            ),
                        )
                    }
                    println("Downloaded ${CliProgressReporter.formatSize(bytes)}")
                }
            }
        } catch (e: AuthenticationException) {
            parent.handleAuthError(e, provider)
        } finally {
            db.close()
            lock.unlock()
        }
    }
}
