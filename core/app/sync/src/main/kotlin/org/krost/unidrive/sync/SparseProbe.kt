package org.krost.unidrive.sync

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * In-process detection of stub files (interrupted-sync placeholders, dehydrated files): a file
 * that has its full logical size but no real content. The JDK exposes no allocated-block count,
 * so instead of asking the filesystem, the probe reads the content. A file with real content
 * usually shows a non-zero byte in the head, the tail, or one of the evenly spaced sampled pages
 * in between, so it is cleared after one or a few page reads. A file whose samples are all zeros
 * is read end to end before it is reported: real bytes in a page between the samples (a disk or
 * filesystem image, a preallocated database) keep it real. Works the same on every platform and
 * spawns no process.
 *
 * Only a file that is zeros end to end is reported as a stub; such a file is byte-identical to one.
 * A file that is a partial write (real content in the head, a zero-filled tail) is not: the replaced
 * allocated-block check flagged any file with a hole, this probe flags only a file with no non-zero
 * byte at all. Any error (unreadable, locked, vanished) reports "not a stub", which keeps the bytes.
 */
internal object SparseProbe {
    const val PAGE: Int = 4096
    private const val MAX_SAMPLED_PAGES = 64

    /** Files this size or smaller are never reported as stubs (one filesystem page). */
    const val MIN_DETECTABLE_SIZE: Long = PAGE.toLong()

    fun isStub(
        path: Path,
        expectedSize: Long,
    ): Boolean {
        if (expectedSize <= 0L) return false
        return try {
            if (!Files.isRegularFile(path)) return false
            val actual = Files.size(path)
            if (actual == 0L) return expectedSize > 0L
            if (expectedSize <= MIN_DETECTABLE_SIZE) return false
            allSampledZero(path, actual)
        } catch (_: Exception) {
            false
        }
    }

    private fun allSampledZero(
        path: Path,
        size: Long,
    ): Boolean =
        FileChannel.open(path, StandardOpenOption.READ).use { ch ->
            val offsets = sampleOffsets(size)
            when {
                offsets.any { offset -> hasNonZeroByte(ch, offset) } -> false
                // Every page was sampled: the whole file has been read.
                offsets.size.toLong() == pageCount(size) -> true
                // Samples alone never condemn a file: confirm the pages between them.
                else -> !hasNonZeroByteAnywhere(ch)
            }
        }

    private fun pageCount(size: Long): Long = (size + PAGE - 1) / PAGE

    /** Page-aligned offsets to sample: every page when there are few, else head, tail and evenly spaced ones. */
    private fun sampleOffsets(size: Long): LongArray {
        val pages = pageCount(size)
        if (pages <= MAX_SAMPLED_PAGES) return LongArray(pages.toInt()) { it.toLong() * PAGE }
        val step = (pages - 1).toDouble() / (MAX_SAMPLED_PAGES - 1)
        return LongArray(MAX_SAMPLED_PAGES) { (it * step).toLong() * PAGE }
    }

    /** Reads up to one page at [offset] and reports whether any byte read is non-zero. */
    private fun hasNonZeroByte(
        ch: FileChannel,
        offset: Long,
    ): Boolean {
        val buf = ByteBuffer.allocate(PAGE)
        var read = 0
        while (buf.hasRemaining()) {
            val n = ch.read(buf, offset + read)
            if (n < 0) break
            read += n
        }
        for (i in 0 until read) {
            if (buf.get(i) != 0.toByte()) return true
        }
        return false
    }

    /** Reads the whole file and reports whether any byte is non-zero; stops at the first one. */
    private fun hasNonZeroByteAnywhere(ch: FileChannel): Boolean {
        val buf = ByteBuffer.allocate(SCAN_CHUNK)
        var offset = 0L
        while (true) {
            buf.clear()
            val n = ch.read(buf, offset)
            if (n < 0) return false
            // A read that makes no progress cannot prove the file empty: keep the bytes.
            if (n == 0) return true
            if ((0 until n).any { buf.get(it) != 0.toByte() }) return true
            offset += n
        }
    }

    private const val SCAN_CHUNK = 64 * 1024
}
