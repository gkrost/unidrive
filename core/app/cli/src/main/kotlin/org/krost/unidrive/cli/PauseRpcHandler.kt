package org.krost.unidrive.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.krost.unidrive.hydration.HydrationEvent
import org.krost.unidrive.hydration.PauseGate

/**
 * IPC verb handlers for `daemon.pause` and `daemon.resume` (docs/dev/specs/unidrive-daemon-design.md §4.4).
 * Both need the full scope (`IpcAuth.VERB_CLASSES`: admin); the state is [gate], held in memory only.
 *
 * - `daemon.pause {reason, auto}`: `reason` is a short machine word the client words itself (`metered`,
 *   `power_saver`, `user`): 1 to [REASON_MAX_LENGTH] characters of `a-z`, `0-9` and `_`. A missing reason answers
 *   `missing_reason`, any other that is not such a word `invalid_reason`; `auto` (default false: a person
 *   paused) must be a JSON boolean or the answer is `invalid_auto`. A refusal changes nothing. The reply is
 *   `{"ok":true,"paused":{reason,since_ms,auto}}`. Pausing while paused keeps the first `since_ms` and takes the
 *   new reason and `auto`.
 * - `daemon.resume`: `{"ok":true}`, also when the daemon was running (a no-op).
 *
 * A pause that begins applies the in-flight rule at once ([PauseGate.abortSlowTransfers]), and every change a
 * client can observe (a pause begun, a reason or `auto` changed, a pause ended) goes to [publish] as an event.
 * Pausing with the state the daemon already has, and resuming a running daemon, publish nothing.
 */
class PauseRpcHandler(
    private val gate: PauseGate,
    private val publish: (HydrationEvent) -> Unit,
) {
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
        // State, abort and event in one step: two requests at once cannot swap their events.
        synchronized(gate) {
            val change = gate.pause(reason, auto)
            val state = checkNotNull(gate.current) { "paused just now" }
            if (change == PauseGate.Change.STARTED) gate.abortSlowTransfers()
            if (change != PauseGate.Change.UNCHANGED) publish(HydrationEvent.Paused(state.reason, state.sinceMs, state.auto))
            return buildJsonObject {
                put("ok", JsonPrimitive(true))
                put("paused", state.toJson())
            }.toString()
        }
    }

    fun resume(): String {
        synchronized(gate) {
            if (gate.resume()) publish(HydrationEvent.Resumed)
        }
        return """{"ok":true}"""
    }

    companion object {
        const val REASON_MAX_LENGTH = 32

        private val REASON = Regex("[a-z0-9_]{1,$REASON_MAX_LENGTH}")

        private const val MISSING_REASON = """{"ok":false,"error":"missing_reason"}"""
        private const val INVALID_REASON = """{"ok":false,"error":"invalid_reason"}"""
        private const val INVALID_AUTO = """{"ok":false,"error":"invalid_auto"}"""
    }
}
