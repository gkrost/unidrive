package org.krost.unidrive.sync

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * UD-282: regression tests for TOML strict-config diagnostics. Pre-fix,
 * ktoml's `ignoreUnknownNames = true` silently dropped sections like
 * `[profiles.X]` (the most common typo for `[providers.X]`); the user
 * saw `Unknown profile: X` minutes later from the resolver, never at
 * config-load time. The validateTomlSections + parseRaw warnings give
 * a loud signal at parse time.
 */
class SyncConfigStrictTest {
    // -- validateTomlSections (pure function) ---------------------------------

    @Test
    fun `validateTomlSections returns empty for clean config`() {
        val toml =
            """
            [general]
            default_profile = "home"

            [providers.home]
            type = "localfs"

            [providers.work]
            type = "onedrive"
            """.trimIndent()
        assertEquals(emptyList(), validateTomlSections(toml))
    }

    @Test
    fun `validateTomlSections detects 'profiles' typo and suggests 'providers'`() {
        val toml =
            """
            [profiles.unidrive-localfs-19notte78]
            type = "localfs"
            """.trimIndent()
        val unknowns = validateTomlSections(toml)
        assertEquals(1, unknowns.size)
        val u = unknowns[0]
        assertEquals("profiles.unidrive-localfs-19notte78", u.section)
        assertEquals(1, u.lineNumber)
        assertEquals("providers.unidrive-localfs-19notte78", u.suggestion)
    }

    @Test
    fun `validateTomlSections handles other common typos (profile, provider)`() {
        val toml =
            """
            [profile.foo]
            type = "localfs"

            [provider.bar]
            type = "onedrive"
            """.trimIndent()
        val unknowns = validateTomlSections(toml)
        assertEquals(2, unknowns.size)
        assertEquals("providers.foo", unknowns[0].suggestion)
        assertEquals("providers.bar", unknowns[1].suggestion)
    }

    @Test
    fun `validateTomlSections detects 'globals' typo and suggests 'general'`() {
        val toml =
            """
            [globals]
            default_profile = "home"
            """.trimIndent()
        val unknowns = validateTomlSections(toml)
        assertEquals(1, unknowns.size)
        assertEquals("general", unknowns[0].suggestion)
    }

    @Test
    fun `validateTomlSections returns null suggestion for completely-unrelated section`() {
        val toml =
            """
            [xyzzyfrobnicate]
            random = "data"
            """.trimIndent()
        val unknowns = validateTomlSections(toml)
        assertEquals(1, unknowns.size)
        assertNull(unknowns[0].suggestion, "Levenshtein > 3 against any known should return null")
    }

    @Test
    fun `validateTomlSections records correct line numbers across multi-line files`() {
        val toml =
            """
            # This is line 1
            [general]
            default_profile = "home"

            # comment at line 5
            [profiles.foo]
            type = "localfs"
            """.trimIndent()
        val unknowns = validateTomlSections(toml)
        assertEquals(1, unknowns.size)
        assertEquals(6, unknowns[0].lineNumber, "section header was on line 6")
    }

    @Test
    fun `validateTomlSections accepts nested provider tables (providers_X_pin_patterns)`() {
        val toml =
            """
            [providers.home]
            type = "localfs"

            [providers.home.pin_patterns]
            include = ["**/*.txt"]
            """.trimIndent()
        // Both [providers.home] and [providers.home.pin_patterns] are valid —
        // top-level prefix is "providers" in both cases.
        assertEquals(emptyList(), validateTomlSections(toml))
    }

    @Test
    fun `validateTomlSections ignores commented-out section headers`() {
        val toml =
            """
            [general]

            # [profiles.foo]
            # type = "localfs"

            [providers.real]
            type = "onedrive"
            """.trimIndent()
        // The `# [profiles.foo]` line is a comment — must NOT trigger the warning.
        assertEquals(emptyList(), validateTomlSections(toml))
    }

    // -- parseRaw integration: warning surface --------------------------------

    private fun captureSyncConfigLog(): ListAppender<ILoggingEvent> {
        val logger = LoggerFactory.getLogger("org.krost.unidrive.sync.SyncConfig") as Logger
        val appender =
            ListAppender<ILoggingEvent>().apply {
                start()
            }
        logger.addAppender(appender)
        return appender
    }

    private fun detachSyncConfigLog(appender: ListAppender<ILoggingEvent>) {
        val logger = LoggerFactory.getLogger("org.krost.unidrive.sync.SyncConfig") as Logger
        logger.detachAppender(appender)
    }

    @Test
    fun `parseRaw with profiles typo emits a WARN line with did-you-mean`() {
        val toml =
            """
            [providers.real]
            type = "onedrive"

            [profiles.unidrive-localfs-19notte78]
            type = "localfs"
            """.trimIndent()
        val appender = captureSyncConfigLog()
        try {
            val raw = SyncConfig.parseRaw(toml)
            // The good provider parses; the typo'd one is dropped (existing
            // ktoml behaviour). The contract change is the warning, not the
            // semantic change.
            assertEquals(1, raw.providers.size)
            assertNotNull(raw.providers["real"])
        } finally {
            detachSyncConfigLog(appender)
        }

        val warns = appender.list.filter { it.level == Level.WARN }
        assertTrue(
            warns.any { ev ->
                val msg = ev.formattedMessage
                msg.contains("ignored unknown section") &&
                    msg.contains("[profiles.unidrive-localfs-19notte78]") &&
                    msg.contains("did you mean") &&
                    msg.contains("[providers.unidrive-localfs-19notte78]")
            },
            "expected WARN with section + suggestion; saw: ${warns.map { it.formattedMessage }}",
        )
    }

    @Test
    fun `parseRaw with clean config emits no WARN lines`() {
        val toml =
            """
            [general]
            default_profile = "home"

            [providers.home]
            type = "localfs"
            """.trimIndent()
        val appender = captureSyncConfigLog()
        try {
            SyncConfig.parseRaw(toml)
        } finally {
            detachSyncConfigLog(appender)
        }
        val warns =
            appender.list.filter {
                it.level == Level.WARN && it.formattedMessage.contains("ignored unknown section")
            }
        assertTrue(warns.isEmpty(), "no warnings expected for clean config; saw: ${warns.map { it.formattedMessage }}")
    }
}
