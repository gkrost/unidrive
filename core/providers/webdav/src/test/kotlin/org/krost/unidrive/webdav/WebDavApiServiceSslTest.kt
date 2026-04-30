package org.krost.unidrive.webdav

import kotlin.test.*

/**
 * UD-104: verifies both SSL branches of [WebDavApiService] construct successfully.
 *
 * Originally the backlog item claimed the `trust_all_certs` flag was declared but
 * unwired. Re-reading [WebDavApiService] line 53 showed this was false — the flag
 * selects the Ktor Java engine with a permissive X509TrustManager. These tests
 * lock in that behavior so a future change can't silently drop the wiring.
 */
class WebDavApiServiceSslTest {
    @Test
    fun `service with trustAllCerts=true constructs without throwing`() {
        // Exercises the Java engine + permissive X509TrustManager init path.
        val cfg =
            WebDavConfig(
                baseUrl = "https://self-signed.invalid/dav",
                username = "u",
                password = "p",
                trustAllCerts = true,
            )
        WebDavApiService(cfg).use { svc ->
            assertNotNull(svc)
        }
    }

    @Test
    fun `service with trustAllCerts=false constructs on the default CIO engine`() {
        val cfg =
            WebDavConfig(
                baseUrl = "https://dav.example.com/",
                username = "u",
                password = "p",
                trustAllCerts = false,
            )
        WebDavApiService(cfg).use { svc ->
            assertNotNull(svc)
        }
    }

    // -- UD-807: engine-selection branch coverage ------------------------------
    //
    // Pre-fix the two SSL tests above only verified construction succeeded —
    // they couldn't catch a regression where `trust_all_certs = true` quietly
    // started routing through CIO (which fails the TLS handshake on Synology
    // DSM 7.x with a fatal ProtocolVersion alert) instead of Apache5. The full
    // docker-compose self-signed Nginx harness in the original ticket is
    // deferred — these unit assertions catch the engine-selection regression
    // for free against the in-process Ktor httpClient instance.

    @Test
    fun `UD-807 - trustAllCerts=true selects Apache5 engine`() {
        val cfg =
            WebDavConfig(
                baseUrl = "https://self-signed.invalid/dav",
                username = "u",
                password = "p",
                trustAllCerts = true,
            )
        WebDavApiService(cfg).use { svc ->
            val engineFqn = svc.httpClient.engine.javaClass.canonicalName ?: ""
            assertTrue(
                engineFqn.contains("apache5", ignoreCase = true),
                "trust_all_certs=true must route through the Apache5 engine; got '$engineFqn'",
            )
        }
    }

    @Test
    fun `UD-807 - trustAllCerts=false selects CIO engine`() {
        val cfg =
            WebDavConfig(
                baseUrl = "https://dav.example.com/",
                username = "u",
                password = "p",
                trustAllCerts = false,
            )
        WebDavApiService(cfg).use { svc ->
            val engineFqn = svc.httpClient.engine.javaClass.canonicalName ?: ""
            assertTrue(
                engineFqn.contains("cio", ignoreCase = true),
                "trust_all_certs=false must route through the CIO engine; got '$engineFqn'",
            )
        }
    }
}
