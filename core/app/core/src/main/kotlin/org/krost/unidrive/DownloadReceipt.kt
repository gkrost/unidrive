package org.krost.unidrive

/** Result metadata for a provider download that can hash the plaintext while streaming it. */
data class DownloadReceipt(
    val bytesWritten: Long,
    val plaintextSha256: String? = null,
    val stagedSize: Long? = null,
    val stagedMtimeMillis: Long? = null,
    val mtimeAppliedBeforePlacement: Boolean = false,
)
