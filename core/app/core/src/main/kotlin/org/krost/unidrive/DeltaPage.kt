package org.krost.unidrive

data class DeltaPage(
    val items: List<CloudItem>,
    val cursor: String,
    val hasMore: Boolean,
)
