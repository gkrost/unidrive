package org.krost.unidrive.cli

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.CompleteAuthResult
import org.krost.unidrive.internxt.BadCredentialsException
import org.krost.unidrive.internxt.InternxtConfig
import org.krost.unidrive.internxt.TfaInvalidException
import org.krost.unidrive.internxt.TfaRequiredException
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.ParentCommand
import java.io.IOException
import java.util.concurrent.Callable

/*
 * The non-interactive halves of `unidrive auth`, for scripts and front-ends: JSON on stdout, no
 * console, no prompt, no daemon started. Secrets never travel through the argument list: the
 * device-code flow needs none (the user signs in on the provider's page), and the Internxt password
 * is read from standard input only. Nothing here prints, logs or returns a password, a TOTP code,
 * a device code or a token.
 */

private fun reply(
    json: Boolean,
    block: () -> JsonObject,
): Int =
    try {
        val result = block()
        if (json) {
            emitJson(result)
        } else {
            println(result.toString())
        }
        0
    } catch (e: LifecycleError) {
        if (json) emitJson(lifecycleErrorJson(e)) else System.err.println("Error: ${e.message}")
        1
    } catch (e: Exception) {
        // A front-end parses stdout: whatever else goes wrong is still one JSON refusal, with the
        // exception's type only (its message could quote a response or a path).
        val refusal = LifecycleError("internal_error", "unexpected failure: ${e.javaClass.simpleName}")
        if (json) emitJson(lifecycleErrorJson(refusal)) else System.err.println("Error: ${refusal.message}")
        1
    }

// ── auth begin ───────────────────────────────────────────────────────────────

@Command(
    name = "begin",
    description = [
        "Start a device-code sign-in (OneDrive): prints the code and the page to enter it on. " +
            "Finish with 'auth complete --handle ...'. Starts no daemon.",
    ],
    mixinStandardHelpOptions = true,
)
class AuthBeginCommand : Callable<Int> {
    @ParentCommand
    lateinit var authCmd: AuthCommand

    @Option(names = ["--profile"], description = ["Profile (default: -p, else the default profile)"])
    var profile: String? = null

    @Option(names = ["--json"], description = ["Print the result (or the refusal) as one JSON object on stdout"])
    var json: Boolean = false

    override fun call(): Int =
        reply(json) {
            val target = resolveProfileTarget(authCmd.parent, profile)
            val factory =
                LifecycleHooks.factoryFor(target.type)?.takeIf { it.supportsInteractiveAuth() }
                    ?: throw LifecycleError(
                        "no_device_code_flow",
                        "profile '${target.name}' (type ${target.type}) has no device-code sign-in; " +
                            "for Internxt use 'auth login --password-stdin'",
                    )
            val begun =
                withProfileLock(target) {
                    try {
                        runBlocking { factory.beginInteractiveAuth(target.profileDir) }
                    } catch (e: IOException) {
                        throw LifecycleError("begin_failed", "the sign-in could not be started: ${e.javaClass.simpleName}")
                    } catch (e: Exception) {
                        throw LifecycleError("begin_failed", "the sign-in could not be started: ${e.javaClass.simpleName}")
                    }
                }
            buildJsonObject {
                put("ok", true)
                put("profile", target.name)
                put("handle", begun.continuationHandle)
                // Provider-supplied fields (user_code, verification_uri, expires_in, interval_seconds, message).
                for ((k, v) in begun.fields) put(k, v)
                put("expires_at", begun.expiresAt.toString())
            }
        }
}

// ── auth complete ────────────────────────────────────────────────────────────

@Command(
    name = "complete",
    description = [
        "Finish a device-code sign-in begun with 'auth begin': one poll (status authenticated or pending), " +
            "or with --wait until it ends. Starts no daemon.",
    ],
    mixinStandardHelpOptions = true,
)
class AuthCompleteCommand : Callable<Int> {
    @ParentCommand
    lateinit var authCmd: AuthCommand

    @Option(names = ["--profile"], description = ["Profile (default: -p, else the default profile)"])
    var profile: String? = null

    @Option(names = ["--handle"], required = true, description = ["The handle 'auth begin' returned"])
    lateinit var handle: String

    @Option(names = ["--wait"], description = ["Keep polling until the sign-in succeeds, fails or expires"])
    var wait: Boolean = false

    @Option(names = ["--json"], description = ["Print the result (or the refusal) as one JSON object on stdout"])
    var json: Boolean = false

    override fun call(): Int =
        reply(json) {
            val target = resolveProfileTarget(authCmd.parent, profile)
            val factory =
                LifecycleHooks.factoryFor(target.type)?.takeIf { it.supportsInteractiveAuth() }
                    ?: throw LifecycleError("no_device_code_flow", "profile '${target.name}' (type ${target.type}) has no device-code sign-in")
            withProfileLock(target) {
                fun pollOnce(): CompleteAuthResult =
                    try {
                        runBlocking { factory.completeInteractiveAuth(target.profileDir, handle) }
                    } catch (e: Exception) {
                        throw LifecycleError("complete_failed", "the sign-in could not be completed: ${e.javaClass.simpleName}")
                    }
                var outcome = pollOnce()
                while (wait && outcome is CompleteAuthResult.Pending) {
                    LifecycleHooks.sleepSeconds(maxOf(1L, outcome.retryAfterSeconds))
                    outcome = pollOnce()
                }
                when (val o = outcome) {
                    is CompleteAuthResult.Success ->
                        buildJsonObject {
                            put("ok", true)
                            put("profile", target.name)
                            put("status", "authenticated")
                        }
                    is CompleteAuthResult.Pending ->
                        buildJsonObject {
                            put("ok", true)
                            put("profile", target.name)
                            put("status", "pending")
                            put("retry_after_seconds", o.retryAfterSeconds)
                        }
                    is CompleteAuthResult.Failure -> throw LifecycleError("auth_failed", o.message)
                }
            }
        }
}

// ── auth login (Internxt) ────────────────────────────────────────────────────

@Command(
    name = "login",
    description = [
        "Sign in to Internxt without a console. The password is read from standard input (first line), " +
            "never from an argument. Starts no daemon.",
    ],
    mixinStandardHelpOptions = true,
)
class AuthLoginCommand : Callable<Int> {
    @ParentCommand
    lateinit var authCmd: AuthCommand

    @Option(names = ["--profile"], description = ["Profile (default: -p, else the default profile)"])
    var profile: String? = null

    @Option(names = ["--email"], required = true, description = ["Account e-mail"])
    lateinit var email: String

    @Option(names = ["--password-stdin"], description = ["Read the password from the first line of standard input (required)"])
    var passwordStdin: Boolean = false

    @Option(names = ["--totp"], description = ["Current two-factor code, when the account has one"])
    var totp: String? = null

    @Option(names = ["--json"], description = ["Print the result (or the refusal) as one JSON object on stdout"])
    var json: Boolean = false

    override fun call(): Int =
        reply(json) {
            if (!passwordStdin) {
                throw LifecycleError("password_stdin_required", "pass --password-stdin and give the password on standard input")
            }
            if (email.isBlank()) throw LifecycleError("missing_email", "--email is required")
            val target = resolveProfileTarget(authCmd.parent, profile)
            if (target.type != "internxt") {
                throw LifecycleError("login_not_supported", "'auth login' is for Internxt profiles; profile '${target.name}' is ${target.type}")
            }
            val password = readPasswordLine()
            if (password.isEmpty()) throw LifecycleError("empty_password", "no password on standard input")

            withProfileLock(target) {
                try {
                    runBlocking {
                        LifecycleHooks.internxtLogin(InternxtConfig(tokenPath = target.profileDir), email.trim(), password, totp?.trim()?.takeIf { it.isNotEmpty() })
                    }
                } catch (_: TfaRequiredException) {
                    throw LifecycleError("tfa_required", "this account needs a two-factor code; repeat with --totp")
                } catch (_: TfaInvalidException) {
                    throw LifecycleError("tfa_invalid", "the two-factor code was rejected")
                } catch (_: BadCredentialsException) {
                    throw LifecycleError("bad_credentials", "wrong e-mail or password")
                } catch (_: AuthenticationException) {
                    throw LifecycleError("auth_failed", "the provider refused the sign-in")
                } catch (e: IOException) {
                    throw LifecycleError("network_error", "the provider could not be reached: ${e.javaClass.simpleName}")
                } catch (e: Exception) {
                    throw LifecycleError("login_failed", "the sign-in failed: ${e.javaClass.simpleName}")
                }
                buildJsonObject {
                    put("ok", true)
                    put("profile", target.name)
                    put("status", "authenticated")
                    put("account", email.trim())
                }
            }
        }

    /** The first line of standard input without its line terminator; spaces in the password are kept. */
    private fun readPasswordLine(): String {
        val bytes = java.io.ByteArrayOutputStream()
        while (true) {
            val b = System.`in`.read()
            if (b < 0 || b == '\n'.code) break
            bytes.write(b)
        }
        return bytes.toString(Charsets.UTF_8).removeSuffix("\r")
    }
}
