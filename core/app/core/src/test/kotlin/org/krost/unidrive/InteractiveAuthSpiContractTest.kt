package org.krost.unidrive

import kotlinx.coroutines.runBlocking
import org.krost.unidrive.localfs.LocalFsProviderFactory
import java.nio.file.Files
import java.util.ServiceLoader
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * UD-014 cross-cutting invariant: every factory that declares
 * supportsInteractiveAuth() == true MUST override beginInteractiveAuth
 * (so the throwing default never reaches users). The complementary
 * invariant: a known non-OAuth factory MUST keep the throwing default.
 *
 * If this test is removed, a future provider could declare capability
 * support without an override and silently ship a misexecuting flow.
 *
 * Per CLAUDE.md "orthogonal invariant decomposition": one named test
 * per invariant.
 */
class InteractiveAuthSpiContractTest {
    private lateinit var tmpProfileDir: java.nio.file.Path

    @BeforeTest
    fun setUp() {
        tmpProfileDir = Files.createTempDirectory("ud-014-spi-")
    }

    @AfterTest
    fun tearDown() {
        tmpProfileDir.toFile().deleteRecursively()
    }

    @Test
    fun interactive_auth_capability_and_override_agree() {
        val factories = ServiceLoader.load(ProviderFactory::class.java).toList()
        assertTrue(factories.isNotEmpty(), "ServiceLoader returned no factories")

        for (factory in factories) {
            if (!factory.supportsInteractiveAuth()) continue
            try {
                runBlocking { factory.beginInteractiveAuth(tmpProfileDir) }
            } catch (e: UnsupportedOperationException) {
                fail(
                    "Factory '${factory.id}' declares supportsInteractiveAuth=true " +
                        "but did not override beginInteractiveAuth (throwing default reached): ${e.message}",
                )
            } catch (_: Throwable) {
                // Any other Throwable (network error, missing config, ...) is
                // tolerated — it proves the override exists. We catch Throwable
                // rather than Exception so a coroutine CancellationException
                // can't escape unfiltered.
            }
        }
    }

    @Test
    fun non_oauth_factory_uses_default_throwing_sentinels() {
        val localfs = LocalFsProviderFactory()
        assertFalse(
            localfs.supportsInteractiveAuth(),
            "LocalFsProviderFactory must not declare interactive-auth support",
        )

        try {
            runBlocking { localfs.beginInteractiveAuth(tmpProfileDir) }
            fail("LocalFsProviderFactory.beginInteractiveAuth should throw UnsupportedOperationException by default")
        } catch (e: UnsupportedOperationException) {
            assertTrue(e.message?.contains("no interactive auth flow") == true)
        }
    }
}
