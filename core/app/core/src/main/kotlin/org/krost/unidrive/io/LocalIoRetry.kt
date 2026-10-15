package org.krost.unidrive.io

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Retries a local file operation while another process holds the file open (#802): antivirus, the search
 * indexer or a backup tool locks a file for a second and the operation used to fail a whole poll cycle.
 * Only a sharing violation or an access denial on a file that still exists is retried — three attempts,
 * 100/300/900 ms; anything else (and a file that is gone: "locked" is not "gone") is rethrown at once.
 * When the attempts are used up the original failure is rethrown with the cause named. Windows only: the
 * other platforms have no sharing violations, so there it is a plain call.
 *
 * Never swallows a [CancellationException]: it is not a [FileSystemException] and passes through.
 */
private val RETRY_DELAYS_MS = longArrayOf(100, 300, 900)

private val WINDOWS = System.getProperty("os.name").lowercase().contains("windows")

/**
 * What the retry loop does with a [FileSystemException] on [attempt]: null means worth another try (a
 * lock that may clear, attempts left); otherwise the failure to give up with — the original, when the
 * operation did not fail on a lock at all, or [lockedUp] when the attempts are used up.
 */
private fun lockRetryVerdict(
    e: FileSystemException,
    attempt: Int,
): FileSystemException? =
    when {
        !isRetriableLock(e) -> e
        attempt >= RETRY_DELAYS_MS.size -> lockedUp(e)
        else -> null
    }

suspend fun <T> withLocalIoRetry(
    delayMs: suspend (Long) -> Unit = { delay(it) },
    windows: Boolean = WINDOWS,
    action: suspend () -> T,
): T {
    if (!windows) return action()
    var attempt = 0
    while (true) {
        try {
            return action()
        } catch (e: CancellationException) {
            throw e
        } catch (e: FileSystemException) {
            val giveUp = lockRetryVerdict(e, attempt)
            if (giveUp != null) throw giveUp
            delayMs(RETRY_DELAYS_MS[attempt])
            attempt++
        }
    }
}

/** The same retry for code that is not suspend (the hydration staging walks); sleeps the worker thread. */
fun <T> withLocalIoRetryBlocking(action: () -> T): T {
    if (!WINDOWS) return action()
    var attempt = 0
    while (true) {
        try {
            return action()
        } catch (e: CancellationException) {
            throw e
        } catch (e: FileSystemException) {
            val giveUp = lockRetryVerdict(e, attempt)
            if (giveUp != null) throw giveUp
            Thread.sleep(RETRY_DELAYS_MS[attempt])
            attempt++
        }
    }
}

/** A held-open file that is still there is worth another try; a file that is gone is not ("locked" vs "gone"). */
private fun isRetriableLock(e: FileSystemException): Boolean {
    if (e is AccessDeniedException) {
        val file = e.file?.let { runCatching { Path.of(it) }.getOrNull() }
        return file != null && Files.exists(file)
    }
    // The reason is the platform's, and localized: the English and the German "in use by another
    // process" phrasings are what this repo's machines produce. AccessDeniedException stays the
    // signal that needs no text at all (handled by the caller).
    val text = ((e.reason ?: "") + " " + (e.message ?: "")).lowercase()
    val looksLikeALock =
        "sharing violation" in text ||
            "being used by another process" in text ||
            "von einem anderen prozess verwendet" in text
    if (!looksLikeALock) return false
    val file = e.file?.let { runCatching { Path.of(it) }.getOrNull() } ?: return false
    return Files.exists(file)
}

/** The attempts are used up: today's failure, with the cause named (a lock, not a disappearance). */
private fun lockedUp(e: FileSystemException): FileSystemException =
    FileSystemException(e.file, e.otherFile, "locked by another process: ${e.reason ?: e.message}").apply { initCause(e) }
