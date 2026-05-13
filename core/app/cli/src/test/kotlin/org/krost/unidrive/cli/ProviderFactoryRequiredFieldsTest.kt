package org.krost.unidrive.cli

import org.krost.unidrive.ConfigurationException
import org.krost.unidrive.ProviderFactory
import org.krost.unidrive.ProviderRegistry
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.fail

/**
 * UD-818 — parametric replacement for the duplicated "create throws when
 * required field is missing/blank" tests previously inlined in each provider
 * module's *ProviderFactoryTest. Drives the same assertion via the
 * [ProviderFactory.credentialPrompts] SPI so adding a 9th provider with
 * required prompts gets free coverage.
 *
 * Invariant under test: for every prompt declared `required = true` by a
 * factory, calling [ProviderFactory.create] with that key absent OR blank
 * MUST throw [ConfigurationException]. The factory's other required keys
 * are filled with stub values from [fullProps] so the assertion isolates
 * one prompt at a time.
 *
 * Scope notes:
 * - Providers that do not implement [ProviderFactory.credentialPrompts]
 *   (default empty list) contribute no cases here. Their per-module factory
 *   tests still own the required-field coverage. Today: localfs, rclone,
 *   onedrive, internxt. When any of those adopts the prompts SPI, this
 *   test picks them up automatically and the per-module duplicates can be
 *   deleted.
 * - The [fullProps] table is hard-coded per provider id. A factory that
 *   declares required prompts without a matching [fullProps] entry will
 *   fail fast, which is the intended signal for "new provider, no test
 *   data wired up."
 *
 * Framework: this module uses JUnit 4 (see `core/app/cli/build.gradle.kts`
 * `useJUnit()`). JUnit 5's `@TestFactory` would give per-case reporting but
 * isn't available here. Failures collect into one assertion that names every
 * failing (provider, key, mode) tuple so a regression points at all
 * affected sites at once instead of stopping at the first.
 */
class ProviderFactoryRequiredFieldsTest {
    /**
     * Stub values that satisfy each provider's complete required-field set.
     * Values do not need to be live credentials — `create()` only validates
     * non-blank presence, not authenticity.
     */
    private val fullProps: Map<String, Map<String, String>> =
        mapOf(
            "s3" to
                mapOf(
                    "bucket" to "my-bucket",
                    "access_key_id" to "AKIAIOSFODNN7EXAMPLE",
                    "secret_access_key" to "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
                ),
            "sftp" to
                mapOf(
                    "host" to "sftp.example.com",
                ),
            "webdav" to
                mapOf(
                    "url" to "https://webdav.example.com/remote.php/dav",
                    "user" to "alice",
                    "password" to "hunter2",
                ),
        )

    @Test
    fun `every required prompt key triggers ConfigurationException when missing or blank`() {
        val failures = mutableListOf<String>()

        for (factory in ProviderRegistry.all()) {
            val requiredKeys =
                factory
                    .credentialPrompts()
                    .filter { it.required }
                    .map { it.key }

            if (requiredKeys.isEmpty()) continue

            val full =
                fullProps[factory.id]
                    ?: run {
                        failures +=
                            "[${factory.id}] declares required credentialPrompts " +
                            "(${requiredKeys.joinToString()}) but has no entry in " +
                            "ProviderFactoryRequiredFieldsTest.fullProps."
                        continue
                    }

            val missingFromStub = requiredKeys.filterNot { it in full.keys }
            if (missingFromStub.isNotEmpty()) {
                failures += "[${factory.id}] fullProps missing required keys: $missingFromStub"
                continue
            }

            for (key in requiredKeys) {
                checkThrows(factory, full, key, replacement = null, mode = "missing", failures)
                checkThrows(factory, full, key, replacement = "  ", mode = "blank", failures)
            }
        }

        if (failures.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("UD-818: required-field contract violated by:")
                    failures.forEach { appendLine("  - $it") }
                }.trimEnd(),
            )
        }
    }

    private fun checkThrows(
        factory: ProviderFactory,
        full: Map<String, String>,
        key: String,
        replacement: String?,
        mode: String,
        failures: MutableList<String>,
    ) {
        val tokenDir = Files.createTempDirectory("ud818-${factory.id}-")
        try {
            val props: Map<String, String?> =
                full.mapValues<String, String, String?> { it.value }.toMutableMap().also { it[key] = replacement }
            try {
                factory.create(props, tokenDir)
                failures += "[${factory.id}] create($key=$mode) did not throw ConfigurationException"
                return
            } catch (e: ConfigurationException) {
                // Strong invariant: the exception MUST name its origin provider and
                // mention the offending key, so operators can fix config-files
                // without re-running with a debugger. WebDAV's pre-UD-818 tests
                // asserted these explicitly; the contract is lifted here so every
                // prompt-driven provider gets the same guarantee.
                if (e.providerId != factory.id) {
                    failures +=
                        "[${factory.id}] create($key=$mode) threw ConfigurationException with " +
                        "providerId='${e.providerId}', expected '${factory.id}'"
                }
                if (!e.message.contains(key, ignoreCase = true)) {
                    failures +=
                        "[${factory.id}] create($key=$mode) ConfigurationException.message " +
                        "did not name the offending key '$key': ${e.message}"
                }
            } catch (t: Throwable) {
                failures +=
                    "[${factory.id}] create($key=$mode) threw " +
                    "${t::class.simpleName} (expected ConfigurationException): ${t.message}"
            }
        } finally {
            Files.deleteIfExists(tokenDir)
        }
    }
}
