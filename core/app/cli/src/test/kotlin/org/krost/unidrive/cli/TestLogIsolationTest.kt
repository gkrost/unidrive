package org.krost.unidrive.cli

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.core.FileAppender
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Test JVMs must not append to the operator's runtime log. The shipped `logback.xml` (this module's
 * main resources) writes to `%LOCALAPPDATA%/unidrive/unidrive.log` or `~/.local/share/unidrive/`,
 * the file the deployed daemon logs to, so every test run buried the daemon's own anomalies under
 * test output. `logback-test.xml` in the test resources replaces it with a console-only config.
 */
class TestLogIsolationTest {
    @Test
    fun `the test logging config attaches no file appender`() {
        val context = LoggerFactory.getILoggerFactory() as LoggerContext
        val fileAppenders =
            context.loggerList
                .flatMap { it.iteratorForAppenders().asSequence().toList() }
                .filterIsInstance<FileAppender<*>>()
        assertTrue(
            fileAppenders.isEmpty(),
            "tests must not write to a log file; found ${fileAppenders.map { it.file }}",
        )
    }
}
