package org.krost.unidrive.sync

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * In-process detection of stub files (interrupted-sync placeholders, dehydrated files): a file
 * that has its full logical size but no real content. The JDK exposes no allocated-block count,
 * so instead of asking the filesystem, the probe samples the content: a stub reads back as
 * zeros everywhere, a file with real content shows a non-zero byte in the head, the tail, or one
 * of the evenly spaced pages in between. Works the same on every platform and spawns no process.
 *
 * A file whose content is genuinely all zeros in every sampled page is indistinguishable from a
 * stub and is reported as one. Conversely, a file that is a partial write (real content in the head,
 * a zero-filled tail) is not reported as a stub: the probe flags a file only when every sampled page
 * reads as zeros, whereas the replaced allocated-block check flagged any file with a hole.
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
            sampleOffsets(size).none { offset -> hasNonZeroByte(ch, offset) }
        }

    /** Page-aligned offsets to sample: every page when there are few, else head, tail and evenly spaced ones. */
    private fun sampleOffsets(size: Long): LongArray {
        val pages = (size + PAGE - 1) / PAGE
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
}
