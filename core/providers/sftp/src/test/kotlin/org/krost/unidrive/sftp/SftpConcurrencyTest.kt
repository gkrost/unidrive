package org.krost.unidrive.sftp

import kotlinx.coroutines.sync.Semaphore
import kotlin.test.*

/**
 * Verifies that SFTP concurrency limiting is correctly wired:
 * - [SftpConfig.maxConcurrency] defaults to 4 (Synology-safe)
 * - [SftpApiService] creates a semaphore matching the configured limit
 * - [SftpProviderFactory] parses `max_concurrency` from TOML properties
 *
 * Invariant: at most [SftpConfig.maxConcurrency] SFTP subsystem channels are
 * open simultaneously. Servers like Synology DS418play reject parallel SSH
 * channel opens beyond ~10; the default of 4 prevents "open failed" errors.
 */
class SftpConcurrencyTest {
    @Test
    fun `SftpConfig maxConcurrency defaults to 4`() {
        val config = SftpConfig(host = "localhost")
        assertEquals(4, config.maxConcurrency)
    }

    @Test
    fun `SftpConfig maxConcurrency is configurable`() {
        val config = SftpConfig(host = "localhost", maxConcurrency = 1)
        assertEquals(1, config.maxConcurrency)
    }

    @Test
    fun `SftpApiService semaphore matches configured maxConcurrency`() {
        for (limit in listOf(1, 2, 4, 8)) {
            val config = SftpConfig(host = "localhost", maxConcurrency = limit)
            val service = SftpApiService(config)
            try {
                val semaphore =
                    service.javaClass
                        .getDeclaredField("concurrencySemaphore")
                        .apply { isAccessible = true }
                        .get(service) as Semaphore
                // Semaphore(n) allows n concurrent acquires before blocking.
                // Verify by trying to acquire exactly n permits without blocking.
                val acquired = (1..limit).count { semaphore.tryAcquire() }
                assertEquals(limit, acquired, "Semaphore should have $limit permits for maxConcurrency=$limit")
                // n+1 should fail
                assertFalse(semaphore.tryAcquire(), "Semaphore should block beyond $limit permits")
            } finally {
                service.close()
            }
        }
    }

    @Test
    fun `SftpProviderFactory parses max_concurrency from properties`() {
        val factory = SftpProviderFactory()

        // Explicit value
        val provider2 =
            factory.create(
                mapOf("host" to "example.com", "max_concurrency" to "2", "password" to "test"),
                SftpConfig.defaultTokenPath(),
            ) as SftpProvider
        assertEquals(2, provider2.config.maxConcurrency)

        // Missing → default 4
        val providerDefault =
            factory.create(
                mapOf("host" to "example.com", "password" to "test"),
                SftpConfig.defaultTokenPath(),
            ) as SftpProvider
        assertEquals(4, providerDefault.config.maxConcurrency)

        // Invalid → default 4
        val providerBad =
            factory.create(
                mapOf("host" to "example.com", "max_concurrency" to "notanumber", "password" to "test"),
                SftpConfig.defaultTokenPath(),
            ) as SftpProvider
        assertEquals(4, providerBad.config.maxConcurrency)

        // Zero or negative → clamped to 1
        val providerZero =
            factory.create(
                mapOf("host" to "example.com", "max_concurrency" to "0", "password" to "test"),
                SftpConfig.defaultTokenPath(),
            ) as SftpProvider
        assertEquals(1, providerZero.config.maxConcurrency)
    }
}
