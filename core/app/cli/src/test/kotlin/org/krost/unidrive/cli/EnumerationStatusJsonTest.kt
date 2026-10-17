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
                    recovery = true,
                    droppedItems = 14_591,
                    lastError = "connection closed",
                    nextAttemptAtMs = null,
                ),
            )

        assertEquals(
            setOf(
                "state", "first", "attempt", "phase", "listing", "started_at_ms", "elapsed_ms", "items", "folders_done",
                "folders_known", "folders_skipped", "rate_per_s", "eta_s", "eta_kind", "last_success_at_ms", "dropped_items", "recovery",
                "last_error",
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
        assertTrue(json.getValue("recovery").jsonPrimitive.boolean)
        assertEquals(14_591, json.getValue("dropped_items").jsonPrimitive.int)
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

    // #878: last_error_code is its own key beside last_error, and absent (never null) when unknown.
    @Test
    fun `a failed status names last_error_code and leaves it out when unknown`() {
        val coded =
            wire(EnumerationStatus(state = EnumerationStatus.State.FAILED, first = true, attempt = 1, lastError = "API error: 408", lastErrorCode = "http_408"))
        val uncoded = wire(EnumerationStatus(state = EnumerationStatus.State.FAILED, first = true, attempt = 1, lastError = "boom"))

        assertEquals("http_408", coded.getValue("last_error_code").jsonPrimitive.content)
        assertEquals("API error: 408", coded.getValue("last_error").jsonPrimitive.content)
        assertFalse("last_error_code" in uncoded.keys, "unknown means absent: $uncoded")
    }

    // The items of the last full listing below a folder that is gone for good: a count, absent when there are none.
    @Test
    fun `unreachable_items is named when there are some and absent otherwise`() {
        val some = wire(EnumerationStatus(state = EnumerationStatus.State.IDLE, first = false, attempt = 0, lastScanComplete = true, unreachableItems = 14_591))
        val none = wire(EnumerationStatus(state = EnumerationStatus.State.IDLE, first = false, attempt = 0, lastScanComplete = true))

        assertEquals(14_591, some.getValue("unreachable_items").jsonPrimitive.int)
        assertFalse("unreachable_items" in none.keys, "none means absent: $none")
    }

    // #926: a running attempt that lists again because the last scan was incomplete says so; the items the last full
    // listing did not return stay as a count after it. Both are absent (never false or 0) otherwise.
    @Test
    fun `recovery is true only while it runs and dropped_items is absent when there are none`() {
        val running =
            wire(EnumerationStatus(state = EnumerationStatus.State.RUNNING, first = false, attempt = 1, recovery = true, listing = "cursor", items = 12))
        val plain = wire(EnumerationStatus(state = EnumerationStatus.State.RUNNING, first = false, attempt = 1, listing = "cursor", items = 12))
        val after =
            wire(EnumerationStatus(state = EnumerationStatus.State.IDLE, first = false, attempt = 0, lastScanComplete = false, droppedItems = 14_591))
        val clean = wire(EnumerationStatus(state = EnumerationStatus.State.IDLE, first = false, attempt = 0, lastScanComplete = true))

        assertTrue(running.getValue("recovery").jsonPrimitive.boolean)
        assertFalse("recovery" in plain.keys, "not a recovery means absent: $plain")
        assertFalse("recovery" in after.keys, "$after")
        assertEquals(14_591, after.getValue("dropped_items").jsonPrimitive.int)
        assertFalse("dropped_items" in clean.keys, "none means absent: $clean")
        assertFalse("dropped_items" in running.keys, "$running")
    }

    // The step after the listing: its phase and its own counts, absent (never null) outside it.
    @Test
    fun `resolving paths names its phase and its counts`() {
        val resolving =
            wire(
                EnumerationStatus(
                    state = EnumerationStatus.State.RUNNING,
                    first = true,
                    attempt = 1,
                    phase = EnumerationStatus.Phase.RESOLVING_PATHS,
                    listing = "cursor",
                    items = 387_140,
                    pathsDone = 400,
                    pathsTotal = 949,
                ),
            )
        val listing = wire(EnumerationStatus(state = EnumerationStatus.State.RUNNING, first = true, attempt = 1, phase = EnumerationStatus.Phase.LISTING, items = 5))

        assertEquals("resolving_paths", resolving.getValue("phase").jsonPrimitive.content)
        assertEquals(400, resolving.getValue("paths_done").jsonPrimitive.int)
        assertEquals(949, resolving.getValue("paths_total").jsonPrimitive.int)
        assertEquals(387_140, resolving.getValue("items").jsonPrimitive.int)
        assertFalse("paths_done" in listing.keys || "paths_total" in listing.keys, "unknown means absent: $listing")
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
