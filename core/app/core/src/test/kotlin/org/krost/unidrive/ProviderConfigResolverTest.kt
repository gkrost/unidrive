package org.krost.unidrive

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProviderConfigResolverTest {
    @Test
    fun `mergeWithEnv preserves existing properties when no env vars match`() {
        val props = mapOf("host" to "example.com", "port" to "22")
        val envMappings = mapOf("UNIDRIVE_NONEXISTENT_VAR_XYZ" to "host")

        val result = ProviderConfigResolver.mergeWithEnv(props, envMappings)

        assertEquals("example.com", result["host"])
        assertEquals("22", result["port"])
    }

    @Test
    fun `mergeWithEnv overrides property when env var is set`() {
        // PATH is set on every platform we target (Linux, macOS, Windows).
        val props = mapOf("path_dir" to "/old/path")
        val envMappings = mapOf("PATH" to "path_dir")

        val result = ProviderConfigResolver.mergeWithEnv(props, envMappings)

        assertEquals(System.getenv("PATH"), result["path_dir"])
    }

    @Test
    fun `mergeWithEnv with empty maps returns empty`() {
        val result = ProviderConfigResolver.mergeWithEnv(emptyMap(), emptyMap())
        assertEquals(emptyMap(), result)
    }

    @Test
    fun `mergeWithEnv does not remove keys when env var is absent`() {
        val props = mapOf("key1" to "val1", "key2" to null)
        val envMappings = mapOf("UNIDRIVE_NONEXISTENT_VAR_XYZ" to "key1")

        val result = ProviderConfigResolver.mergeWithEnv(props, envMappings)

        assertEquals("val1", result["key1"])
        assertNull(result["key2"])
    }

    @Test
    fun `requireEnv returns value for existing env var`() {
        // PATH is set on every platform we target (Linux, macOS, Windows).
        val result = ProviderConfigResolver.requireEnv("PATH", "Usage: set PATH")
        assertEquals(System.getenv("PATH"), result)
    }
}
