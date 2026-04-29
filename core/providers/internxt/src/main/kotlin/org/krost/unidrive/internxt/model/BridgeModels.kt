package org.krost.unidrive.internxt.model

import kotlinx.serialization.Serializable

@Serializable
data class Farmer(
    val userAgent: String = "",
    val protocol: String = "https",
    val address: String,
    val port: Int,
    val nodeID: String = "",
    val lastSeen: String = "",
)

@Serializable
data class Mirror(
    val index: Int = 0,
    val hash: String = "",
    val size: Long = 0,
    val parity: Boolean = false,
    val token: String = "",
    val healthy: Boolean? = null,
    val farmer: Farmer,
    val url: String,
    val operation: String = "",
)

@Serializable
data class BridgeShard(
    val index: Int = 0,
    val hash: String = "",
    val url: String = "", // presigned download URL
)

@Serializable
data class BridgeFileInfo(
    val bucket: String,
    val mimetype: String = "",
    val filename: String = "",
    val frame: String = "",
    val size: Long = 0,
    val id: String,
    val index: String, // 64-char hex — the encryption index
    val shards: List<BridgeShard> = emptyList(),
)

@Serializable
data class UploadDescriptor(
    val index: Int = 0,
    val uuid: String,
    val url: String? = null,
)

@Serializable
data class StartUploadResponse(
    val uploads: List<UploadDescriptor>,
)

@Serializable
data class ShardMeta(
    val hash: String,
    val uuid: String,
)

@Serializable
data class FinishUploadRequest(
    val index: String, // 64-char hex
    val shards: List<ShardMeta>,
)

@Serializable
data class BucketEntry(
    val id: String, // fileId for drive registration
    val index: String = "",
    val bucket: String = "",
    val name: String = "",
)
