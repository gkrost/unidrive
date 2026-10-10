package org.krost.unidrive.sync

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SparseProbeTest {
    private lateinit var dir: Path
    private lateinit var db: StateDatabase

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("unidrive-sparse-probe")
        db = StateDatabase(Files.createTempDirectory("unidrive-sparse-db").resolve("state.db"))
        db.initialize()
    }

    @AfterTest
    fun tearDown() {
        db.close()
    }

    private fun stub(
        name: String,
        size: Long,
    ): Path {
        val p = dir.resolve(name)
        FileChannel.open(p, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { ch ->
            ch.position(size - 1)
            ch.write(ByteBuffer.wrap(byteArrayOf(0)))
        }
        return p
    }

    @Test
    fun `a file that is zeros end to end is detected as a stub`() {
        val p = stub("stub.bin", 1_000_000)
        assertTrue(SparseProbe.isStub(p, Files.size(p)))
    }

    @Test
    fun `a fully written file is not flagged`() {
        val p = dir.resolve("real.bin")
        Files.write(p, ByteArray(1_000_000) { (it % 251 + 1).toByte() })
        assertFalse(SparseProbe.isStub(p, Files.size(p)))
    }

    @Test
    fun `content only in the tail or middle is not flagged`() {
        val tail = stub("tail.bin", 1_000_000)
        FileChannel.open(tail, StandardOpenOption.WRITE).use { it.write(ByteBuffer.wrap(byteArrayOf(7)), 999_999) }
        assertFalse(SparseProbe.isStub(tail, 1_000_000))

        val big = stub("big.bin", 50L * 1024 * 1024)
        val pageStart = (50L * 1024 * 1024 / SparseProbe.PAGE / 2) * SparseProbe.PAGE
        FileChannel.open(big, StandardOpenOption.WRITE).use { ch ->
            // 4 MiB of content in the middle: wider than the sampling stride, so a sample must land in it
            ch.write(ByteBuffer.wrap(ByteArray(SparseProbe.PAGE * 1024) { 9 }), pageStart)
        }
        assertFalse(SparseProbe.isStub(big, Files.size(big)))
    }

    @Test
    fun `a file at or below one page is never flagged`() {
        val p = stub("small.bin", 4096)
        assertFalse(SparseProbe.isStub(p, 4096))
        val tiny = stub("tiny.bin", 100)
        assertFalse(SparseProbe.isStub(tiny, 100))
    }

    @Test
    fun `an empty file is a stub only when more bytes are expected`() {
        val p = dir.resolve("empty.bin")
        Files.createFile(p)
        assertTrue(SparseProbe.isStub(p, 100))
        assertFalse(SparseProbe.isStub(p, 0))
    }

    @Test
    fun `a missing path or a directory is not flagged`() {
        assertFalse(SparseProbe.isStub(dir.resolve("nope"), 100_000))
        assertFalse(SparseProbe.isStub(dir, 100_000))
    }

    @Test
    fun `the scanner records a new stub as not hydrated and a new real file as hydrated`() {
        val root = Files.createTempDirectory("unidrive-sparse-scan")
        FileChannel.open(root.resolve("stub.bin"), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use {
            it.position(99_999)
            it.write(ByteBuffer.wrap(byteArrayOf(0)))
        }
        Files.write(root.resolve("real.bin"), ByteArray(100_000) { 5 })
        LocalScanner(root, db).scan()
        assertEquals(false, db.getEntry("/stub.bin")?.isHydrated)
        assertEquals(true, db.getEntry("/real.bin")?.isHydrated)
    }

    @Test
    fun `no subprocess is spawned for sparse detection`() {
        val src = java.io.File("src/main/kotlin/org/krost/unidrive/sync")
        val offenders =
            src
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { "ProcessBuilder(" in it.readText() && "\"stat\"" in it.readText() }
                .map { it.name }
                .toList()
        assertTrue(offenders.isEmpty(), "stat subprocess still present in: $offenders")
    }
}
