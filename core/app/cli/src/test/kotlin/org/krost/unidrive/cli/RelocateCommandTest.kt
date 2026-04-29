package org.krost.unidrive.cli

import picocli.CommandLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RelocateCommandTest {
    private val cmd = CommandLine(Main())
    private val relocateCmd get() = cmd.subcommands["relocate"]!!

    // -- Command registration ---------------------------------------------------

    @Test
    fun `relocate command is registered`() {
        assertNotNull(cmd.subcommands["relocate"], "relocate subcommand should be registered")
    }

    // -- Option flags -----------------------------------------------------------

    @Test
    fun `--from option is registered and required`() {
        val spec = relocateCmd.commandSpec
        val opt = spec.options().first { "--from" in it.names() }
        assertTrue(opt.required(), "--from must be required")
    }

    @Test
    fun `--from has -f short alias`() {
        val allNames = relocateCmd.commandSpec.options().flatMap { it.names().toList() }
        assertTrue("-f" in allNames)
    }

    @Test
    fun `--to option is registered and required`() {
        val spec = relocateCmd.commandSpec
        val opt = spec.options().first { "--to" in it.names() }
        assertTrue(opt.required(), "--to must be required")
    }

    @Test
    fun `--to has -t short alias`() {
        val allNames = relocateCmd.commandSpec.options().flatMap { it.names().toList() }
        assertTrue("-t" in allNames)
    }

    @Test
    fun `--delete-source flag is registered`() {
        val options = relocateCmd.commandSpec.options().map { it.longestName() }
        assertTrue("--delete-source" in options)
    }

    @Test
    fun `--delete-source defaults to false`() {
        val opt = relocateCmd.commandSpec.options().first { "--delete-source" in it.names() }
        assertEquals(false, opt.defaultValue()?.toBooleanStrictOrNull() ?: false)
    }

    @Test
    fun `--source-path option is registered`() {
        val options = relocateCmd.commandSpec.options().map { it.longestName() }
        assertTrue("--source-path" in options)
    }

    @Test
    fun `--target-path option is registered`() {
        val options = relocateCmd.commandSpec.options().map { it.longestName() }
        assertTrue("--target-path" in options)
    }

    @Test
    fun `--buffer-mb option is registered`() {
        val options = relocateCmd.commandSpec.options().map { it.longestName() }
        assertTrue("--buffer-mb" in options)
    }

    @Test
    fun `--force flag is registered`() {
        val options = relocateCmd.commandSpec.options().map { it.longestName() }
        assertTrue("--force" in options)
    }

    @Test
    fun `--force defaults to false`() {
        val opt = relocateCmd.commandSpec.options().first { "--force" in it.names() }
        assertEquals(false, opt.defaultValue()?.toBooleanStrictOrNull() ?: false)
    }

    // -- formatSize helper ------------------------------------------------------

    @Test
    fun `formatSize formats bytes`() {
        val cmd = RelocateCommand()
        val method = cmd.javaClass.getDeclaredMethod("formatSize", Long::class.java)
        method.isAccessible = true
        assertEquals("0 B", method.invoke(cmd, 0L))
        assertEquals("512 B", method.invoke(cmd, 512L))
    }

    @Test
    fun `formatSize formats kibibytes`() {
        val cmd = RelocateCommand()
        val method = cmd.javaClass.getDeclaredMethod("formatSize", Long::class.java)
        method.isAccessible = true
        assertEquals("1.0 KiB", method.invoke(cmd, 1024L))
        assertEquals("1.5 KiB", method.invoke(cmd, 1536L))
    }

    @Test
    fun `formatSize formats mebibytes`() {
        val cmd = RelocateCommand()
        val method = cmd.javaClass.getDeclaredMethod("formatSize", Long::class.java)
        method.isAccessible = true
        assertEquals("1.0 MiB", method.invoke(cmd, 1024L * 1024))
    }

    @Test
    fun `formatSize formats gibibytes`() {
        val cmd = RelocateCommand()
        val method = cmd.javaClass.getDeclaredMethod("formatSize", Long::class.java)
        method.isAccessible = true
        assertEquals("1.0 GiB", method.invoke(cmd, 1024L * 1024 * 1024))
    }

    @Test
    fun `formatSize formats tebibytes`() {
        val cmd = RelocateCommand()
        val method = cmd.javaClass.getDeclaredMethod("formatSize", Long::class.java)
        method.isAccessible = true
        assertEquals("1.0 TiB", method.invoke(cmd, 1024L * 1024 * 1024 * 1024))
    }
}
