package org.krost.unidrive.hydration

import java.nio.file.Paths

/**
 * Translates IpcServer JSON-line requests to Hydration verb calls
 * and back. Registers six verbs on the IpcServer; the per-connection
 * id used in the Hydration API is the IpcServer's per-client opaque
 * connection identifier (passed by the caller of handle()).
 *
 * Wire format (all JSON):
 *   open_read   request:  {"verb":"hydration.open_read","handle_id":"...","path":"/foo"}
 *   open_read   reply ok: {"ok":true,"cache_path":"/home/.../foo.txt"}
 *   open_read   reply err:{"ok":false,"error":"<message>"}
 *   open_write  request:  {"verb":"hydration.open_write","handle_id":"...","path":"/foo","cache_path":"/home/.../foo.txt"}
 *   open_write  reply:    same as open_read
 *   close_handle request: {"verb":"hydration.close_handle","handle_id":"..."}
 *   close_handle reply:   {"ok":true}
 *   hydrate     request:  {"verb":"hydration.hydrate","path":"/foo"}
 *   hydrate     reply:    {"ok":true} or {"ok":false,"error":"<message>"}
 *   dehydrate   request:  {"verb":"hydration.dehydrate","path":"/foo"}
 *   dehydrate   reply:    {"ok":true} or {"ok":false,"error":"busy"} or {"ok":false,"error":"<message>"}
 *   subscribe   request:  {"verb":"hydration.subscribe"}
 *   subscribe   reply:    {"ok":true} — and from then on, the connection becomes a one-way
 *                         event stream (server-pushed NDJSON of HydrationEvent serializations)
 *
 * The subscribe verb is registered but its event-push side is wired up
 * by the daemon startup glue (Task 14) — Phase 1 ships the verb call;
 * full stream delivery is exercised by the smoke test in Task 15.
 */
class HydrationIpcHandler(
    private val hydration: Hydration,
) {
    suspend fun handle(connectionId: String, jsonRequest: String): String {
        val verb = pluck(jsonRequest, "verb") ?: return reply(ok = false, error = "missing_verb")
        return when (verb) {
            "hydration.open_read" -> {
                val handleId = pluck(jsonRequest, "handle_id") ?: return reply(ok = false, error = "missing_handle_id")
                val path = pluck(jsonRequest, "path") ?: return reply(ok = false, error = "missing_path")
                when (val r = hydration.openForRead(connectionId, handleId, path)) {
                    is OpenResult.Ok -> """{"ok":true,"cache_path":${jsonEsc(r.cachePath.toString())}}"""
                    is OpenResult.Failed -> reply(ok = false, error = r.error.message)
                }
            }
            "hydration.open_write" -> {
                val handleId = pluck(jsonRequest, "handle_id") ?: return reply(ok = false, error = "missing_handle_id")
                val path = pluck(jsonRequest, "path") ?: return reply(ok = false, error = "missing_path")
                val cache = pluck(jsonRequest, "cache_path") ?: return reply(ok = false, error = "missing_cache_path")
                if (cache.isEmpty()) return reply(ok = false, error = "missing_cache_path")
                when (val r = hydration.openForWrite(connectionId, handleId, path, Paths.get(cache))) {
                    is OpenResult.Ok -> """{"ok":true,"cache_path":${jsonEsc(r.cachePath.toString())}}"""
                    is OpenResult.Failed -> reply(ok = false, error = r.error.message)
                }
            }
            "hydration.close_handle" -> {
                val handleId = pluck(jsonRequest, "handle_id") ?: return reply(ok = false, error = "missing_handle_id")
                hydration.closeHandle(connectionId, handleId)
                reply(ok = true)
            }
            "hydration.hydrate" -> {
                val path = pluck(jsonRequest, "path") ?: return reply(ok = false, error = "missing_path")
                when (val r = hydration.hydrate(path)) {
                    HydrateResult.Ok -> reply(ok = true)
                    is HydrateResult.Failed -> reply(ok = false, error = r.error.message)
                }
            }
            "hydration.dehydrate" -> {
                val path = pluck(jsonRequest, "path") ?: return reply(ok = false, error = "missing_path")
                when (val r = hydration.dehydrate(path)) {
                    DehydrateResult.Ok -> reply(ok = true)
                    DehydrateResult.Busy -> reply(ok = false, error = "busy")
                    is DehydrateResult.Failed -> reply(ok = false, error = r.error.message)
                }
            }
            "hydration.subscribe" -> {
                // event-stream wiring lands in daemon startup glue (Task 14)
                reply(ok = true)
            }
            else -> reply(ok = false, error = "unknown_verb")
        }
    }

    private fun reply(ok: Boolean, error: String? = null): String =
        if (ok) """{"ok":true}""" else """{"ok":false,"error":${jsonEsc(error ?: "unknown")}}"""

    // Minimal JSON pluck — works for flat top-level string fields. Sufficient
    // for our verb messages; we don't accept arbitrary client JSON shapes.
    private fun pluck(line: String, key: String): String? {
        val needle = "\"$key\""
        val k = line.indexOf(needle)
        if (k < 0) return null
        val colon = line.indexOf(':', k + needle.length)
        if (colon < 0) return null
        val q1 = line.indexOf('"', colon)
        if (q1 < 0) return null
        val sb = StringBuilder()
        var i = q1 + 1
        while (i < line.length) {
            val c = line[i]
            if (c == '"') return sb.toString()
            if (c == '\\' && i + 1 < line.length) { sb.append(line[i + 1]); i += 2; continue }
            sb.append(c); i++
        }
        return null
    }
}

/**
 * Serialise a HydrationEvent to a JSON-line string suitable for broadcast
 * via IpcServer.emit(). Called by the daemon startup glue (Task 14) in the
 * event-fan-out coroutine.
 */
fun serialiseHydrationEvent(e: HydrationEvent): String = when (e) {
    is HydrationEvent.Hydrating  -> """{"event":"hydrating","path":${jsonEsc(e.path)}}"""
    is HydrationEvent.Hydrated   -> """{"event":"hydrated","path":${jsonEsc(e.path)},"bytes":${e.bytes}}"""
    is HydrationEvent.Dehydrated -> """{"event":"dehydrated","path":${jsonEsc(e.path)}}"""
    is HydrationEvent.Failed     -> """{"event":"failed","path":${jsonEsc(e.path)},"error":${jsonEsc(e.error.message)}}"""
}

private fun jsonEsc(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
