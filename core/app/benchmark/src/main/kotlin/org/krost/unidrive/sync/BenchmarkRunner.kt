package org.krost.unidrive.sync

import kotlinx.coroutines.delay
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.ProviderException
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

class BenchmarkRunner(
    private val provider: CloudProvider,
    private val db: BenchmarkDatabase,
    private val providerId: String,
    private val providerType: String,
    private val protocol: String,
    private val endpoint: String? = null,
    private val ipStack: String = "auto",
    private val clientLocation: String? = null,
    private val clientNetwork: String? = null,
    private val unidriveVersion: String,
    private val iterations: Int = DEFAULT_ITERATIONS,
    private val fileSizes: List<Long> = DEFAULT_SIZES,
    private val keepFiles: Boolean = false,
    private val delayMs: Long = 200,
) {

    companion object {
        const val DEFAULT_ITERATIONS = 5
        const val BENCHMARK_FOLDER = "/unidrive-benchmark"
        val DEFAULT_SIZES = listOf(1024L, 102400L, 1048576L, 10485760L, 104857600L)

        fun generateTestFile(dir: Path, size: Long): Path {
            val path = dir.resolve("bench-$size.bin")
            val rng = Random(size)
            val buf = ByteArray(minOf(size, 65536).toInt())
            Files.newOutputStream(path).use { out ->
                var remaining = size
                while (remaining > 0) {
                    val chunk = minOf(remaining, buf.size.toLong()).toInt()
                    rng.nextBytes(buf)
                    out.write(buf, 0, chunk)
                    remaining -= chunk
                }
            }
            return path
        }

        fun parseSizes(input: String): List<Long> {
            return input.split(',').map { token ->
                val t = token.trim().uppercase()
                when {
                    t.endsWith("GB") -> t.dropLast(2).toLong() * 1024L * 1024L * 1024L
                    t.endsWith("MB") -> t.dropLast(2).toLong() * 1024L * 1024L
                    t.endsWith("KB") -> t.dropLast(2).toLong() * 1024L
                    else -> t.toLong()
                }
            }
        }
    }

    private fun resolveEndpoint(endpoint: String?): String? {
        if (endpoint == null) return null
        return try {
            val host = java.net.URI(endpoint).host ?: endpoint
            java.net.InetAddress.getByName(host).hostAddress
        } catch (_: Exception) { null }
    }

    suspend fun run(onProgress: (String) -> Unit): BenchmarkRun {
        val runId = UUID.randomUUID().toString()
        val startedAt = Instant.now().toString()
        val fileSizesStr = fileSizes.joinToString(",", "[", "]")

        val resolvedIpBefore = resolveEndpoint(endpoint)

        val initialRun = BenchmarkRun(
            runId = runId,
            startedAt = startedAt,
            finishedAt = null,
            providerId = providerId,
            providerType = providerType,
            protocol = protocol,
            endpoint = endpoint,
            ipStack = ipStack,
            resolvedIp = resolvedIpBefore,
            clientLocation = clientLocation,
            clientNetwork = clientNetwork,
            unidriveVersion = unidriveVersion,
            iterations = iterations,
            fileSizes = fileSizesStr,
            throttled = false,
        )
        db.insertRun(initialRun)

        val tmpDir = Files.createTempDirectory("unidrive-bench-$runId")
        var anyThrottled = false

        try {
            // Pre-flight: create benchmark folder
            onProgress("Creating benchmark folder $BENCHMARK_FOLDER")
            val mkdirPoint = measureOperation("mkdir", 0L, 0) {
                provider.createFolder(BENCHMARK_FOLDER)
            }
            db.insertPoint(mkdirPoint.copy(runId = runId))

            // Generate local test files
            val localFiles = fileSizes.associateWith { size ->
                generateTestFile(tmpDir, size)
            }

            for (size in fileSizes) {
                val localFile = localFiles.getValue(size)
                val remotePath = "$BENCHMARK_FOLDER/bench-$size.bin"
                var consecutiveThrottles = 0

                for (iter in 1..iterations) {
                    if (consecutiveThrottles >= 3) {
                        onProgress("Skipping remaining iterations for size $size (3 consecutive 429s)")
                        break
                    }

                    onProgress("[$iter/$iterations] upload size=$size")
                    val uploadPoint = measureOperation("upload", size, iter) {
                        provider.upload(localFile, remotePath)
                    }
                    val uploadResult = uploadPoint.copy(
                        runId = runId,
                        throughputBps = if (uploadPoint.status == "ok" && uploadPoint.durationMs > 0)
                            size * 1000L / uploadPoint.durationMs else uploadPoint.throughputBps,
                    )
                    db.insertPoint(uploadResult)
                    if (uploadResult.status == "throttled") {
                        anyThrottled = true
                        consecutiveThrottles++
                        delay(5000)
                        continue
                    }
                    consecutiveThrottles = 0
                    delay(delayMs)

                    // TTFB: only measure on smallest file size (data transfer is negligible,
                    // duration ≈ network round-trip + server processing). Skip for larger sizes
                    // to avoid doubling bandwidth and producing redundant data.
                    onProgress("[$iter/$iterations] ttfb size=$size")
                    val ttfbDest = tmpDir.resolve("ttfb-$size-$iter.bin")
                    val ttfbPoint = if (size == fileSizes.min()) {
                        measureOperation("ttfb", size, iter) {
                            provider.download(remotePath, ttfbDest)
                        }
                    } else {
                        // For larger sizes, record a ttfb point with duration=0 and status=skip
                        BenchmarkPoint(runId = runId, iteration = iter, operation = "ttfb",
                            fileSize = size, durationMs = 0, throughputBps = null,
                            status = "skip", errorCode = null, errorMessage = "ttfb only measured on smallest size",
                            ipStack = ipStack, timestamp = java.time.Instant.now().toString())
                    }
                    val ttfbResult = ttfbPoint.copy(
                        runId = runId,
                        throughputBps = null, // TTFB is latency, not throughput
                    )
                    db.insertPoint(ttfbResult)
                    if (ttfbResult.status == "throttled") {
                        anyThrottled = true
                        consecutiveThrottles++
                        delay(5000)
                        continue
                    }
                    consecutiveThrottles = 0
                    delay(delayMs)

                    onProgress("[$iter/$iterations] download size=$size")
                    val dlDest = tmpDir.resolve("dl-$size-$iter.bin")
                    val dlPoint = measureOperation("download", size, iter) {
                        provider.download(remotePath, dlDest)
                    }
                    val dlResult = dlPoint.copy(
                        runId = runId,
                        throughputBps = if (dlPoint.status == "ok" && dlPoint.durationMs > 0)
                            size * 1000L / dlPoint.durationMs else dlPoint.throughputBps,
                    )
                    db.insertPoint(dlResult)
                    if (dlResult.status == "throttled") {
                        anyThrottled = true
                        consecutiveThrottles++
                        delay(5000)
                        continue
                    }
                    consecutiveThrottles = 0
                    delay(delayMs)

                    onProgress("[$iter/$iterations] delete size=$size")
                    val deletePoint = measureOperation("delete", size, iter) {
                        provider.delete(remotePath)
                    }
                    db.insertPoint(deletePoint.copy(runId = runId))
                    if (deletePoint.status == "throttled") {
                        anyThrottled = true
                        consecutiveThrottles++
                        delay(5000)
                        continue
                    }
                    consecutiveThrottles = 0
                    delay(delayMs)
                }
            }
        } finally {
            // Cleanup remote
            if (!keepFiles) {
                try { provider.delete(BENCHMARK_FOLDER) } catch (_: Exception) {}
            }

            // Cleanup local temp files
            tmpDir.toFile().deleteRecursively()
        }

        val finishedAt = Instant.now().toString()
        val resolvedIpAfter = resolveEndpoint(endpoint)
        if (resolvedIpBefore != null && resolvedIpAfter != null && resolvedIpBefore != resolvedIpAfter) {
            onProgress("Warning: endpoint IP changed mid-run ($resolvedIpBefore -> $resolvedIpAfter)")
        }
        db.finishRun(runId, finishedAt, anyThrottled)

        return db.getRun(runId)!!
    }

    private suspend fun measureOperation(
        operation: String,
        fileSize: Long,
        iteration: Int,
        block: suspend () -> Any?,
    ): BenchmarkPoint {
        val timestamp = Instant.now().toString()
        val start = System.currentTimeMillis()
        return try {
            block()
            val durationMs = System.currentTimeMillis() - start
            BenchmarkPoint(
                runId = "",
                iteration = iteration,
                operation = operation,
                fileSize = fileSize,
                durationMs = durationMs,
                throughputBps = null,
                status = "ok",
                errorCode = null,
                errorMessage = null,
                ipStack = ipStack,
                timestamp = timestamp,
            )
        } catch (e: ProviderException) {
            val durationMs = System.currentTimeMillis() - start
            val statusCode = Regex("""(?:status|code)[=: ]+(\d{3})""")
                .find(e.message ?: "")
                ?.groupValues?.get(1)?.toIntOrNull()
            val status = if (statusCode == 429) "throttled" else "error"
            BenchmarkPoint(
                runId = "",
                iteration = iteration,
                operation = operation,
                fileSize = fileSize,
                durationMs = durationMs,
                throughputBps = null,
                status = status,
                errorCode = statusCode,
                errorMessage = e.message,
                ipStack = ipStack,
                timestamp = timestamp,
            )
        } catch (e: SocketTimeoutException) {
            val durationMs = System.currentTimeMillis() - start
            BenchmarkPoint(
                runId = "",
                iteration = iteration,
                operation = operation,
                fileSize = fileSize,
                durationMs = durationMs,
                throughputBps = null,
                status = "timeout",
                errorCode = null,
                errorMessage = e.message,
                ipStack = ipStack,
                timestamp = timestamp,
            )
        } catch (e: Exception) {
            val durationMs = System.currentTimeMillis() - start
            BenchmarkPoint(
                runId = "",
                iteration = iteration,
                operation = operation,
                fileSize = fileSize,
                durationMs = durationMs,
                throughputBps = null,
                status = "error",
                errorCode = null,
                errorMessage = e.message,
                ipStack = ipStack,
                timestamp = timestamp,
            )
        }
    }
}
