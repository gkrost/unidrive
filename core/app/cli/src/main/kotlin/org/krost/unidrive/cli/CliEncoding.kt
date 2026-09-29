package org.krost.unidrive.cli

import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import java.lang.management.ManagementFactory
import java.nio.charset.Charset

/**
 * #391: the CLI chooses its stdout/stderr charset explicitly instead of
 * inheriting whatever the JVM guessed.
 *
 * On Windows the JVM derives the stream charset from the console code page
 * when attached, and from the platform native encoding when stdout is
 * redirected. Redirected output is what scripts, CI and agents capture, and
 * they decode UTF-8 — so a plan line like `mkdir /_INBOX/Köln …` reached its
 * reader as U+FFFD-mangled bytes whenever no launcher flag happened to force
 * UTF-8 (verified in #391: the same run with `-Dstdout.encoding=UTF-8` was
 * correct, the log file and state.db always were — stdout encoding only).
 *
 * The deploy launchers (unidrive.ps1/cmd, the Git Bash launcher) already pass
 * `-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8` as the belt-and-braces
 * default; [apply] covers every invocation that bypasses them (bare
 * `java -jar`, gradle run, agents spawning the jar directly).
 */
object CliEncoding {
    /**
     * The charset [current] should be: an explicitly configured flag wins
     * (the JVM already applied it at stream creation, so callers rebind only
     * when this differs; a value the JVM rejected falls back to [current]
     * rather than failing startup); attached to a real console the JVM default
     * is that console's code page and rebinding would mangle screen output —
     * keep it (JDK 22+ could refine via `System.console().charset()`; the
     * toolchain is 21). Redirected or piped — the case scripts see — is UTF-8.
     */
    fun charsetFor(
        attachedToConsole: Boolean,
        configured: String?,
        current: Charset,
    ): Charset {
        if (configured != null) {
            return runCatching { Charset.forName(configured) }.getOrElse { current }
        }
        return if (attachedToConsole) current else Charsets.UTF_8
    }

    /**
     * The value of `-D<name>=…` as passed to THIS JVM, or null. The property
     * map alone cannot answer whether a charset was requested:
     * `System.getProperty("stdout.encoding")` is defined by the JVM itself on
     * every start (JDK 19+) — on Windows with redirected output it reads
     * "Cp1252" with no flag anywhere — so honoring the property unconditionally
     * pins the #391 mangling in place. Only a flag on the actual command line
     * counts as a request (launcher invocations, explicit user override).
     */
    internal fun explicitProperty(name: String): String? {
        val flag = "-D$name="
        return ManagementFactory.getRuntimeMXBean().inputArguments
            .firstOrNull { it.startsWith(flag) }
            ?.substring(flag.length)
    }

    /**
     * Rebinds [System.out] and [System.err] where the current charset would
     * mangle output. No-op when the charset is already right (launcher flags,
     * attached console), so calling it unconditionally at startup is safe.
     */
    fun apply() {
        val attached = System.console() != null
        val out = charsetFor(attached, explicitProperty("stdout.encoding"), System.out.charset())
        if (out != System.out.charset()) {
            System.setOut(PrintStream(FileOutputStream(FileDescriptor.out), true, out))
        }
        val err = charsetFor(attached, explicitProperty("stderr.encoding"), System.err.charset())
        if (err != System.err.charset()) {
            System.setErr(PrintStream(FileOutputStream(FileDescriptor.err), true, err))
        }
    }
}
