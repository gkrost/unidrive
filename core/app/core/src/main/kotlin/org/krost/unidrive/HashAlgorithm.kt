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
}
