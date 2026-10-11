package org.krost.unidrive.internxt

import org.krost.unidrive.auth.CredentialData
import org.krost.unidrive.auth.CredentialProtectionException
import org.krost.unidrive.auth.CredentialProtector
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Linux Secret Service backend, driven through libsecret's `secret-tool` CLI. */
class LinuxSecretServiceCredentialProtector(
    private val executable: String = "secret-tool",
    private val timeoutSeconds: Long = 10,
) : CredentialProtector {
    override fun protect(id: String, plaintext: String): String {
        val key = keyFor(id)
        val revision = UUID.randomUUID().toString()
        run(storeArgs(key, revision), plaintext)
        val retrieved = lookup(key, revision)
        if (retrieved != plaintext) {
            clear(key, revision)
            throw CredentialProtectionException("Linux Secret Service did not verify stored credentials")
        }
        return "$STORED_PREFIX$revision"
    }

    override fun decode(id: String, stored: String): CredentialData {
        if (!stored.startsWith(STORED_PREFIX)) return CredentialData(stored, false)
        val revision = stored.removePrefix(STORED_PREFIX)
        if (runCatching { UUID.fromString(revision) }.isFailure) {
            throw CredentialProtectionException("Internxt credential reference is invalid; refusing plaintext fallback")
        }
        return CredentialData(lookup(keyFor(id), revision), true)
    }

    override fun delete(id: String) {
        run(listOf("clear", "application", "unidrive", "credential", keyFor(id)), "")
    }

    override fun discard(id: String, reference: String) {
        if (!reference.startsWith(STORED_PREFIX)) return
        val revision = reference.removePrefix(STORED_PREFIX)
        if (runCatching { UUID.fromString(revision) }.isSuccess) clear(keyFor(id), revision)
    }

    private fun lookup(key: String, revision: String): String =
        run(listOf("lookup", "application", "unidrive", "credential", key, "revision", revision), "")
            .trimEnd('\n', '\r')

    private fun storeArgs(key: String, revision: String): List<String> =
        listOf(
            "store",
            "--label=Unidrive provider credentials",
            "application",
            "unidrive",
            "credential",
            key,
            "revision",
            revision,
        )

    private fun clear(key: String, revision: String) {
        run(listOf("clear", "application", "unidrive", "credential", key, "revision", revision), "")
    }

    private fun run(
        args: List<String>,
        stdin: String,
    ): String {
        val process =
            runCatching { ProcessBuilder(listOf(executable) + args).start() }
                .getOrElse { processFailure(it) }
        val output = CompletableFuture.supplyAsync {
            process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        }
        val errorOutput = CompletableFuture.supplyAsync {
            process.errorStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        }
        val finished =
            runCatching {
                process.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                    if (stdin.isNotEmpty()) writer.write(stdin)
                }
                process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            }.getOrElse {
                stop(process, output, errorOutput)
                processFailure(it)
            }
        if (!finished) {
            stop(process, output, errorOutput)
            processFailure(IllegalStateException("Linux Secret Service timed out; unlock the user keyring and retry"))
        }
        val response =
            runCatching { output.get(timeoutSeconds, TimeUnit.SECONDS) }.getOrElse {
                stop(process, output, errorOutput)
                processFailure(it)
            }
        runCatching { errorOutput.get(timeoutSeconds, TimeUnit.SECONDS) }.getOrElse {
            stop(process, output, errorOutput)
            processFailure(it)
        }
        if (process.exitValue() != 0) processFailure(null)
        return response
    }

    private fun stop(
        process: Process,
        output: CompletableFuture<String>,
        errorOutput: CompletableFuture<String>,
    ) {
        process.destroyForcibly()
        process.waitFor(1, TimeUnit.SECONDS)
        joinReader(output)
        joinReader(errorOutput)
    }

    private fun joinReader(reader: CompletableFuture<String>) {
        runCatching { reader.get(1, TimeUnit.SECONDS) }
        if (!reader.isDone) reader.cancel(true)
    }

    private fun processFailure(cause: Throwable?): Nothing =
        if (cause?.message?.contains("timed out") == true) {
            throw CredentialProtectionException("Linux Secret Service timed out; unlock the user keyring and retry", cause)
        } else {
            throw CredentialProtectionException(
                "Linux Secret Service is unavailable or locked; install libsecret-tools, unlock the user keyring, and retry",
                cause,
            )
        }

    private fun keyFor(id: String): String =
        MessageDigest.getInstance("SHA-256").digest(id.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val STORED_PREFIX = "unidrive-secret-service-v1:"
    }
}
