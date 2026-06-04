package org.krost.unidrive.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #164: `RefreshRpcHandler` previously escaped only double-quotes
 * (`e.message?.replace("\"", "\\\"")`) before embedding an exception/provider
 * message into the hand-built `refresh.done` terminal event. JSON strings also
 * require escaping backslashes, control characters (`\n`, `\r`, `\t`, `\b`,
 * `\f`) and bytes below 0x20 — so a Windows path (backslashes) or a multi-line
 * error message produced INVALID JSON that a strict client could not parse.
 *
 * The fix routes the message through `RefreshRpcHandler.jsonString`, which uses
 * `kotlinx.serialization`'s `JsonPrimitive`. These tests pin the escaping
 * contract directly on that seam — the unit that owns the bug — and prove the
 * output embeds into the event string as valid, round-trippable JSON.
 *
 * Invariants are split one-per-test (orthogonal decomposition): a regression in
 * the special-char path can't hide behind the clean-message path, and vice
 * versa.
 */
class RefreshRpcHandlerJsonEscapeTest {
    /** Embed [message] exactly as the handler does and parse the result back. */
    private fun roundTrippedMessage(message: String?): String {
        val msg = RefreshRpcHandler.jsonString(message)
        // Mirror the production event-string shape: `"message":$msg` (no extra quotes).
        val event = """{"event":"refresh.done","ok":false,"error":"provider_error","message":$msg}"""
        val parsed = Json.parseToJsonElement(event) as JsonObject
        return parsed["message"]!!.jsonPrimitive.content
    }

    /**
     * INVARIANT (#164): a message with a backslash, an embedded quote, a newline
     * and a control char round-trips through the event as valid JSON with the
     * EXACT original content. This is the case the quote-only escape broke.
     *
     * Regression mode if removed/loosened: an exotic provider/exception message
     * (Windows path, multi-line stack text) again produces invalid `refresh.done`
     * JSON that a strict daemon client cannot parse.
     */
    @Test
    fun `message with backslash quote newline and control char round-trips as valid JSON`() {
        val original = "path C:\\Users\\x with \"quotes\" and \nnewline and \u0001 control"
        assertEquals(
            original,
            roundTrippedMessage(original),
            "special-char message must survive embedding + parse intact",
        )
    }

    /**
     * INVARIANT (#164): a plain message (no special chars) still round-trips
     * unchanged — the fix must not corrupt the common case.
     */
    @Test
    fun `a clean message round-trips unchanged`() {
        val original = "provider returned 503 Service Unavailable"
        assertEquals(original, roundTrippedMessage(original))
    }

    /**
     * INVARIANT (#164): a null message degrades to the empty JSON string,
     * matching the prior `?: ""` behaviour — no NPE, still valid JSON.
     */
    @Test
    fun `a null message becomes an empty JSON string`() {
        assertEquals("", roundTrippedMessage(null))
        assertEquals("\"\"", RefreshRpcHandler.jsonString(null), "null must render as the JSON empty string literal")
    }
}
