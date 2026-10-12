package org.krost.unidrive.cli

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.krost.unidrive.CredentialHealth
import org.krost.unidrive.cli.CredentialHealthMonitor.Reading
import org.krost.unidrive.cli.CredentialHealthMonitor.State
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #586: the `credential_health` object of `daemon.status`. Unknown stays unknown: nothing is `ok` until a check
 * said so, and a status read never waits for the check.
 */
class CredentialHealthMonitorTest {
    private val scope = CoroutineScope(SupervisorJob())

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private var now = 1_000_000L

    // Unconfined: the launched check runs to completion inside the call, so the tests need no waiting.
    private fun monitor(
        ttlMs: Long = 60_000,
        check: (() -> CredentialHealth)?,
    ) = CredentialHealthMonitor(check, ttlMs, scope, ioDispatcher = Dispatchers.Unconfined, clock = { now })

    private fun readingOf(health: CredentialHealth): Reading = CredentialHealthMonitor.toReading(health, checkedAtMs = now)

    @Test
    fun `ok maps to ok with no expiry`() {
        val reading = readingOf(CredentialHealth.Ok)

        assertEquals(State.OK, reading.state)
        assertNull(reading.expiresInMs(now), "Ok knows no expiry: unknown, not 0")
        assertEquals(now, reading.checkedAtMs)
    }

    @Test
    fun `an expiring credential counts down from the time the check measured`() {
        val reading = readingOf(CredentialHealth.ExpiresIn(hours = 5, message = "5h", remainingMs = 5 * 3_600_000L + 120_000))

        assertEquals(State.EXPIRING, reading.state)
        assertEquals(5 * 3_600_000L + 120_000, reading.expiresInMs(now))
        assertEquals(5 * 3_600_000L + 120_000 - 60_000, reading.expiresInMs(now + 60_000), "counts down between checks")
        assertEquals(0L, reading.expiresInMs(now + 99 * 3_600_000L), "never negative")
    }

    @Test
    fun `under an hour left is expiring, not expired, when the provider knows the exact time`() {
        val reading = readingOf(CredentialHealth.ExpiresIn(hours = 0, message = "0h", remainingMs = 20 * 60_000L))

        assertEquals(State.EXPIRING, reading.state)
        assertEquals(20 * 60_000L, reading.expiresInMs(now))
    }

    @Test
    fun `whole hours alone are a lower bound`() {
        val reading = readingOf(CredentialHealth.ExpiresIn(hours = 3, message = "3h"))

        assertEquals(State.EXPIRING, reading.state)
        assertEquals(3 * 3_600_000L, reading.expiresInMs(now))
    }

    @Test
    fun `zero hours without an exact time is expired`() {
        val reading = readingOf(CredentialHealth.ExpiresIn(hours = 0, message = "JWT expired"))

        assertEquals(State.EXPIRED, reading.state)
        assertEquals(0L, reading.expiresInMs(now))
    }

    @Test
    fun `an exact time that has run out is expired`() {
        assertEquals(State.EXPIRED, readingOf(CredentialHealth.ExpiresIn(hours = 0, message = "gone", remainingMs = -5)).state)
        assertEquals(State.EXPIRED, readingOf(CredentialHealth.ExpiresIn(hours = 0, message = "gone", remainingMs = 0)).state)
    }

    @Test
    fun `missing maps to missing with no expiry`() {
        val reading = readingOf(CredentialHealth.Missing("No credentials file"))

        assertEquals(State.MISSING, reading.state)
        assertNull(reading.expiresInMs(now))
    }

    @Test
    fun `a warning is a finding the daemon cannot classify and stays unknown`() {
        val reading = readingOf(CredentialHealth.Warning("token.json exists but could not be parsed"))

        assertEquals(State.UNKNOWN, reading.state)
        assertNull(reading.expiresInMs(now))
        assertEquals(now, reading.checkedAtMs, "it was checked: the daemon looked and could not tell")
    }

    @Test
    fun `before the first check has finished, and with no check at all, the state is unknown`() {
        val latch = CountDownLatch(1)
        val slow = CredentialHealthMonitor({ latch.await(); CredentialHealth.Ok }, 60_000, scope, ioDispatcher = Dispatchers.IO)
        try {
            val first = slow.reading()
            assertEquals(State.UNKNOWN, first.state)
            assertNull(first.checkedAtMs, "not checked yet")
        } finally {
            latch.countDown()
        }

        val none = monitor(check = null).reading()
        assertEquals(Reading(State.UNKNOWN, expiresAtMs = null, checkedAtMs = null), none)
    }

    @Test
    fun `a check that throws leaves unknown and is not repeated before the ttl`() {
        val calls = AtomicInteger()
        val m = monitor { calls.incrementAndGet(); error("credential store unavailable") }

        m.reading() // starts the check, which throws
        val after = m.reading()

        assertEquals(State.UNKNOWN, after.state)
        assertEquals(now, after.checkedAtMs)
        assertEquals(1, calls.get(), "a failing check is not hammered")
    }

    @Test
    fun `a second read inside the ttl does not run the check again, one past it does`() {
        val calls = AtomicInteger()
        val m = monitor(ttlMs = 60_000) { calls.incrementAndGet(); CredentialHealth.Ok }

        assertEquals(State.UNKNOWN, m.reading().state, "the first read only starts the check")
        assertEquals(State.OK, m.reading().state)
        now += 59_999
        assertEquals(State.OK, m.reading().state)
        assertEquals(1, calls.get(), "inside the ttl the check ran once")

        now += 1
        m.reading() // past the ttl: serves the old value and re-checks
        assertEquals(2, calls.get())
    }

    @Test
    fun `a slow check never delays a read and lands in a later one`() {
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val m = CredentialHealthMonitor(
            check = {
                started.countDown()
                release.await(30, TimeUnit.SECONDS)
                CredentialHealth.Ok
            },
            ttlMs = 60_000,
            scope = scope,
            ioDispatcher = Dispatchers.IO,
            clock = { now },
        )

        val t0 = System.nanoTime()
        val duringCheck = m.reading()
        m.reading()
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000

        assertTrue(started.await(10, TimeUnit.SECONDS), "the check ran off the calling thread")
        assertEquals(State.UNKNOWN, duringCheck.state)
        assertTrue(elapsedMs < 1_000, "the reads returned while the check was blocked ($elapsedMs ms)")

        release.countDown()
        val deadline = System.currentTimeMillis() + 10_000
        while (m.reading().state != State.OK && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(State.OK, m.reading().state)
    }

    @Test
    fun `a rejection newer than the last contact turns ok, expiring and unknown into expired`() {
        for (health in listOf(CredentialHealth.Ok, CredentialHealth.ExpiresIn(5, "5h"), CredentialHealth.Warning("odd"))) {
            val rejected = readingOf(health).withRejection(authRejectedAtMs = now + 10, lastContactMs = now)

            assertEquals(State.EXPIRED, rejected.state, "$health")
            assertEquals(0L, rejected.expiresInMs(now + 10))
            assertEquals(now + 10, rejected.checkedAtMs, "stamped with the moment the rejection was seen")
        }
        // Nothing has worked yet (no contact at all): the rejection stands.
        assertEquals(State.EXPIRED, readingOf(CredentialHealth.Ok).withRejection(now + 10, lastContactMs = null).state)
    }

    @Test
    fun `a rejection older than a success is history`() {
        val ok = readingOf(CredentialHealth.Ok)

        assertEquals(ok, ok.withRejection(authRejectedAtMs = now, lastContactMs = now + 5))
        assertEquals(ok, ok.withRejection(authRejectedAtMs = now, lastContactMs = now), "the same millisecond: the success wins")
        assertEquals(ok, ok.withRejection(authRejectedAtMs = null, lastContactMs = null))
    }

    @Test
    fun `a rejection does not hide that the credential is missing`() {
        val missing = readingOf(CredentialHealth.Missing("No token.json"))

        assertEquals(State.MISSING, missing.withRejection(authRejectedAtMs = now + 10, lastContactMs = null).state)
    }

    @Test
    fun `the wire object has exactly state, expires_in_ms and checked_at_ms and writes unknowns as null`() {
        val unknown: JsonObject = Reading(State.UNKNOWN, expiresAtMs = null, checkedAtMs = null).toJson(now)
        assertEquals(setOf("state", "expires_in_ms", "checked_at_ms"), unknown.keys)
        assertEquals("unknown", unknown.getValue("state").jsonPrimitive.content)
        assertEquals(JsonNull, unknown.getValue("expires_in_ms"))
        assertEquals(JsonNull, unknown.getValue("checked_at_ms"))

        val expiring = readingOf(CredentialHealth.ExpiresIn(2, "2h", remainingMs = 2 * 3_600_000L)).toJson(now + 1_000)
        assertEquals("expiring", expiring.getValue("state").jsonPrimitive.content)
        assertEquals((2 * 3_600_000L - 1_000).toString(), expiring.getValue("expires_in_ms").jsonPrimitive.content)
        assertEquals(now.toString(), expiring.getValue("checked_at_ms").jsonPrimitive.content)
    }
}
