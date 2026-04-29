package org.krost.unidrive.webdav

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.QuotaInfo
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import kotlin.test.*

class WebDavProviderTest {
    private fun provider(
        baseUrl: String = "https://dav.example.com/webdav",
        username: String = "alice",
        password: String = "secret",
    ) = WebDavProvider(
        WebDavConfig(
            baseUrl = baseUrl,
            username = username,
            password = password,
            tokenPath = Files.createTempDirectory("webdav-provider-test"),
        ),
    )

    // --- identity ---

    @Test
    fun `id is webdav`() {
        assertEquals("webdav", provider().id)
    }

    @Test
    fun `displayName is WebDAV`() {
        assertEquals("WebDAV", provider().displayName)
    }

    // --- canAuthenticate ---

    @Test
    fun `canAuthenticate true when all config fields present`() {
        assertTrue(provider().canAuthenticate)
    }

    @Test
    fun `canAuthenticate false when baseUrl blank`() {
        assertFalse(provider(baseUrl = "").canAuthenticate)
    }

    @Test
    fun `canAuthenticate false when username blank`() {
        assertFalse(provider(username = "").canAuthenticate)
    }

    @Test
    fun `canAuthenticate false when password blank`() {
        assertFalse(provider(password = "").canAuthenticate)
    }

    @Test
    fun `canAuthenticate false when all config fields blank`() {
        assertFalse(provider(baseUrl = "", username = "", password = "").canAuthenticate)
    }

    // --- isAuthenticated ---

    @Test
    fun `isAuthenticated starts as false`() {
        assertFalse(provider().isAuthenticated)
    }

    @Test
    fun `logout sets isAuthenticated to false`() =
        runTest {
            val p = provider()
            assertFalse(p.isAuthenticated)
            p.logout()
            assertFalse(p.isAuthenticated)
        }

    // --- quota ---

    @Test
    fun `quota returns zeros when not authenticated`() =
        runTest {
            val p = provider()
            // isAuthenticated is false — quota() returns zeros without a network call
            val q = p.quota()
            assertEquals(0L, q.total)
            assertEquals(0L, q.used)
            assertEquals(0L, q.remaining)
        }

    // -- UD-291: quota silent-failure regression -------------------------------
    //
    // Pre-fix: `catch (_: Exception) { QuotaInfo(0,0,0) }` swallowed every
    // failure (including CancellationException) without a log line. A network
    // failure to the quota PROPFIND endpoint was indistinguishable from
    // "server doesn't expose quota properties" — RelocateCommand's
    // `targetQuota.total > 0` guard skipped silently in both cases.

    /**
     * Test helper: a [WebDavApiService] subclass where `quotaPropfind` and
     * `propfind` can be configured to throw any [Throwable]. `propfind` is
     * what `WebDavProvider.authenticate()` calls to flip
     * `isAuthenticated = true`; the no-op default lets us reach `quota()`
     * with an authenticated provider without an HTTP server.
     */
    private class TestableApiService(
        config: WebDavConfig,
        private val quotaThrowable: Throwable? = null,
    ) : WebDavApiService(config) {
        override suspend fun propfind(remotePath: String): List<WebDavEntry> = emptyList()

        override suspend fun quotaPropfind(): QuotaInfo? {
            quotaThrowable?.let { throw it }
            return null
        }
    }

    private fun captureLog(): ListAppender<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(WebDavProvider::class.java) as Logger
        val appender =
            ListAppender<ILoggingEvent>().apply {
                start()
            }
        logger.addAppender(appender)
        return appender
    }

    private fun detachLog(appender: ListAppender<ILoggingEvent>) {
        val logger = LoggerFactory.getLogger(WebDavProvider::class.java) as Logger
        logger.detachAppender(appender)
    }

    @Test
    fun `UD-291 - quota logs a WARN with class + throwable on api failure`() =
        runTest {
            val cfg =
                WebDavConfig(
                    baseUrl = "https://dav.example.com/webdav",
                    username = "alice",
                    password = "secret",
                    tokenPath = Files.createTempDirectory("webdav-quota-fail-test"),
                )
            val api =
                TestableApiService(
                    cfg,
                    quotaThrowable = IOException("simulated network failure"),
                )
            val p = WebDavProvider(cfg, api)
            p.authenticate()

            val appender = captureLog()
            try {
                val q = p.quota()
                // Pre-fix and post-fix both return the zero sentinel (preserved
                // for API compatibility — every caller already treats total==0
                // as "unknown"). The contract change is the log line.
                assertEquals(0L, q.total)
                assertEquals(0L, q.used)
                assertEquals(0L, q.remaining)
            } finally {
                detachLog(appender)
            }

            val warns = appender.list.filter { it.level == Level.WARN }
            assertTrue(
                warns.any { ev ->
                    val msg = ev.formattedMessage
                    msg.contains("WebDAV quota PROPFIND failed") &&
                        msg.contains("IOException") &&
                        msg.contains("simulated network failure")
                },
                "expected WARN line with class + message; captured WARNs: ${warns.map { it.formattedMessage }}",
            )
            // Throwable is attached to the SLF4J event so the stack reaches
            // unidrive.log (per the UD-286 "log {} {} {}, e" pattern).
            assertTrue(
                warns.any { it.throwableProxy != null && it.throwableProxy.className.contains("IOException") },
                "expected the IOException to be attached to the log event",
            )
        }

    @Test
    fun `UD-291 - quota propagates CancellationException instead of absorbing it`() =
        runTest {
            val cfg =
                WebDavConfig(
                    baseUrl = "https://dav.example.com/webdav",
                    username = "alice",
                    password = "secret",
                    tokenPath = Files.createTempDirectory("webdav-quota-cancel-test"),
                )
            val api =
                TestableApiService(
                    cfg,
                    quotaThrowable = CancellationException("simulated cancellation"),
                )
            val p = WebDavProvider(cfg, api)
            p.authenticate()

            // Pre-UD-291: the bare `catch (_: Exception)` absorbed
            // CancellationException and returned QuotaInfo(0,0,0), breaking
            // structured concurrency. Post-fix: cancellation propagates.
            assertFailsWith<CancellationException> {
                p.quota()
            }
        }

    // --- close ---

    @Test
    fun `close does not throw`() {
        val p = provider()
        p.close()
        // no assertion needed — just verify no exception
    }

    @Test
    fun `close is idempotent`() {
        val p = provider()
        p.close()
        p.close()
    }
}
