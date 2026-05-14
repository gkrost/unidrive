package org.krost.unidrive.sync.audit

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuditLogTest {
    private lateinit var dir: Path

    @AfterTest
    fun cleanup() {
        if (::dir.isInitialized) {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `UD-113 emits one JSONL entry per emit call to date-stamped file`() {
        dir = createTempDirectory("audit-")
        val fixedDay = Instant.parse("2026-05-03T19:00:00Z")
        val log = AuditLog(dir, profileName = "inxt_test", clock = { fixedDay })

        log.emit("Upload", "/Documents/a.txt", result = "success", size = 1234, newHash = "abc")
        log.emit("Delete", "/Documents/old.txt", result = "success", size = 99, oldHash = "def")
        log.emit("Move", "/Documents/new.txt", result = "success", fromPath = "/Documents/older.txt", oldHash = "def", newHash = "def")

        val file = dir.resolve("audit-2026-05-03.jsonl")
        assertTrue(Files.exists(file), "audit file should exist at $file")
        val lines = Files.readAllLines(file)
        assertEquals(3, lines.size)

        val entry0 = Json.parseToJsonElement(lines[0]) as JsonObject
        assertEquals("Upload", entry0["action"]!!.jsonPrimitive.content)
        assertEquals("/Documents/a.txt", entry0["path"]!!.jsonPrimitive.content)
        assertEquals("success", entry0["result"]!!.jsonPrimitive.content)
        assertEquals("1234", entry0["size"]!!.jsonPrimitive.content)
        assertEquals("abc", entry0["newHash"]!!.jsonPrimitive.content)
        assertEquals("inxt_test", entry0["profile"]!!.jsonPrimitive.content)
        assertEquals(fixedDay.toString(), entry0["ts"]!!.jsonPrimitive.content)

        val entry2 = Json.parseToJsonElement(lines[2]) as JsonObject
        assertEquals("Move", entry2["action"]!!.jsonPrimitive.content)
        assertEquals("/Documents/older.txt", entry2["fromPath"]!!.jsonPrimitive.content)
    }

    @Test
    fun `UD-113 rotates filename across UTC day boundary`() {
        dir = createTempDirectory("audit-")
        var now = Instant.parse("2026-05-03T23:59:59Z")
        val log = AuditLog(dir, profileName = "p1", clock = { now })

        log.emit("Upload", "/late.txt", result = "success")
        now = Instant.parse("2026-05-04T00:00:01Z")
        log.emit("Upload", "/early.txt", result = "success")

        val day1 = dir.resolve("audit-2026-05-03.jsonl")
        val day2 = dir.resolve("audit-2026-05-04.jsonl")
        assertTrue(Files.exists(day1) && Files.exists(day2), "both day files should exist")
        assertEquals(1, Files.readAllLines(day1).size)
        assertEquals(1, Files.readAllLines(day2).size)
    }

    @Test
    fun `UD-113 emit swallows IO errors (does not throw past the call site)`() {
        dir = createTempDirectory("audit-")
        // Make the dir read-only by deleting it after init — emit should fail silently.
        val log = AuditLog(dir, profileName = "p1")
        dir.toFile().deleteRecursively()
        // Should NOT throw — sync engine must never be blocked by audit-log failures.
        log.emit("Upload", "/x.txt", result = "success")
    }
}
