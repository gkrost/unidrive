package org.krost.unidrive.xtra

import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.ProviderException
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom

class XtraEncryptedProvider(
    private val inner: CloudProvider,
    private val keyManager: XtraKeyManager,
    private val strategy: XtraEncryptionStrategy = AesGcmStrategy(),
) : CloudProvider by inner {
    override suspend fun upload(
        localPath: Path,
        remotePath: String,
        existingRemoteId: String?,
        onProgress: ((Long, Long) -> Unit)?,
    ): CloudItem {
        val fileKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(strategy.ivLength).also { SecureRandom().nextBytes(it) }
        val wrappedKey = keyManager.wrapKey(fileKey)

        val tmp = Files.createTempFile("xtra-", ".tmp")
        try {
            FileOutputStream(tmp.toFile()).use { out ->
                XtraHeader.write(out, strategy.algorithmId, wrappedKey, iv)
                FileInputStream(localPath.toFile()).use { input ->
                    strategy.encrypt(input, out, fileKey, iv)
                }
            }
            return inner.upload(tmp, remotePath, existingRemoteId, onProgress)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    override suspend fun download(
        remotePath: String,
        destination: Path,
    ): Long {
        val tmp = Files.createTempFile("xtra-", ".tmp")
        try {
            inner.download(remotePath, tmp)
            return decryptToDestination(tmp, destination)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    override suspend fun downloadById(
        remoteId: String,
        remotePath: String,
        destination: Path,
    ): Long {
        val tmp = Files.createTempFile("xtra-", ".tmp")
        try {
            inner.downloadById(remoteId, remotePath, tmp)
            return decryptToDestination(tmp, destination)
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    private fun decryptToDestination(
        encryptedFile: Path,
        destination: Path,
    ): Long {
        FileInputStream(encryptedFile.toFile()).use { input ->
            when (val result = XtraHeader.read(input)) {
                is XtraHeader.ReadResult.NotEncrypted ->
                    throw ProviderException("File is not XTRA-encrypted")
                is XtraHeader.ReadResult.Corrupted ->
                    throw ProviderException("XTRA header corrupted: ${result.reason}")
                is XtraHeader.ReadResult.Valid -> {
                    val fileKey = keyManager.unwrapKey(result.wrappedKey)
                    FileOutputStream(destination.toFile()).use { out ->
                        strategy.decrypt(input, out, fileKey, result.iv)
                    }
                }
            }
        }
        return Files.size(destination)
    }
}
