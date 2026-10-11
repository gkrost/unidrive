package org.krost.unidrive.internxt

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.CloudItem
import org.krost.unidrive.RemoteObjectStatus
import org.krost.unidrive.internxt.model.BridgeFileInfo
import org.krost.unidrive.internxt.model.BridgeShard
import org.krost.unidrive.internxt.model.Farmer
import org.krost.unidrive.internxt.model.InternxtFile
import org.krost.unidrive.internxt.model.Mirror
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit-level pins for [InternxtRemoteObjectAuditor] with injected lookups (no HTTP): the failure and
 * "unknown" paths of the classification, which the end-to-end mock test does not reach (#769).
 */
class InternxtRemoteObjectAuditorTest {
    private val bucket = "bucket-1"

    private fun listed(
        id: String,
        size: Long = 100,
    ) = CloudItem(
        id = id,
        name = "$id.bin",
        path = "/$id.bin",
        size = size,
        isFolder = false,
        modified = Instant.EPOCH,
        created = Instant.EPOCH,
        hash = null,
        mimeType = null,
    )

    private fun meta(
        id: String,
        size: String = "100",
        withObject: Boolean = true,
    ) = InternxtFile(
        uuid = id,
        fileId = if (withObject) "bridge-$id" else null,
        bucket = if (withObject) bucket else null,
        size = size,
    )

    private fun info(
        id: String,
        size: Long = 100,
        shards: Int = 1,
    ) = BridgeFileInfo(
        bucket = bucket,
        id = "bridge-$id",
        size = size,
        index = "11".repeat(32),
        shards = (0 until shards).map { BridgeShard(index = it, hash = "h$it", url = "https://shard.invalid/$it") },
    )

    private fun mirror(
        index: Int,
        size: Long,
        healthy: Boolean? = true,
    ) = Mirror(index = index, size = size, healthy = healthy, farmer = Farmer(address = "node.invalid", port = 443), url = "https://node.invalid/$index")

    private fun auditor(
        fileMeta: suspend (String) -> InternxtFile = { meta(it) },
        bridgeInfo: suspend (String, String) -> BridgeFileInfo = { _, fileId -> info(fileId.removePrefix("bridge-")) },
        mirrors: suspend (String, String) -> List<Mirror> = { _, _ -> listOf(mirror(0, 100)) },
    ) = InternxtRemoteObjectAuditor(fileMeta, bridgeInfo, mirrors)

    @Test
    fun `a drive file that vanished after the listing is unverified, not a lost object`() =
        runTest {
            val report =
                auditor(fileMeta = { throw InternxtApiException("gone", 404) })
                    .auditRemoteObjects(listOf(listed("a")))
            assertEquals(RemoteObjectStatus.UNVERIFIED, report.objects.single().status)
        }

    @Test
    fun `a throttled or failing lookup stays unverified and never healthy`() =
        runTest {
            val metaFails = auditor(fileMeta = { throw InternxtApiException("busy", 429) }).auditRemoteObjects(listOf(listed("a")))
            val infoFails = auditor(bridgeInfo = { _, _ -> throw InternxtApiException("busy", 503) }).auditRemoteObjects(listOf(listed("a")))
            val mirrorsFail = auditor(mirrors = { _, _ -> throw java.io.IOException("reset") }).auditRemoteObjects(listOf(listed("a")))
            assertEquals(
                listOf(RemoteObjectStatus.UNVERIFIED, RemoteObjectStatus.UNVERIFIED, RemoteObjectStatus.UNVERIFIED),
                listOf(metaFails, infoFails, mirrorsFail).map { it.objects.single().status },
            )
        }

    @Test
    fun `an authentication failure aborts the audit instead of marking every remaining file`() =
        runTest {
            var calls = 0
            val subject =
                auditor(fileMeta = {
                    calls++
                    throw AuthenticationException("Authentication failed (401)")
                })
            assertFailsWith<AuthenticationException> { subject.auditRemoteObjects(listOf(listed("a"), listed("b"), listed("c"))) }
            assertEquals(1, calls, "the audit must stop at the first authentication failure")
        }

    @Test
    fun `cancellation is not swallowed into an unverified record`() =
        runTest {
            val subject = auditor(bridgeInfo = { _, _ -> throw CancellationException("stop") })
            assertFailsWith<CancellationException> { subject.auditRemoteObjects(listOf(listed("a"))) }
        }

    @Test
    fun `a drive size that disagrees with the listing is unverified`() =
        runTest {
            // The metadata default for a missing size is "0": without a cross-check, a file without any
            // bridge reference would be reported healthy as an "empty" file.
            val report =
                auditor(fileMeta = { meta(it, size = "0", withObject = false) })
                    .auditRemoteObjects(listOf(listed("a", size = 100)))
            assertEquals(RemoteObjectStatus.UNVERIFIED, report.objects.single().status)
        }

    @Test
    fun `an unreadable drive size is unverified`() =
        runTest {
            val report =
                auditor(fileMeta = { meta(it, size = "n/a", withObject = false) })
                    .auditRemoteObjects(listOf(listed("a", size = 0)))
            assertEquals(RemoteObjectStatus.UNVERIFIED, report.objects.single().status)
        }

    @Test
    fun `an empty drive file without a stored object is healthy and a non-empty one is missing`() =
        runTest {
            val report =
                auditor(fileMeta = { meta(it, size = if (it == "empty") "0" else "100", withObject = false) })
                    .auditRemoteObjects(listOf(listed("empty", size = 0), listed("full", size = 100)))
            assertEquals(listOf(RemoteObjectStatus.HEALTHY, RemoteObjectStatus.MISSING), report.objects.map { it.status })
        }

    @Test
    fun `an object with more shards than one mirror query returns is unverified, not truncated`() =
        runTest {
            val report =
                auditor(
                    fileMeta = { meta(it, size = "400") },
                    bridgeInfo = { _, fileId -> info(fileId.removePrefix("bridge-"), size = 400, shards = 4) },
                    // The bridge answers at most the query limit of pointers; the 4th shard is not absent, just not asked for.
                    mirrors = { _, _ -> (0 until InternxtApiService.BRIDGE_MIRROR_LIMIT).map { mirror(it, 100) } },
                ).auditRemoteObjects(listOf(listed("a", size = 400)))
            val record = report.objects.single()
            assertEquals(RemoteObjectStatus.UNVERIFIED, record.status)
            assertNotNull(record.detail)
        }

    @Test
    fun `a multi-shard object within the mirror query limit is checked per shard`() =
        runTest {
            val healthy =
                auditor(
                    fileMeta = { meta(it, size = "200") },
                    bridgeInfo = { _, fileId -> info(fileId.removePrefix("bridge-"), size = 200, shards = 2) },
                    mirrors = { _, _ -> listOf(mirror(0, 100), mirror(1, 100)) },
                ).auditRemoteObjects(listOf(listed("a", size = 200)))
            val missingShard =
                auditor(
                    fileMeta = { meta(it, size = "200") },
                    bridgeInfo = { _, fileId -> info(fileId.removePrefix("bridge-"), size = 200, shards = 2) },
                    mirrors = { _, _ -> listOf(mirror(0, 100)) },
                ).auditRemoteObjects(listOf(listed("a", size = 200)))
            assertEquals(RemoteObjectStatus.HEALTHY, healthy.objects.single().status)
            assertEquals(RemoteObjectStatus.TRUNCATED, missingShard.objects.single().status)
        }

    @Test
    fun `an unhealthy mirror does not count as an available shard`() =
        runTest {
            val report = auditor(mirrors = { _, _ -> listOf(mirror(0, 100, healthy = false)) }).auditRemoteObjects(listOf(listed("a")))
            assertTrue(report.objects.single().status != RemoteObjectStatus.HEALTHY)
        }
}
