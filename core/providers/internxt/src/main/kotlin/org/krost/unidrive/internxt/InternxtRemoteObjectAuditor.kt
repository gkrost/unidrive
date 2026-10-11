package org.krost.unidrive.internxt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.krost.unidrive.AuthenticationException
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
            when (val result = lookup { getFileMeta(item.id) }) {
                is Lookup.Found -> result.value
                // A 404 here means the drive file was removed after the listing was taken: that is not
                // a stored object gone missing, so it must not be reported as one.
                is Lookup.Failed ->
                    return record(
                        item,
                        RemoteObjectStatus.UNVERIFIED,
                        detail =
                            if (result.statusCode == 404) {
                                "drive file no longer exists (removed since the listing)"
                            } else {
                                "drive metadata request failed" + statusSuffix(result.statusCode)
                            },
                    )
            }
        // The metadata size defaults to "0" when the field is absent; trusting that would report a file
        // without any bridge reference as an empty (healthy) one. The listing carries the same field, so
        // the two must agree, otherwise the size is unknown and so is the verdict.
        val driveSize = metadata.size.toLongOrNull()
        if (driveSize == null || driveSize < 0L || driveSize != item.size) {
            return record(
                item,
                RemoteObjectStatus.UNVERIFIED,
                driveSize?.takeIf { it >= 0L } ?: item.size,
                "drive size is unreadable or differs from the listing",
            )
        }
        val bucket = metadata.bucket
        val fileId = metadata.fileId
        if (bucket.isNullOrBlank() || fileId.isNullOrBlank()) {
            val status = if (driveSize == 0L) RemoteObjectStatus.HEALTHY else RemoteObjectStatus.MISSING
            val detail = if (driveSize == 0L) "empty drive file has no stored object" else "drive file has no bridge object reference"
            return record(item, status, driveSize, detail)
        }

        delay(MIN_REQUEST_SPACING_MS)
        val bridgeInfo =
            when (val result = lookup { getBridgeFileInfo(bucket, fileId) }) {
                is Lookup.Found -> result.value
                is Lookup.Failed ->
                    return if (result.statusCode == 404) {
                        record(item, RemoteObjectStatus.MISSING, driveSize, "bridge object metadata is missing")
                    } else {
                        record(
                            item,
                            RemoteObjectStatus.UNVERIFIED,
                            driveSize,
                            "bridge object metadata request failed" + statusSuffix(result.statusCode),
                        )
                    }
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

        // One mirror query returns at most BRIDGE_MIRROR_LIMIT shard pointers. A shard that was not asked for
        // is not a shard that is gone, so an object with more shards than that cannot be judged.
        val shardCount = bridgeInfo.shards.filter { it.url.isNotBlank() }.map { it.index }.toSet().size
        if (shardCount > InternxtApiService.BRIDGE_MIRROR_LIMIT) {
            return record(
                item,
                RemoteObjectStatus.UNVERIFIED,
                driveSize,
                "object has $shardCount shards, more than one availability query covers (${InternxtApiService.BRIDGE_MIRROR_LIMIT})",
                bridgeInfo,
            )
        }

        delay(MIN_REQUEST_SPACING_MS)
        val mirrors =
            when (val result = lookup { getBridgeMirrors(bucket, fileId) }) {
                is Lookup.Found -> result.value
                is Lookup.Failed ->
                    return if (result.statusCode == 404) {
                        record(item, RemoteObjectStatus.MISSING, driveSize, "bridge object has no availability records", bridgeInfo)
                    } else {
                        record(
                            item,
                            RemoteObjectStatus.UNVERIFIED,
                            driveSize,
                            "bridge availability request failed" + statusSuffix(result.statusCode),
                            bridgeInfo,
                        )
                    }
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

    private sealed interface Lookup<out T> {
        class Found<T>(
            val value: T,
        ) : Lookup<T>

        /** The request failed; [statusCode] is the HTTP status when the failure was an API answer. */
        class Failed(
            val statusCode: Int?,
        ) : Lookup<Nothing>
    }

    /**
     * Runs one lookup. Cancellation and a dead session end the whole audit: recording them as one object's
     * UNVERIFIED would swallow the cancellation, and with a rejected token would walk the remaining drive at
     * one request per 500 ms, marking every file unverified. Any other failure is that object's unknown.
     */
    private suspend fun <T> lookup(call: suspend () -> T): Lookup<T> =
        try {
            Lookup.Found(call())
        } catch (e: CancellationException) {
            throw e
        } catch (e: AuthenticationException) {
            throw e
        } catch (e: InternxtApiException) {
            Lookup.Failed(e.statusCode)
        } catch (_: Exception) {
            Lookup.Failed(null)
        }

    private fun statusSuffix(statusCode: Int?): String = if (statusCode != null && statusCode > 0) " ($statusCode)" else ""

    private companion object {
        const val MIN_REQUEST_SPACING_MS = 500L
    }
}
