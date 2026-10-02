package org.krost.unidrive.internxt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import java.nio.file.Files
import java.nio.file.Path

/**
 * #311: unit coverage for the tombstone content fingerprint — the cheap
 * head+tail binding that stops an upload resume from reusing the pinned CTR
 * (key, IV) over different plaintext when a writer preserves mtime+size.
 *
 * The known blind spot (a same-size rewrite confined to the middle of a file
 * larger than two windows) is documented and intentionally enshrined here:
 * the crypto risk of that residual is closed separately by the provider's
 * ENCRYPTING-stage rule — a stage beyond ENCRYPTING with an unverifiable
 * `.enc` always cold-restarts with fresh indexBytes rather than re-encrypting
 * under the pinned keystream.
 */
class UploadTombstoneContentFingerprintTest {
    private fun tempFile(name: String, size: Int, fill: (Int) -> Byte): Path {
        val dir = Files.createTempDirectory("ud-fp-")
        return Files.createTempFile(dir, name, ".bin").also { p ->
            Files.write(p, ByteArray(size) { fill(it) })
        }
    }

    @Test
    fun `same content yields the same fingerprint`() {
        val a = tempFile("same-a-", 1024) { (it % 251).toByte() }
        val b = tempFile("same-b-", 1024) { (it % 251).toByte() }
        try {
            assertEquals(
                UploadTombstoneStore.contentFingerprint(a),
                UploadTombstoneStore.contentFingerprint(b),
            )
        } finally {
            a.parent.toFile().deleteRecursively()
            b.parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun `any byte change in a window-sized file trips the fingerprint`() {
        val dir = Files.createTempDirectory("ud-fp-small-")
        val p = Files.createTempFile(dir, "small-", ".bin").also { f ->
            Files.write(f, ByteArray(1024) { (it % 251).toByte() })
        }
        val original = UploadTombstoneStore.contentFingerprint(p)
        try {
            // First byte.
            Files.write(p, ByteArray(1024) { if (it == 0) 0x7F else (it % 251).toByte() })
            assertNotEquals(original, UploadTombstoneStore.contentFingerprint(p))
            // Last byte (file ≤ one window → hashed in full).
            Files.write(p, ByteArray(1024) { if (it == 1023) 0x7F else (it % 251).toByte() })
            assertNotEquals(original, UploadTombstoneStore.contentFingerprint(p))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `head and tail windows of a large file are covered`() {
        val dir = Files.createTempDirectory("ud-fp-large-")
        val size = 3 * 64 * 1024 // 192 KiB — comfortably two windows plus a middle
        val p = Files.createTempFile(dir, "large-", ".bin").also { f ->
            Files.write(f, ByteArray(size) { (it % 241).toByte() })
        }
        val original = UploadTombstoneStore.contentFingerprint(p)
        try {
            // First byte.
            Files.write(p, ByteArray(size) { if (it == 0) 0x7F else (it % 241).toByte() })
            assertNotEquals(original, UploadTombstoneStore.contentFingerprint(p), "head window not covered")
            // Last byte.
            Files.write(p, ByteArray(size) { if (it == size - 1) 0x7F else (it % 241).toByte() })
            assertNotEquals(original, UploadTombstoneStore.contentFingerprint(p), "tail window not covered")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `middle-only change beyond the windows is a documented blind spot (#311 residual)`() {
        val dir = Files.createTempDirectory("ud-fp-mid-")
        val size = 3 * 64 * 1024
        val p = Files.createTempFile(dir, "mid-", ".bin").also { f ->
            Files.write(f, ByteArray(size) { (it % 239).toByte() })
        }
        val original = UploadTombstoneStore.contentFingerprint(p)
        try {
            val middle = size / 2
            Files.write(p, ByteArray(size) { if (it == middle) 0x7F else (it % 239).toByte() })
            assertEquals(
                original,
                UploadTombstoneStore.contentFingerprint(p),
                "documented residual: mid-file rewrites past the two 64 KiB windows are invisible to the " +
                    "fingerprint; the provider's unverifiable-.enc backstop covers the crypto risk, and the " +
                    "7-day RESUME_TTL bounds the stale-upload window",
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `empty file fingerprint is stable`() {
        val a = tempFile("empty-a-", 0) { 0 }
        val b = tempFile("empty-b-", 0) { 0 }
        try {
            assertEquals(
                UploadTombstoneStore.contentFingerprint(a),
                UploadTombstoneStore.contentFingerprint(b),
            )
        } finally {
            a.parent.toFile().deleteRecursively()
            b.parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun `schema-1 (pre-fingerprint) tombstones are rejected at parse time`() {
        val dir = Files.createTempDirectory("ud-fp-schema-")
        try {
            val store = UploadTombstoneStore(dir)
            val pathHash = UploadTombstoneStore.pathHash("/some/file.bin")
            // A schema-1 sidecar written by a pre-#311 build: no
            // contentFingerprintHex, discriminator still 1.
            val legacy = """{"schema":1,"localPath":"/some/file.bin","localMtimeMillis":1700000000000,""" +
                """"localSize":1024,"bucket":"b","folderUuid":"f","plainName":"file","ext":"bin",""" +
                """"indexBytesHex":"${"00".repeat(32)}","stage":"PUT_PENDING",""" +
                """"startedAtMillis":1700000000000,"tombstoneWrittenAtMillis":1700000000000}"""
            val jsonPath = dir.resolve("$pathHash.json")
            Files.writeString(jsonPath, legacy)
            assertNull(
                store.read(pathHash),
                "schema-1 tombstones carry no content binding — they must route to discard (cold restart), " +
                    "never to a resume",
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
