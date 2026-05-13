package org.krost.unidrive.e2e

import org.krost.unidrive.e2e.verify.ManifestReader
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

class GoldenStructureBuilderTest {

    private fun makeCtx(): Pair<RunContext, java.nio.file.Path> {
        val tmpDir = Files.createTempDirectory("gsb-test")
        val config = ConfigLoader.parse("[general]\nprofile = \"dev\"\n[run]\nrun_id = \"test\"\n")
        val ctx = RunContext.create(config, tmpDir, "onedrive")
        return ctx to tmpDir
    }

    @Test
    fun `dev profile generates approximately 30 files`() {
        val (ctx, _) = makeCtx()
        val rootDir = Files.createTempDirectory("gsb-root")
        val entries = GoldenStructureBuilder(ctx).build(rootDir)
        assertTrue(entries.size in 20..80, "Expected 20..80 entries but got ${entries.size}")
    }

    @Test
    fun `golden structure includes Documents, Encodings, Unicode, Edge_Cases`() {
        val (ctx, _) = makeCtx()
        val rootDir = Files.createTempDirectory("gsb-root")
        GoldenStructureBuilder(ctx).build(rootDir)
        assertTrue(Files.isDirectory(rootDir.resolve("Documents")), "Documents dir missing")
        assertTrue(Files.isDirectory(rootDir.resolve("Encodings")), "Encodings dir missing")
        assertTrue(Files.isDirectory(rootDir.resolve("Unicode")), "Unicode dir missing")
        assertTrue(Files.isDirectory(rootDir.resolve("Edge_Cases")), "Edge_Cases dir missing")
    }

    @Test
    fun `manifest file is valid JSONL`() {
        val (ctx, _) = makeCtx()
        val rootDir = Files.createTempDirectory("gsb-root")
        GoldenStructureBuilder(ctx).build(rootDir)
        val manifestPath = rootDir.resolve("manifest.sha3-512.jsonl")
        assertTrue(Files.exists(manifestPath), "Manifest file missing")
        val entries = ManifestReader.read(manifestPath)
        assertTrue(entries.isNotEmpty(), "Manifest is empty")
        entries.forEach { entry ->
            assertTrue(entry.hash.length == 128, "Hash length ${entry.hash.length} != 128 for ${entry.path}")
        }
    }

    @Test
    fun `edge cases include hidden file and zero-byte file`() {
        val (ctx, _) = makeCtx()
        val rootDir = Files.createTempDirectory("gsb-root")
        GoldenStructureBuilder(ctx).build(rootDir)
        val edgeCasesDir = rootDir.resolve("Edge_Cases")
        assertTrue(Files.exists(edgeCasesDir.resolve(".hidden_file")), ".hidden_file missing")
        val zeroFile = edgeCasesDir.resolve("empty_file_zero_bytes.dat")
        assertTrue(Files.exists(zeroFile), "empty_file_zero_bytes.dat missing")
        assertTrue(Files.size(zeroFile) == 0L, "empty_file_zero_bytes.dat is not zero bytes")
    }
}
