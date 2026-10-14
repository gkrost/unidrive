package org.krost.unidrive

/**
 * Algorithms the SPI exposes for post-transfer integrity verification.
 *
 * A provider returns one of these from [CloudProvider.hashAlgorithm]
 * to declare which hash format its `remoteHash` strings carry.
 * Returning null from `hashAlgorithm()` means the provider has no
 * verifiable hash; callers MUST treat that as "skip verification"
 * rather than "verification passed".
 *
 * Add new variants here when a new provider needs an algorithm not
 * already represented.
 */
sealed class HashAlgorithm {
    /** OneDrive's QuickXorHash, encoded Base64. */
    object QuickXor : HashAlgorithm()

    /**
     * Plain MD5, lowercase hex. Matches simple S3 ETags. Multipart
     * S3 ETags (containing `-`) cannot be verified this way and
     * callers must skip verification when the remote hash matches
     * `<hex>-<n>`.
     */
    object Md5Hex : HashAlgorithm()

    /** SHA-256, lowercase hex. Reserved for future providers. */
    object Sha256Hex : HashAlgorithm()

    /**
     * Dropbox's `content_hash`, lowercase hex: the file is split into 4 MiB blocks (the last one may be shorter),
     * each block is hashed with SHA-256, and the SHA-256 of the concatenated block digests is the result. An empty
     * file has no blocks, so its hash is the SHA-256 of nothing.
     */
    object DropboxContentHash : HashAlgorithm()
}
