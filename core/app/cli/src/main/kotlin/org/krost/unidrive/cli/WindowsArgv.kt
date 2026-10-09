package org.krost.unidrive.cli

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.nio.charset.Charset

/**
 * #487: on Windows the JVM's launcher hands `main` its arguments converted to the ANSI code page, so every character
 * outside it arrives as `?` (or a best-fit look-alike): `ls --live /_INBOX/Русский` asked for `/_INBOX/???????`. The
 * loss happens before `main`; the arguments themselves cannot be repaired. The process's own command line still holds
 * them in UTF-16, so they are read back from there: `GetCommandLineW`, split by `CommandLineToArgvW`, and the trailing
 * entries taken (the JVM's own options and the jar come first).
 *
 * The recovered arguments are used only when they provably are the ones the JVM received: same count, same length in
 * UTF-16 units, and every unit the ANSI code page can represent identical (only the units it cannot represent may
 * differ). Anything else — another launcher's quoting, a code page with multi-byte characters, a failing native call —
 * keeps the arguments as they arrived, which is today's behaviour.
 *
 * #528: there is deliberately NO cheap early return on "all characters are ASCII" — the degraded form of a character
 * outside the code page IS ASCII (`?`), so an argument made only of emoji or CJK characters arrives all-ASCII and is
 * exactly the case the recovery exists for. The `pickTrailing` verification is what proves a match; the native read is
 * bounded (one GetCommandLineW round-trip per invocation).
 */
object WindowsArgv {
    fun recover(args: Array<String>): Array<String> {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return args
        val full =
            try {
                nativeCommandLine()
            } catch (e: Throwable) {
                null // no native access (flag missing, older runtime): keep what arrived
            } ?: return args
        return pickTrailing(full, args, ansiCharset()) ?: args
    }

    /** The trailing [received].size entries of [full], when each is the one the JVM degraded into [received]. */
    internal fun pickTrailing(
        full: List<String>,
        received: Array<String>,
        ansi: Charset,
    ): Array<String>? {
        if (full.size < received.size) return null
        val tail = full.subList(full.size - received.size, full.size)
        val encoder = ansi.newEncoder()
        for (i in received.indices) {
            val original = tail[i]
            val got = received[i]
            if (original.length != got.length) return null
            for (k in original.indices) {
                val c = original[k]
                if (c == got[k]) continue
                // A unit the code page can represent must have come through unchanged; one it cannot (a surrogate, a
                // character outside it) arrives as '?' or a best-fit look-alike, which is exactly the loss being repaired.
                if (!c.isSurrogate() && encoder.canEncode(c)) return null
            }
        }
        return tail.toTypedArray()
    }

    private fun ansiCharset(): Charset =
        listOf("sun.jnu.encoding", "native.encoding")
            .firstNotNullOfOrNull { key -> System.getProperty(key)?.let { runCatching { Charset.forName(it) }.getOrNull() } }
            ?: Charset.defaultCharset()

    private fun nativeCommandLine(): List<String>? {
        val linker = Linker.nativeLinker()
        Arena.ofConfined().use { arena ->
            val kernel32 = SymbolLookup.libraryLookup("kernel32", arena)
            val shell32 = SymbolLookup.libraryLookup("shell32", arena)
            val getCommandLine =
                linker.downcallHandle(kernel32.find("GetCommandLineW").orElseThrow(), FunctionDescriptor.of(ValueLayout.ADDRESS))
            val toArgv =
                linker.downcallHandle(
                    shell32.find("CommandLineToArgvW").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
                )
            val localFree =
                linker.downcallHandle(kernel32.find("LocalFree").orElseThrow(), FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS))

            val commandLine = getCommandLine.invoke() as MemorySegment
            val count = arena.allocate(ValueLayout.JAVA_INT)
            val argv = toArgv.invoke(commandLine, count) as MemorySegment
            if (argv.address() == 0L) return null
            try {
                val n = count.get(ValueLayout.JAVA_INT, 0)
                val pointers = argv.reinterpret(n.toLong() * ValueLayout.ADDRESS.byteSize())
                return (0 until n).map { i -> readWide(pointers.getAtIndex(ValueLayout.ADDRESS, i.toLong())) }
            } finally {
                localFree.invoke(argv)
            }
        }
    }

    // A NUL-terminated UTF-16 string. Bounded: a command line is at most 32,767 characters.
    private fun readWide(pointer: MemorySegment): String {
        val segment = pointer.reinterpret(MAX_COMMAND_LINE_CHARS * 2L)
        val sb = StringBuilder()
        for (i in 0 until MAX_COMMAND_LINE_CHARS) {
            val c = segment.get(ValueLayout.JAVA_CHAR_UNALIGNED, i * 2L)
            if (c == '\u0000') break
            sb.append(c)
        }
        return sb.toString()
    }

    private const val MAX_COMMAND_LINE_CHARS = 32_767
}
