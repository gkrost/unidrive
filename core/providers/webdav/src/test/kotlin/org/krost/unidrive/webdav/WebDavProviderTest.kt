package org.krost.unidrive.webdav

import kotlinx.coroutines.test.runTest
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
