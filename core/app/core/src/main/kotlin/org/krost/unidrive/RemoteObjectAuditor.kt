package org.krost.unidrive

/** Read-only provider surface for checking stored objects without downloading them. */
interface RemoteObjectAuditor {
    /** Audit the file items from a complete provider listing. Implementations must not mutate remote or local sync state. */
    suspend fun auditRemoteObjects(files: List<CloudItem>): RemoteObjectAuditReport
}

data class RemoteObjectAuditReport(
    val checkedCount: Int,
    /** One record per listed file, including healthy objects, so the JSON report is a reusable audit artifact. */
    val objects: List<RemoteObjectAuditRecord>,
)

data class RemoteObjectAuditRecord(
    val path: String,
    val status: RemoteObjectStatus,
    val driveSize: Long,
    val bridgeSize: Long? = null,
    val shardCount: Int? = null,
    val availableShardCount: Int? = null,
    val shardBytes: Long? = null,
    /** Safe diagnostic category; providers must not put credentials or response bodies here. */
    val detail: String? = null,
)

enum class RemoteObjectStatus {
    HEALTHY,
    MISSING,
    EMPTY,
    TRUNCATED,
    UNVERIFIED,
}
