package org.krost.unidrive.sync

import java.nio.file.Files
import kotlin.test.*

class BenchmarkRunnerTest {

    @Test
    fun `generateTestFile creates deterministic content`() {
        val tmp = Files.createTempDirectory("bench-gen")
        val file1 = BenchmarkRunner.generateTestFile(tmp, 1024)
        val file2 = BenchmarkRunner.generateTestFile(tmp, 1024)
        assertEquals(1024, Files.size(file1))
        assertContentEquals(Files.readAllBytes(file1), Files.readAllBytes(file2))
        val file3 = BenchmarkRunner.generateTestFile(tmp, 2048)
        assertEquals(2048, Files.size(file3))
    }

    @Test
    fun `parseSizes handles common formats`() {
        assertEquals(listOf(1024L), BenchmarkRunner.parseSizes("1KB"))
        assertEquals(listOf(1024L, 1048576L), BenchmarkRunner.parseSizes("1KB,1MB"))
        assertEquals(listOf(104857600L), BenchmarkRunner.parseSizes("100MB"))
    }

    @Test
    fun `default file sizes`() {
        val defaults = BenchmarkRunner.DEFAULT_SIZES
        assertEquals(5, defaults.size)
        assertEquals(1024L, defaults[0])
        assertEquals(104857600L, defaults[4])
    }
}
