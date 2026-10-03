package org.krost.unidrive.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.krost.unidrive.sync.EnumerationStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The wire shape of the enumeration object in daemon.status: the names clients decode, and what is left out.
class EnumerationStatusJsonTest {
    private fun wire(status: EnumerationStatus): JsonObject = Json.parseToJsonElement(status.toJson().toString()).jsonObject

    @Test
    fun `an idle status with nothing known has exactly state, first and attempt`() {
        val json = wire(EnumerationStatus(state = EnumerationStatus.State.IDLE, first = true, attempt = 0))

        assertEquals(setOf("state", "first", "attempt"), json.keys)
        assertEquals("idle", json.getValue("state").jsonPrimitive.content)
        assertTrue(json.getValue("first").jsonPrimitive.boolean)
        assertEquals(0, json.getValue("attempt").jsonPrimitive.int)
    }

    @Test
    fun `a running status names every field the way clients read it`() {
        val json =
            wire(
                EnumerationStatus(
                    state = EnumerationStatus.State.RUNNING,
                    first = false,
                    attempt = 2,
                    phase = EnumerationStatus.Phase.LISTING,
                    listing = "tree",
                    startedAtMs = 1_700_000_000_000,
                    elapsedMs = 61_500,
                    items = 47_000,
                    foldersDone = 310,
                    foldersKnown = 4_100,
                    foldersSkipped = 3,
                    ratePerS = 312.46,
                    etaS = 905,
                    etaKind = EnumerationStatus.EtaKind.LOWER_BOUND,
                    lastSuccessAtMs = 1_699_999_000_000,
                    lastError = "connection closed",
                    nextAttemptAtMs = null,
                ),
            )

        assertEquals(
            setOf(
                "state", "first", "attempt", "phase", "listing", "started_at_ms", "elapsed_ms", "items", "folders_done",
                "folders_known", "folders_skipped", "rate_per_s", "eta_s", "eta_kind", "last_success_at_ms", "last_error",
            ),
            json.keys,
            "next_attempt_at_ms is unknown and therefore absent",
        )
        assertEquals("running", json.getValue("state").jsonPrimitive.content)
        assertFalse(json.getValue("first").jsonPrimitive.boolean)
        assertEquals(2, json.getValue("attempt").jsonPrimitive.int)
        assertEquals("listing", json.getValue("phase").jsonPrimitive.content)
        assertEquals("tree", json.getValue("listing").jsonPrimitive.content)
        assertEquals(1_700_000_000_000L, json.getValue("started_at_ms").jsonPrimitive.long)
        assertEquals(61_500L, json.getValue("elapsed_ms").jsonPrimitive.long)
        assertEquals(47_000, json.getValue("items").jsonPrimitive.int)
        assertEquals(310, json.getValue("folders_done").jsonPrimitive.int)
        assertEquals(4_100, json.getValue("folders_known").jsonPrimitive.int)
        assertEquals(3, json.getValue("folders_skipped").jsonPrimitive.int)
        assertEquals(312.5, json.getValue("rate_per_s").jsonPrimitive.double, "one decimal is enough for a rate")
        assertEquals(905L, json.getValue("eta_s").jsonPrimitive.long)
        assertEquals("lower_bound", json.getValue("eta_kind").jsonPrimitive.content)
        assertEquals(1_699_999_000_000L, json.getValue("last_success_at_ms").jsonPrimitive.long)
        assertEquals("connection closed", json.getValue("last_error").jsonPrimitive.content)
    }

    @Test
    fun `a failed status carries the retry time and an estimate is named estimate`() {
        val failed =
            wire(
                EnumerationStatus(
                    state = EnumerationStatus.State.FAILED,
                    first = true,
                    attempt = 3,
                    lastError = "boom",
                    nextAttemptAtMs = 1_700_000_240_000,
                ),
            )
        val estimating =
            wire(
                EnumerationStatus(
                    state = EnumerationStatus.State.RUNNING,
                    first = true,
                    attempt = 1,
                    phase = EnumerationStatus.Phase.SAVING,
                    etaS = 10,
                    etaKind = EnumerationStatus.EtaKind.ESTIMATE,
                ),
            )

        assertEquals("failed", failed.getValue("state").jsonPrimitive.content)
        assertEquals(1_700_000_240_000L, failed.getValue("next_attempt_at_ms").jsonPrimitive.long)
        assertEquals("saving", estimating.getValue("phase").jsonPrimitive.content)
        assertEquals("estimate", estimating.getValue("eta_kind").jsonPrimitive.content)
    }

    @Test
    fun `no field is ever written as null`() {
        val json = wire(EnumerationStatus(state = EnumerationStatus.State.RUNNING, first = true, attempt = 1, items = 5))

        assertTrue(json.values.none { it is JsonNull }, "unknown means absent: $json")
        assertEquals(JsonPrimitive(5), json.getValue("items"))
    }

    @Test
    fun `a reason with quotes and backslashes survives as valid json`() {
        val json = wire(EnumerationStatus(state = EnumerationStatus.State.FAILED, first = true, attempt = 1, lastError = "said \"no\" \\ twice"))

        assertEquals("said \"no\" \\ twice", json.getValue("last_error").jsonPrimitive.content)
    }
}
