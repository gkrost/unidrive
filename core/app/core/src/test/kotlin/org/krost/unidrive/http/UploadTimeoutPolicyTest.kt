package org.krost.unidrive.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * UD-337 / UD-277: pin the size-adaptive request-timeout shape. Originally
 * `WebDavTimeoutPolicyTest` in the webdav provider; lifted alongside the
 * helper itself so Internxt / OneDrive / HiDrive / S3 inherit the same
 * tested contract when they adopt the policy.
 */
class UploadTimeoutPolicyTest {
    private val policy = UploadTimeoutPolicy

    // UD-277 acceptance #2 — explicit fixture from the original WebDAV
    // ticket: fileSize = 2 GiB, minThroughput = 512 KiB/s → timeout ≥ 4096 s

    @Test
    fun `UD-277 - 2 GiB at 512 KiB-per-second yields at least 4096 seconds`() {
        val twoGiB = 2L * 1024 * 1024 * 1024
        val fiveTwelveKiBPerSec = 512L * 1024
        val timeoutMs = policy.computeRequestTimeoutMs(twoGiB, floorMs = 600_000L, fiveTwelveKiBPerSec)
        assertTrue(
            timeoutMs >= 4_096_000L,
            "expected >= 4 096 000 ms (4096 s); was $timeoutMs",
        )
    }

    @Test
    fun `floor wins for small files`() {
        // 1 MiB at 512 KiB/s = 2 s — way under 600 s floor.
        val oneMiB = 1L * 1024 * 1024
        val fiveTwelveKiBPerSec = 512L * 1024
        val timeoutMs = policy.computeRequestTimeoutMs(oneMiB, floorMs = 600_000L, fiveTwelveKiBPerSec)
        assertEquals(600_000L, timeoutMs, "small file should keep the floor")
    }

    @Test
    fun `size-adaptive wins for large files`() {
        // 1 GiB at 100 KiB/s — ceiling-divide makes the math reproducible:
        // 1 073 741 824 / 102 400 = 10 485 s remainder 77 824 → 10 486 s.
        val oneGiB = 1L * 1024 * 1024 * 1024
        val hundredKiBPerSec = 100L * 1024
        val timeoutMs = policy.computeRequestTimeoutMs(oneGiB, floorMs = 600_000L, hundredKiBPerSec)
        assertEquals(10_486_000L, timeoutMs)
    }

    @Test
    fun `zero or negative minThroughput opts out to Long-MAX_VALUE`() {
        // UD-285 escape hatch: setting min throughput to 0 reverts to
        // unbounded behaviour for users who explicitly want it.
        val oneTiB = 1L * 1024 * 1024 * 1024 * 1024
        assertEquals(
            Long.MAX_VALUE,
            policy.computeRequestTimeoutMs(oneTiB, floorMs = 600_000L, minThroughputBytesPerSecond = 0L),
        )
        assertEquals(
            Long.MAX_VALUE,
            policy.computeRequestTimeoutMs(oneTiB, floorMs = 600_000L, minThroughputBytesPerSecond = -1L),
        )
    }

    @Test
    fun `zero or negative fileSize returns the floor`() {
        // Edge case: PUT body is sometimes computed before file size is
        // known, or the file is truncated to zero. Don't divide-by-zero;
        // just hand back the floor.
        val timeoutMs = policy.computeRequestTimeoutMs(0L, floorMs = 600_000L, minThroughputBytesPerSecond = 100_000L)
        assertEquals(600_000L, timeoutMs)
        val timeoutMsNeg = policy.computeRequestTimeoutMs(-1L, floorMs = 600_000L, minThroughputBytesPerSecond = 100_000L)
        assertEquals(600_000L, timeoutMsNeg)
    }

    @Test
    fun `ceiling rounding catches sub-second remainder`() {
        // 524289 B / 524288 B-per-s = 1.0000019... s. Without ceiling, the
        // Long division returns 1 → 1000 ms — the last byte arrives
        // milliseconds late. With ceiling: 2 s → 2000 ms.
        // Floor must be < 2000 to expose the rounding behaviour.
        val timeoutMs =
            policy.computeRequestTimeoutMs(
                fileSize = 524_289L,
                floorMs = 1_000L,
                minThroughputBytesPerSecond = 524_288L,
            )
        assertEquals(2_000L, timeoutMs, "expected ceiling to bump 1 s → 2 s")
    }

    @Test
    fun `huge file at minimal throughput clamps instead of overflowing`() {
        // 4 EiB at 1 byte-per-second = 4.6e18 seconds; * 1000 ms-per-s
        // would overflow Long (max ~9.2e18). Saturating multiplication
        // clamps to Long.MAX_VALUE rather than rolling over to negative.
        val fourEiB = 4L * 1024 * 1024 * 1024 * 1024 * 1024 * 1024
        val timeoutMs = policy.computeRequestTimeoutMs(fourEiB, floorMs = 600_000L, minThroughputBytesPerSecond = 1L)
        assertEquals(Long.MAX_VALUE, timeoutMs, "no overflow; clamps to MAX_VALUE")
    }

    // UD-337 — verify default arguments work as documented.

    @Test
    fun `UD-337 default arguments use 50 KiB-per-second floor and 600 s floor`() {
        // Small file: defaults yield the floor.
        val oneMiB = 1L * 1024 * 1024
        assertEquals(600_000L, policy.computeRequestTimeoutMs(oneMiB))
        // 5 GiB at 50 KiB/s: 5 * 1024 * 1024 / 50 = 104 857.6 s → 104 858 s
        // (ceiling), * 1000 = 104_858_000 ms — well above the floor.
        val fiveGiB = 5L * 1024 * 1024 * 1024
        val expected = (fiveGiB / (50L * 1024) + 1) * 1000L
        assertEquals(expected, policy.computeRequestTimeoutMs(fiveGiB))
        assertTrue(expected > 600_000L, "5 GiB should exceed the floor with default throughput")
    }
}
