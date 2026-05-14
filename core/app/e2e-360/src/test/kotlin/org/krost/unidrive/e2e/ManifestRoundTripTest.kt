package org.krost.unidrive.e2e

import org.krost.unidrive.e2e.verify.ManifestEntry
import org.krost.unidrive.e2e.verify.ManifestReader
import org.krost.unidrive.e2e.verify.ManifestWriter
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class ManifestRoundTripTest {

    @Test
    fun `write and read back three entries`() {
        val tmp = Files.createTempFile("manifest-", ".jsonl")
        try {
            val entries = listOf(
                ManifestEntry(path = "folder/file1.txt", size = 1024L, hash = "abc123"),
                ManifestEntry(path = "folder/file2.bin", size = 2048L, hash = "def456", verify_mode = "size_only"),
                ManifestEntry(path = "folder/encoded.jpg", size = 512L, hash = "ghi789", encoding = "UTF-8"),
            )

            ManifestWriter.write(tmp, entries)
            val read = ManifestReader.read(tmp)

            assertEquals(3, read.size)

            assertEquals("folder/file1.txt", read[0].path)
            assertEquals(1024L, read[0].size)
            assertEquals("abc123", read[0].hash)
            assertEquals("full_hash", read[0].verify_mode)
            assertEquals(null, read[0].encoding)

            assertEquals("folder/file2.bin", read[1].path)
            assertEquals(2048L, read[1].size)
            assertEquals("def456", read[1].hash)
            assertEquals("size_only", read[1].verify_mode)
            assertEquals(null, read[1].encoding)

            assertEquals("folder/encoded.jpg", read[2].path)
            assertEquals(512L, read[2].size)
            assertEquals("ghi789", read[2].hash)
            assertEquals("full_hash", read[2].verify_mode)
            assertEquals("UTF-8", read[2].encoding)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }
}
