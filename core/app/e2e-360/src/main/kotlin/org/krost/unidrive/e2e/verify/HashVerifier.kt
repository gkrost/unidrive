package org.krost.unidrive.e2e.verify

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.text.Normalizer

enum class VerifyResult { PASS, FAIL, SKIP, MISSING }

data class VerifyOutcome(val path: String, val status: VerifyResult, val reason: String? = null)

object HashVerifier {

    fun sha3_512(file: Path): String {
        val digest = MessageDigest.getInstance("SHA3-512")
        val buf = ByteArray(8192)
        Files.newInputStream(file).use { stream ->
            var read: Int
            while (stream.read(buf).also { read = it } != -1) {
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun verifyFile(localFile: Path, entry: ManifestEntry): VerifyOutcome {
        val normalizedPath = Normalizer.normalize(entry.path, Normalizer.Form.NFC)

        if (!Files.exists(localFile)) {
            return VerifyOutcome(normalizedPath, VerifyResult.MISSING, "file not found")
        }

        return when (entry.verifyMode) {
            "skip" -> VerifyOutcome(normalizedPath, VerifyResult.SKIP)
            "size_only" -> {
                val actual = Files.size(localFile)
                if (actual == entry.size) {
                    VerifyOutcome(normalizedPath, VerifyResult.PASS)
                } else {
                    VerifyOutcome(normalizedPath, VerifyResult.FAIL, "size mismatch: expected ${entry.size}, got $actual")
                }
            }
            else -> {
                val actual = sha3_512(localFile)
                if (actual == entry.hash) {
                    VerifyOutcome(normalizedPath, VerifyResult.PASS)
                } else {
                    VerifyOutcome(normalizedPath, VerifyResult.FAIL, "hash mismatch")
                }
            }
        }
    }
}
