package org.krost.unidrive.cli

import kotlinx.coroutines.runBlocking
import org.krost.unidrive.sync.StateDatabase
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.ParentCommand
import java.io.RandomAccessFile
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/**
 * UD-226: detect + rehydrate zero-byte stubs left behind by the pre-UD-222 code path.
 *
 * Pre-fix `applyCreatePlaceholder` wrote full-size NUL-byte files on Windows and marked the DB
 * entry `isHydrated=true` because `PlaceholderManager.isSparse` returns false unconditionally
 * on Windows (UD-209), so `sizeMatch && !isSparse` looked like real content. The UD-225
 * Reconciler synthesiser only rescues `isHydrated=false` entries — it has no way to know the
 * DB is lying. This command does the external check: walk the tree, sample three blocks
 * (first / middle / last, 4 KiB each) of every regular file, and flag ones that are all-NUL
 * with matching DB remoteSize.
 */
@Command(
    name = "sweep",
    description = [
        "Detect + rehydrate zero-byte stub files (UD-226).",
        "`--null-bytes` is the only scan mode currently supported; `--dry-run` and `--rehydrate` are modifiers that require it.",
    ],
    mixinStandardHelpOptions = true,
)
class SweepCommand : Runnable {
    @ParentCommand
    lateinit var parent: Main

    @Option(names = ["--null-bytes"], description = ["Scan mode (required): scan for zero-byte stubs via sampled NUL detection"])
    var nullBytes: Boolean = false

    @Option(
        names = ["--rehydrate"],
        description = ["Modifier: mark detected stubs as non-hydrated so the next sync re-downloads them (requires --null-bytes)"],
    )
    var rehydrate: Boolean = false

    @Option(
        names = ["--dry-run"],
        description = ["Modifier: report what --rehydrate would do without mutating the DB (requires --null-bytes)"],
    )
    var dryRun: Boolean = false

    override fun run() =
        runBlocking {
            if (!nullBytes) {
                System.err.println(
                    "No sweep mode selected. Pass --null-bytes (the only scan mode currently supported). " +
                        "--dry-run and --rehydrate are modifiers that require --null-bytes.",
                )
                kotlin.system.exitProcess(2)
            }

            val profile = parent.resolveCurrentProfile()
            val configDir = parent.configBaseDir().resolve(profile.name)
            val dbPath = configDir.resolve("state.db")
            if (!Files.exists(dbPath)) {
                println("No state.db for profile '${profile.name}' — nothing to sweep.")
                return@runBlocking
            }

            val db = StateDatabase(dbPath)
            db.initialize()
            try {
                val syncRoot = profile.syncRoot
                if (!Files.isDirectory(syncRoot)) {
                    println("Sync root '$syncRoot' does not exist — nothing to sweep.")
                    return@runBlocking
                }

                println("Scanning $syncRoot for zero-byte stubs...")
                val stubs = findNullByteStubs(syncRoot, db)
                println("  scanned; flagged ${stubs.size} stub(s).")

                if (stubs.isEmpty()) {
                    println("No zero-byte stubs detected.")
                    return@runBlocking
                }

                println()
                println("First ${minOf(stubs.size, 20)} of ${stubs.size}:")
                for (p in stubs.take(20)) println("  $p")
                if (stubs.size > 20) println("  ... and ${stubs.size - 20} more")
                println()

                when {
                    rehydrate && !dryRun -> {
                        var flipped = 0
                        for (p in stubs) {
                            val entry = db.getEntry(p) ?: continue
                            db.upsertEntry(entry.copy(isHydrated = false))
                            flipped++
                        }
                        println("Marked $flipped entries as non-hydrated.")
                        println("Run 'unidrive -p ${profile.name} sync' — the UD-225 reconciler rescue will download real content.")
                    }
                    dryRun -> {
                        println("--dry-run: would mark ${stubs.size} entries as non-hydrated.")
                        println("Next sync would then download real content via the UD-225 rescue path.")
                    }
                    else -> {
                        println("Report only. Pass --rehydrate to mark these for re-download on the next sync,")
                        println("or --dry-run to preview.")
                    }
                }
            } finally {
                db.close()
            }
        }

    private fun findNullByteStubs(
        syncRoot: Path,
        db: StateDatabase,
    ): List<String> {
        val stubs = mutableListOf<String>()
        Files.walkFileTree(
            syncRoot,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (!attrs.isRegularFile) return FileVisitResult.CONTINUE
                    val size = attrs.size()
                    if (size == 0L) return FileVisitResult.CONTINUE

                    val relativePath = "/" + syncRoot.relativize(file).toString().replace('\\', '/')
                    val entry = db.getEntry(relativePath) ?: return FileVisitResult.CONTINUE
                    if (entry.isFolder) return FileVisitResult.CONTINUE
                    if (entry.remoteSize != size) return FileVisitResult.CONTINUE

                    if (isAllNullSampled(file, size)) {
                        stubs.add(relativePath)
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    val relativePath = if (dir == syncRoot) "/" else "/" + syncRoot.relativize(dir).toString().replace('\\', '/')
                    if (relativePath == "/.unidrive-trash" || relativePath == "/.unidrive-versions") {
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return stubs
    }

    /** Reads first + middle + last [SAMPLE_BYTES] of [file] and returns true iff all sampled bytes are 0. */
    private fun isAllNullSampled(
        file: Path,
        size: Long,
    ): Boolean {
        val offsets =
            buildList {
                add(0L)
                if (size > 2 * SAMPLE_BYTES) add(size / 2 - SAMPLE_BYTES / 2)
                if (size > SAMPLE_BYTES) add(size - SAMPLE_BYTES)
            }.distinct()

        RandomAccessFile(file.toFile(), "r").use { raf ->
            val buf = ByteArray(SAMPLE_BYTES)
            for (offset in offsets) {
                raf.seek(offset)
                val want = minOf(SAMPLE_BYTES.toLong(), size - offset).toInt()
                val n = raf.read(buf, 0, want)
                if (n < 0) return false
                for (i in 0 until n) {
                    if (buf[i] != 0.toByte()) return false
                }
            }
        }
        return true
    }

    companion object {
        private const val SAMPLE_BYTES = 4096
    }
}
