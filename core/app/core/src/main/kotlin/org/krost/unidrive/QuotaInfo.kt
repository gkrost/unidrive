package org.krost.unidrive

data class QuotaInfo(
    val total: Long,
    val used: Long,
    val remaining: Long,
)
