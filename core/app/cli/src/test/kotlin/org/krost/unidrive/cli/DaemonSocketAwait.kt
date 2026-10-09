package org.krost.unidrive.cli

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path

/**
 * #625: the shared daemon-startup wait for every test that binds a daemon socket. A cold or busy
 * runner gets 30 s, and when the caller has the daemon's [daemonJob] a start that ENDED without
 * binding fails at once instead of running out the clock (a real regression then names itself).
 * The poll exits the moment the socket exists — the budget is only a ceiling.
 */
internal suspend fun awaitDaemonSocket(
    socketPath: Path,
    daemonJob: Job? = null,
) {
    withTimeout(30_000) {
        while (!Files.exists(socketPath)) {
            if (daemonJob?.isCompleted == true) {
                error("the daemon start finished without binding its socket")
            }
            delay(50)
        }
    }
}
