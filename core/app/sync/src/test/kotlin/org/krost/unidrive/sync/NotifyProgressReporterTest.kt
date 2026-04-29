package org.krost.unidrive.sync

import kotlin.test.*

class NotifyProgressReporterTest {
    private val noOpExecutor: (List<String>) -> Unit = {}

    @Test
    fun `onSyncComplete with zero changes does not crash`() {
        val reporter = NotifyProgressReporter("test", commandExecutor = noOpExecutor)
        reporter.onSyncComplete(0, 0, 0, 100)
    }

    @Test
    fun `onSyncComplete with changes does not crash even without notify-send`() {
        val reporter = NotifyProgressReporter("test", commandExecutor = noOpExecutor)
        reporter.onSyncComplete(5, 3, 1, 2000)
    }

    @Test
    fun `onWarning does not crash even without notify-send`() {
        val reporter = NotifyProgressReporter("test", commandExecutor = noOpExecutor)
        reporter.onWarning("Test warning message")
    }
}
