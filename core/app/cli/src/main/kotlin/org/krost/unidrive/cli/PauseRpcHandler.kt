package org.krost.unidrive.cli

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.krost.unidrive.hydration.HydrationEvent
import org.krost.unidrive.hydration.PauseGate

/**
 * IPC verb handlers for `daemon.pause` and `daemon.resume` (docs/dev/specs/unidrive-daemon-design.md §4.7).
 * Both need the full scope (`IpcAuth.VERB_CLASSES`: admin); the state is [gate], held in memory only.
 *
 * - `daemon.pause {reason, auto, until_ms}`: `reason` is a short machine word the client words itself (`metered`,
 *   `power_saver`, `user`): 1 to [REASON_MAX_LENGTH] characters of `a-z`, `0-9` and `_`. A missing reason answers
 *   `missing_reason`, any other that is not such a word `invalid_reason`; `auto` (default false: a person
 *   paused) must be a JSON boolean or the answer is `invalid_auto`. `until_ms` (optional) is the epoch ms at which
 *   the daemon resumes by itself: it must be a JSON integer (`invalid_until`) after the daemon's clock now
 *   (`until_in_past`). A refusal changes nothing. The reply is `{"ok":true,"paused":{reason,since_ms,auto[,until_ms]}}`.
 *   Pausing while paused keeps the first `since_ms` and takes the new reason, `auto` and `until_ms`: a request names the
 *   whole pause, so one without `until_ms` makes a timed pause untimed, and one with a new `until_ms` replaces the timer.
 * - `daemon.resume`: `{"ok":true}`, also when the daemon was running (a no-op). It cancels the timer.
 *
 * The timer lives in [scope] and is as volatile as the pause: a restarted daemon runs. It checks the wall clock
 * ([PauseGate.clockMs]) at least every [CLOCK_RECHECK_MS], so a clock that was set or a machine that slept through
 * the instant resumes the daemon within that interval of waking, not at a monotonic deadline.
 *
 * A pause that begins applies the in-flight rule at once ([PauseGate.abortSlowTransfers]), and every change a
 * client can observe (a pause begun, a reason, `auto` or end instant changed, a pause ended by `daemon.resume` or by
 * the timer) goes to [publish] as an event. Pausing with the state the daemon already has, and resuming a running
 * daemon, publish nothing.
 */
class PauseRpcHandler(
    private val gate: PauseGate,
    private val scope: CoroutineScope,
    private val publish: (HydrationEvent) -> Unit,
) {
    // Guarded by synchronized(gate), like the state it belongs to.
    private var timer: Job? = null

    fun pause(jsonRequest: String): String {
        val body = runCatching { Json.parseToJsonElement(jsonRequest) as? JsonObject }.getOrNull()
        val reasonElement = body?.get("reason") ?: return MISSING_REASON
        val reason = (reasonElement as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (reason == null || !REASON.matches(reason)) return INVALID_REASON
        val auto =
            when (val element = body["auto"]) {
                null -> false
                else -> (element as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull() ?: return INVALID_AUTO
            }
        val untilMs =
            when (val element = body["until_ms"]) {
                null -> null
                else -> (element as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toLongOrNull() ?: return INVALID_UNTIL
            }
        if (untilMs != null && untilMs <= gate.clockMs()) return UNTIL_IN_PAST
        // State, abort and event in one step: two requests at once cannot swap their events.
        synchronized(gate) {
            val change = gate.pause(reason, auto, untilMs)
            val state = checkNotNull(gate.current) { "paused just now" }
            if (change == PauseGate.Change.STARTED) gate.abortSlowTransfers()
            if (change != PauseGate.Change.UNCHANGED) {
                armTimer(untilMs)
                publish(HydrationEvent.Paused(state.reason, state.sinceMs, state.auto, state.untilMs))
            }
            return buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("paused", state.toJson())
            }.toString()
        }
    }

    fun resume(): String {
        synchronized(gate) {
            cancelTimer()
            if (gate.resume()) publish(HydrationEvent.Resumed())
        }
        return """{"ok":true}"""
    }

    private fun cancelTimer() {
        timer?.cancel()
        timer = null
    }

    // Caller holds synchronized(gate). Replaces any earlier timer; null [untilMs] leaves the pause untimed.
    private fun armTimer(untilMs: Long?) {
        cancelTimer()
        if (untilMs == null) return
        timer =
            scope.launch {
                val self = coroutineContext.job
                while (true) {
                    val remaining = untilMs - gate.clockMs()
                    if (remaining <= 0) break
                    delay(minOf(remaining, CLOCK_RECHECK_MS))
                }
                synchronized(gate) {
                    // A resume or a newer pause that raced this wake-up has already replaced the timer.
                    if (timer !== self) return@launch
                    timer = null
                    if (gate.resume()) publish(HydrationEvent.Resumed(HydrationEvent.Resumed.TIMEOUT))
                }
            }
    }

    companion object {
        const val REASON_MAX_LENGTH = 32

        /** The longest the timer sleeps before it reads the wall clock again. */
        const val CLOCK_RECHECK_MS = 30_000L

        private val REASON = Regex("[a-z0-9_]{1,$REASON_MAX_LENGTH}")

        private const val MISSING_REASON = """{"ok":false,"error":"missing_reason"}"""
        private const val INVALID_REASON = """{"ok":false,"error":"invalid_reason"}"""
        private const val INVALID_AUTO = """{"ok":false,"error":"invalid_auto"}"""
        private const val INVALID_UNTIL = """{"ok":false,"error":"invalid_until"}"""
        private const val UNTIL_IN_PAST = """{"ok":false,"error":"until_in_past"}"""
    }
}
