package org.krost.unidrive.internxt

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.krost.unidrive.CloudItem
import org.krost.unidrive.RemoteObjectAuditRecord
import org.krost.unidrive.RemoteObjectAuditReport
import org.krost.unidrive.RemoteObjectAuditor
import org.krost.unidrive.RemoteObjectStatus
import org.krost.unidrive.internxt.model.BridgeFileInfo
import org.krost.unidrive.internxt.model.InternxtFile
import org.krost.unidrive.internxt.model.Mirror

/** Serial, rate-limited remote verification. It inspects metadata only and never fetches shard bodies. */
internal class InternxtRemoteObjectAuditor(
    private val getFileMeta: suspend (String) -> InternxtFile,
    private val getBridgeFileInfo: suspend (String, String) -> BridgeFileInfo,
    private val getBridgeMirrors: suspend (String, String) -> List<Mirror>,
) : RemoteObjectAuditor {
    private val auditLock = Mutex()

    override suspend fun auditRemoteObjects(files: List<CloudItem>): RemoteObjectAuditReport =
        auditLock.withLock {
            val records = ArrayList<RemoteObjectAuditRecord>(files.size)
            for ((index, item) in files.withIndex()) {
                if (index > 0) delay(MIN_REQUEST_SPACING_MS)
                records += auditOne(item)
            }
            RemoteObjectAuditReport(checkedCount = records.size, objects = records)
        }

    private suspend fun auditOne(item: CloudItem): RemoteObjectAuditRecord {
        val metadata =
            try {
                getFileMeta(item.id)
            } catch (e: InternxtApiException) {
                return if (e.statusCode == 404) {
                    record(item, RemoteObjectStatus.MISSING, detail = "drive metadata disappeared during audit")
                } else {
                    record(item, RemoteObjectStatus.UNVERIFIED, detail = "drive metadata request failed (${e.statusCode})")
                }
            } catch (_: Exception) {
                return record(item, RemoteObjectStatus.UNVERIFIED, detail = "drive metadata request failed")
            }
        val driveSize = metadata.size.toLongOrNull() ?: item.size
        val bucket = metadata.bucket
        val fileId = metadata.fileId
        if (bucket.isNullOrBlank() || fileId.isNullOrBlank()) {
            val status = if (driveSize == 0L) RemoteObjectStatus.HEALTHY else RemoteObjectStatus.MISSING
            val detail = if (driveSize == 0L) "empty drive file has no stored object" else "drive file has no bridge object reference"
            return record(item, status, driveSize, detail)
        }

        delay(MIN_REQUEST_SPACING_MS)
        val bridgeInfo =
            try {
                getBridgeFileInfo(bucket, fileId)
            } catch (e: InternxtApiException) {
                return if (e.statusCode == 404) {
                    record(item, RemoteObjectStatus.MISSING, driveSize, "bridge object metadata is missing")
                } else {
                    record(item, RemoteObjectStatus.UNVERIFIED, driveSize, "bridge object metadata request failed (${e.statusCode})")
                }
            } catch (_: Exception) {
                return record(item, RemoteObjectStatus.UNVERIFIED, driveSize, "bridge object metadata request failed")
            }

        if (bridgeInfo.bucket != bucket || bridgeInfo.id != fileId) {
            return record(
                item,
                RemoteObjectStatus.UNVERIFIED,
                driveSize,
                "bridge metadata identity does not match drive metadata",
                bridgeInfo = bridgeInfo,
            )
        }

        delay(MIN_REQUEST_SPACING_MS)
        val mirrors =
            try {
                getBridgeMirrors(bucket, fileId)
            } catch (e: InternxtApiException) {
                return if (e.statusCode == 404) {
                    record(
                        item,
                        RemoteObjectStatus.MISSING,
                        driveSize,
                        "bridge object has no availability records",
                        bridgeInfo,
                    )
                } else {
                    record(item, RemoteObjectStatus.UNVERIFIED, driveSize, "bridge availability request failed (${e.statusCode})", bridgeInfo)
                }
            } catch (_: Exception) {
                return record(item, RemoteObjectStatus.UNVERIFIED, driveSize, "bridge availability request failed", bridgeInfo)
            }

        return classify(item, driveSize, bridgeInfo, mirrors)
    }

    private fun classify(
        item: CloudItem,
        driveSize: Long,
        bridgeInfo: BridgeFileInfo,
        mirrors: List<Mirror>,
    ): RemoteObjectAuditRecord {
        val shardIndexes = bridgeInfo.shards.filter { it.url.isNotBlank() }.map { it.index }.toSet()
        val mirrorBytesByIndex =
            mirrors
                .filter { it.healthy != false && it.index in shardIndexes }
                .groupBy { it.index }
                .mapValues { (_, copies) -> copies.maxOfOrNull { it.size.coerceAtLeast(0L) } ?: 0L }
        val shardBytes = mirrorBytesByIndex.values.sum()
        val availableShards = mirrorBytesByIndex.count { (_, size) -> size > 0L }
        fun record(status: RemoteObjectStatus, detail: String?) =
            RemoteObjectAuditRecord(
                path = item.path,
                status = status,
                driveSize = driveSize,
                bridgeSize = bridgeInfo.size,
                shardCount = shardIndexes.size,
                availableShardCount = availableShards,
                shardBytes = shardBytes,
                detail = detail,
            )

        if (driveSize == 0L && shardIndexes.isEmpty() && shardBytes == 0L) {
            return record(RemoteObjectStatus.HEALTHY, "empty drive file has no stored object")
        }
        if (shardIndexes.isEmpty() || shardBytes == 0L) {
            return record(RemoteObjectStatus.EMPTY, "non-empty drive file has no non-empty bridge shards")
        }
        if (availableShards == 0) {
            return record(RemoteObjectStatus.MISSING, "bridge object has no available shard mirror")
        }
        if (availableShards < shardIndexes.size) {
            return record(RemoteObjectStatus.TRUNCATED, "one or more bridge shards have no available mirror")
        }
        if ((bridgeInfo.size > 0L && bridgeInfo.size < driveSize) || shardBytes < maxOf(bridgeInfo.size, driveSize)) {
            return record(RemoteObjectStatus.TRUNCATED, "bridge or shard byte total is below the drive size")
        }
        return record(RemoteObjectStatus.HEALTHY, null)
    }

    private fun record(
        item: CloudItem,
        status: RemoteObjectStatus,
        driveSize: Long = item.size,
        detail: String? = null,
        bridgeInfo: BridgeFileInfo? = null,
    ) =
        RemoteObjectAuditRecord(
            path = item.path,
            status = status,
            driveSize = driveSize,
            bridgeSize = bridgeInfo?.size,
            shardCount = bridgeInfo?.shards?.size,
            detail = detail,
        )

    private companion object {
        const val MIN_REQUEST_SPACING_MS = 500L
    }
}
