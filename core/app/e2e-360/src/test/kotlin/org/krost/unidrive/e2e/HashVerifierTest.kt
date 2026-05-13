package org.krost.unidrive.e2e

import org.krost.unidrive.e2e.verify.HashVerifier
import org.krost.unidrive.e2e.verify.ManifestEntry
import org.krost.unidrive.e2e.verify.VerifyResult
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class HashVerifierTest {

    @Test
    fun `sha3-512 hash is deterministic`() {
        val file = Files.createTempFile("hash-test-", ".bin")
        try {
            Files.write(file, "deterministic content".toByteArray())
            val h1 = HashVerifier.sha3_512(file)
            val h2 = HashVerifier.sha3_512(file)
            assertEquals(h1, h2)
            assertEquals(128, h1.length)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `different content produces different hash`() {
        val f1 = Files.createTempFile("hash-a-", ".bin")
        val f2 = Files.createTempFile("hash-b-", ".bin")
        try {
            Files.write(f1, "content alpha".toByteArray())
            Files.write(f2, "content beta".toByteArray())
            assertNotEquals(HashVerifier.sha3_512(f1), HashVerifier.sha3_512(f2))
        } finally {
            Files.deleteIfExists(f1)
            Files.deleteIfExists(f2)
        }
    }

    @Test
    fun `verifyFile returns PASS for matching hash`() {
        val file = Files.createTempFile("verify-pass-", ".bin")
        try {
            Files.write(file, "some file content".toByteArray())
            val hash = HashVerifier.sha3_512(file)
            val entry = ManifestEntry(
                path = file.toString(),
                size = Files.size(file),
                hash = hash,
            )
            val outcome = HashVerifier.verifyFile(file, entry)
            assertEquals(VerifyResult.PASS, outcome.status)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `verifyFile returns FAIL for size_only mode with wrong size`() {
        val file = Files.createTempFile("verify-fail-", ".bin")
        try {
            Files.write(file, "actual content".toByteArray())
            val entry = ManifestEntry(
                path = file.toString(),
                size = 99999L,
                hash = "ignored",
                verify_mode = "size_only",
            )
            val outcome = HashVerifier.verifyFile(file, entry)
            assertEquals(VerifyResult.FAIL, outcome.status)
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
