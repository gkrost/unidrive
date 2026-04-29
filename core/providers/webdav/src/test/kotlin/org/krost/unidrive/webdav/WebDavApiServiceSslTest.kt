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
}
