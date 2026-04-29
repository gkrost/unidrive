package org.krost.unidrive.cli

import picocli.CommandLine
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * UD-245: regression tests for parse-time rejection of nonsensical numeric
 * flag values. Every invalid value in the ticket's acceptance matrix must
 * produce exit code 2 with a clear, flag-specific error message.
 */
class NumericFlagValidationTest {
    private fun run(vararg args: String): Pair<Int, String> {
        val cmd = CommandLine(Main())
        val errBuf = StringWriter()
        val outBuf = StringWriter()
        cmd.err = PrintWriter(errBuf)
        cmd.out = PrintWriter(outBuf)
        val exit = cmd.execute(*args)
        return exit to (errBuf.toString() + outBuf.toString())
    }

    // ── conflicts list -n ─────────────────────────────────────────────────────

    @Test
    fun `conflicts list rejects -n zero`() {
        val (exit, err) = run("conflicts", "list", "-n", "0")
        assertEquals(2, exit, "exit code should be 2 (parse error)")
        assertTrue(err.contains("--limit must be > 0"), "error should name --limit: $err")
        assertTrue(err.contains("got 0"), "error should echo offending value: $err")
    }

    @Test
    fun `conflicts list rejects -n negative`() {
        val (exit, err) = run("conflicts", "list", "-n", "-5")
        assertEquals(2, exit)
        assertTrue(err.contains("--limit must be > 0"), err)
    }

    @Test
    fun `conflicts list rejects -n Int MIN`() {
        val (exit, err) = run("conflicts", "list", "-n", Int.MIN_VALUE.toString())
        assertEquals(2, exit)
        assertTrue(err.contains("--limit must be > 0"), err)
    }

    // ── trash purge --retention-days ─────────────────────────────────────────

    @Test
    fun `trash purge rejects negative retention-days`() {
        val (exit, err) = run("trash", "purge", "--retention-days", "-1")
        assertEquals(2, exit)
        assertTrue(err.contains("--retention-days must be >= 0"), err)
    }

    @Test
    fun `trash purge rejects Int MIN retention-days`() {
        val (exit, err) = run("trash", "purge", "--retention-days", Int.MIN_VALUE.toString())
        assertEquals(2, exit)
        assertTrue(err.contains("--retention-days must be >= 0"), err)
    }

    // ── versions purge --retention-days ──────────────────────────────────────

    @Test
    fun `versions purge rejects negative retention-days`() {
        val (exit, err) = run("versions", "purge", "--retention-days", "-1")
        assertEquals(2, exit)
        assertTrue(err.contains("--retention-days must be >= 0"), err)
    }

    // ── get --concurrency / --delay-ms ───────────────────────────────────────

    @Test
    fun `get rejects concurrency zero`() {
        val (exit, err) = run("get", "/some/path", "--concurrency", "0")
        assertEquals(2, exit)
        assertTrue(err.contains("--concurrency must be > 0"), err)
    }

    @Test
    fun `get rejects negative concurrency`() {
        val (exit, err) = run("get", "/some/path", "--concurrency", "-5")
        assertEquals(2, exit)
        assertTrue(err.contains("--concurrency must be > 0"), err)
    }

    @Test
    fun `get rejects negative delay-ms`() {
        val (exit, err) = run("get", "/some/path", "--delay-ms", "-1")
        assertEquals(2, exit)
        assertTrue(err.contains("--delay-ms must be >= 0"), err)
    }

    // ── share --expiry ───────────────────────────────────────────────────────

    @Test
    fun `share rejects expiry zero`() {
        val (exit, err) = run("share", "/some/path", "--expiry", "0")
        assertEquals(2, exit)
        assertTrue(err.contains("--expiry must be > 0"), err)
    }

    @Test
    fun `share rejects negative expiry`() {
        val (exit, err) = run("share", "/some/path", "--expiry", "-5")
        assertEquals(2, exit)
        assertTrue(err.contains("--expiry must be > 0"), err)
    }

    // ── backup add --name ────────────────────────────────────────────────────

    @Test
    fun `backup add rejects empty name`() {
        val (exit, err) = run("backup", "add", "--name", "", "--provider", "s3")
        assertEquals(2, exit)
        assertTrue(err.contains("--name must not be empty"), err)
    }

    @Test
    fun `backup add rejects name with slash`() {
        val (exit, err) = run("backup", "add", "--name", "foo/bar", "--provider", "s3")
        assertEquals(2, exit)
        assertTrue(err.contains("--name must not contain"), err)
    }

    // ── pin / unpin empty glob ───────────────────────────────────────────────

    @Test
    fun `pin rejects empty glob`() {
        val (exit, err) = run("pin", "")
        assertEquals(2, exit)
        assertTrue(err.contains("glob pattern must not be empty"), err)
    }

    @Test
    fun `unpin rejects empty glob`() {
        val (exit, err) = run("unpin", "")
        assertEquals(2, exit)
        assertTrue(err.contains("glob pattern must not be empty"), err)
    }
}
