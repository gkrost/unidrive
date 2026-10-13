package org.krost.unidrive.cli

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.core.ConsoleAppender
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Commands with a machine-readable contract (`--json`) print exactly one
 * document on stdout, so the shipped logging setup must keep log lines off it.
 */
class LogStreamTest {
    @Test
    fun `shipped logback config sends console log lines to stderr`() {
        val config = javaClass.classLoader.getResource("logback.xml")!!
        val logDir = Files.createTempDirectory("unidrive-logstream")
        val context = LoggerContext()
        try {
            context.putProperty("LOCALAPPDATA", logDir.toString())
            JoranConfigurator().apply { this.context = context }.doConfigure(config)

            val root = context.getLogger(Logger.ROOT_LOGGER_NAME)
            val console = root.getAppender("CONSOLE") as? ConsoleAppender<*>
            assertNotNull(console, "shipped logback.xml must keep a CONSOLE appender")
            assertEquals("System.err", console.target, "console log lines must not reach stdout")
        } finally {
            context.stop()
            logDir.toFile().deleteRecursively()
        }
    }
}
