package org.krost.unidrive.tracking

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.core.FileAppender
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * This module's tests have `:app:cli` on their classpath, and with it the shipped `logback.xml` that
 * appends to the operator's runtime log. `logback-test.xml` in the test resources keeps test runs
 * off that file.
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
