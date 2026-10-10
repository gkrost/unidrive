package org.krost.unidrive.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.krost.unidrive.BeginAuthResult
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.CompleteAuthResult
import org.krost.unidrive.ProviderFactory
import org.krost.unidrive.ProviderMetadata
import org.krost.unidrive.ProviderRegistry
import org.krost.unidrive.internxt.BadCredentialsException
import org.krost.unidrive.internxt.InternxtConfig
import org.krost.unidrive.internxt.TfaInvalidException
import org.krost.unidrive.internxt.TfaRequiredException
import org.krost.unidrive.internxt.model.InternxtCredentials
import org.krost.unidrive.io.OwnerOnly
import org.krost.unidrive.sync.ProcessLock
import picocli.CommandLine
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `auth begin`, `auth complete` and `auth login`: the console-free halves of `auth`. Every test drives
 * the real command line against a temp config directory, with the provider calls replaced by fakes
 * through [LifecycleHooks] (no network, no real account).
 */
class AuthLifecycleTest {
    private lateinit var dir: Path
    private val out = ByteArrayOutputStream()
    private val err = ByteArrayOutputStream()
    private val originalOut = System.out
    private val originalErr = System.err
    private val originalIn = System.`in`

    /** A device-code provider whose poll results are scripted. */
    private class DeviceFactory(
        val polls: ArrayDeque<CompleteAuthResult>,
    ) : ProviderFactory {
        override val id = "onedrive"
        override val metadata = ProviderMetadata("onedrive", "Fake", "test", "oauth", "none", "x", true, false, null, "Test")
        val completedWith = mutableListOf<String>()
        var beganIn: Path? = null

        override fun create(
            properties: Map<String, String?>,
            tokenPath: Path,
        ): CloudProvider = throw UnsupportedOperationException()

        override fun isAuthenticated(
            properties: Map<String, String?>,
            profileDir: Path,
        ) = false

        override fun supportsInteractiveAuth() = true

        override suspend fun beginInteractiveAuth(profileDir: Path): BeginAuthResult {
            beganIn = profileDir
            return BeginAuthResult.of(
                continuationHandle = "handle-1",
                fields =
                    linkedMapOf(
                        "verification_uri" to JsonPrimitive("https://example.test/device"),
                        "user_code" to JsonPrimitive("ABCD-1234"),
                        "interval_seconds" to JsonPrimitive(5),
                        "expires_in" to JsonPrimitive(900),
                        "message" to JsonPrimitive("Enter the code"),
                    ),
                expiresAt = Instant.parse("2030-01-01T00:00:00Z"),
                retryAfterSeconds = 5,
            )
        }

        override suspend fun completeInteractiveAuth(
            profileDir: Path,
            continuationHandle: String,
        ): CompleteAuthResult {
            completedWith += continuationHandle
            return polls.removeFirst()
        }
    }

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("auth-lifecycle")
        System.setOut(PrintStream(out, true, Charsets.UTF_8))
        System.setErr(PrintStream(err, true, Charsets.UTF_8))
        Files.writeString(
            dir.resolve("config.toml"),
            "[general]\ndefault_profile = \"od\"\n\n[providers.od]\ntype = \"onedrive\"\nmode = \"mount\"\n\n" +
                "[providers.ix]\ntype = \"internxt\"\nmode = \"mount\"\n\n[providers.lo]\ntype = \"localfs\"\nmode = \"mount\"\nroot_path = \"/x\"\n",
        )
    }

    @AfterTest
    fun tearDown() {
        System.setOut(originalOut)
        System.setErr(originalErr)
        System.setIn(originalIn)
        LifecycleHooks.factoryFor = { ProviderRegistry.get(it) }
        LifecycleHooks.sleepSeconds = { Thread.sleep(it * 1000) }
        dir.toFile().deleteRecursively()
    }

    private fun run(
        vararg args: String,
        stdin: String = "",
    ): Int {
        out.reset()
        err.reset()
        System.setIn(ByteArrayInputStream(stdin.toByteArray(Charsets.UTF_8)))
        val code = CommandLine(Main()).execute("-c", dir.toString(), *args)
        System.out.flush()
        System.err.flush()
        return code
    }

    private fun json(): JsonObject {
        val text = out.toString(Charsets.UTF_8).trim()
        assertEquals(1, text.lines().size, "stdout must be a single line of JSON, was: $text")
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun errorToken(): String {
        val r = json()
        assertFalse(r.getValue("ok").jsonPrimitive.boolean)
        return r.getValue("error").jsonPrimitive.content
    }

    private fun everything() = out.toString(Charsets.UTF_8) + err.toString(Charsets.UTF_8)

    // -- auth begin / complete -------------------------------------------------------

    @Test
    fun `begin returns the code, the page and the handle, and keeps the profile folder owner-only`() {
        val factory = DeviceFactory(ArrayDeque())
        LifecycleHooks.factoryFor = { factory }

        assertEquals(0, run("auth", "begin", "--json"), err.toString())

        val r = json()
        assertTrue(r.getValue("ok").jsonPrimitive.boolean)
        assertEquals("od", r.getValue("profile").jsonPrimitive.content, "the default profile")
        assertEquals("handle-1", r.getValue("handle").jsonPrimitive.content)
        assertEquals("ABCD-1234", r.getValue("user_code").jsonPrimitive.content)
        assertEquals("https://example.test/device", r.getValue("verification_uri").jsonPrimitive.content)
        assertEquals(900L, r.getValue("expires_in").jsonPrimitive.long)
        assertEquals(5L, r.getValue("interval_seconds").jsonPrimitive.long)
        assertEquals("2030-01-01T00:00:00Z", r.getValue("expires_at").jsonPrimitive.content)
        assertEquals(dir.resolve("od"), factory.beganIn)
        // The folder was made owner-only by begin itself: re-applying changes nothing (the lock file the
        // command leaves inside is a descendant, which a re-application would also touch, so remove it).
        Files.deleteIfExists(dir.resolve("od").resolve(".lock"))
        val outcome = OwnerOnly.restrictDirectory(dir.resolve("od"))
        assertTrue(outcome is OwnerOnly.Outcome.Unchanged || outcome is OwnerOnly.Outcome.Unsupported, "$outcome")
    }

    @Test
    fun `begin takes the profile from --profile or from -p`() {
        val factory = DeviceFactory(ArrayDeque())
        LifecycleHooks.factoryFor = { factory }
        Files.writeString(
            dir.resolve("config.toml"),
            Files.readString(dir.resolve("config.toml")) + "\n[providers.od2]\ntype = \"onedrive\"\nmode = \"mount\"\n",
        )

        assertEquals(0, run("auth", "begin", "--profile", "od2", "--json"))
        assertEquals("od2", json().getValue("profile").jsonPrimitive.content)

        assertEquals(0, run("-p", "od2", "auth", "begin", "--json"))
        assertEquals("od2", json().getValue("profile").jsonPrimitive.content)
    }

    @Test
    fun `complete reports pending, then authenticated`() {
        val factory = DeviceFactory(ArrayDeque(listOf(CompleteAuthResult.Pending(5), CompleteAuthResult.Success)))
        LifecycleHooks.factoryFor = { factory }

        assertEquals(0, run("auth", "complete", "--handle", "handle-1", "--json"))
        val pending = json()
        assertTrue(pending.getValue("ok").jsonPrimitive.boolean)
        assertEquals("pending", pending.getValue("status").jsonPrimitive.content)
        assertEquals(5L, pending.getValue("retry_after_seconds").jsonPrimitive.long)

        assertEquals(0, run("auth", "complete", "--handle", "handle-1", "--json"))
        assertEquals("authenticated", json().getValue("status").jsonPrimitive.content)
        assertEquals(listOf("handle-1", "handle-1"), factory.completedWith)
    }

    @Test
    fun `complete --wait polls until the end, sleeping the provider's interval`() {
        val factory =
            DeviceFactory(ArrayDeque(listOf(CompleteAuthResult.Pending(7), CompleteAuthResult.Pending(0), CompleteAuthResult.Success)))
        LifecycleHooks.factoryFor = { factory }
        val slept = mutableListOf<Long>()
        LifecycleHooks.sleepSeconds = { slept += it }

        assertEquals(0, run("auth", "complete", "--handle", "h", "--wait", "--json"))

        assertEquals("authenticated", json().getValue("status").jsonPrimitive.content)
        assertEquals(listOf(7L, 1L), slept, "never a busy loop: at least one second between polls")
    }

    @Test
    fun `complete reports a failed or expired sign-in as an error`() {
        LifecycleHooks.factoryFor = { DeviceFactory(ArrayDeque(listOf(CompleteAuthResult.Failure("Device code expired. Call auth_begin again.")))) }

        assertEquals(1, run("auth", "complete", "--handle", "h", "--json"))

        assertEquals("auth_failed", errorToken())
        assertTrue("expired" in json().getValue("message").jsonPrimitive.content)
    }

    @Test
    fun `complete without a handle is a usage error, not a guess`() {
        LifecycleHooks.factoryFor = { DeviceFactory(ArrayDeque()) }
        assertEquals(2, run("auth", "complete", "--json"))
    }

    @Test
    fun `a profile without a device-code flow, an unknown profile and a missing config are refused`() {
        assertEquals(1, run("auth", "begin", "--profile", "lo", "--json"))
        assertEquals("no_device_code_flow", errorToken())

        assertEquals(1, run("auth", "begin", "--profile", "nope", "--json"))
        assertEquals("profile_not_found", errorToken())

        assertEquals(1, run("auth", "begin", "--profile", "../x", "--json"))
        assertEquals("invalid_profile_name", errorToken())

        Files.delete(dir.resolve("config.toml"))
        assertEquals(1, run("auth", "begin", "--json"))
        assertEquals("config_not_found", errorToken())
    }

    @Test
    fun `a profile held by a running process is profile_in_use, not an exit`() {
        LifecycleHooks.factoryFor = { DeviceFactory(ArrayDeque()) }
        Files.createDirectories(dir.resolve("od"))
        val holder = ProcessLock(dir.resolve("od").resolve(".lock"))
        assertTrue(holder.tryLock(ProcessLock.Mode.DAEMON))
        try {
            assertEquals(1, run("auth", "begin", "--json"))
            assertEquals("profile_in_use", errorToken())
            assertTrue("daemon" in json().getValue("message").jsonPrimitive.content)
        } finally {
            holder.unlock()
        }
    }

    @Test
    fun `a failing provider call is begin_failed and quotes nothing from the exception`() {
        LifecycleHooks.factoryFor = {
            object : ProviderFactory by DeviceFactory(ArrayDeque()) {
                override suspend fun beginInteractiveAuth(profileDir: Path): BeginAuthResult = throw IOException("client_secret=SHHH")

                override fun supportsInteractiveAuth() = true
            }
        }

        assertEquals(1, run("auth", "begin", "--json"))

        assertEquals("begin_failed", errorToken())
        assertFalse("SHHH" in everything())
    }

    // -- auth login ------------------------------------------------------------------

    private class LoginCall(
        val config: InternxtConfig,
        val email: String,
        val password: String,
        val tfa: String?,
    )

    private fun fakeLogin(
        calls: MutableList<LoginCall>,
        result: () -> Unit = {},
    ) {
        LifecycleHooks.internxtLogin = { config, email, password, tfa ->
            calls += LoginCall(config, email, password, tfa)
            result()
            InternxtCredentials(jwt = "JWT-SECRET", mnemonic = "MNEMONIC-SECRET", rootFolderId = "r", email = email, bridgeUser = email, bridgeUserId = "u", bucket = "b")
        }
    }

    @Test
    fun `login reads the password from stdin and never echoes it or the credentials`() {
        val calls = mutableListOf<LoginCall>()
        fakeLogin(calls)

        val code = run("auth", "login", "--profile", "ix", "--email", "me@example.test", "--password-stdin", "--totp", "123456", "--json", stdin = " pass word 1 \r\nsecond line\n")

        assertEquals(0, code, err.toString())
        val r = json()
        assertEquals("authenticated", r.getValue("status").jsonPrimitive.content)
        assertEquals("ix", r.getValue("profile").jsonPrimitive.content)
        assertEquals("me@example.test", r.getValue("account").jsonPrimitive.content)
        val call = calls.single()
        assertEquals(" pass word 1 ", call.password, "first line only, the line terminator removed, spaces kept")
        assertEquals("123456", call.tfa)
        assertEquals(dir.resolve("ix"), call.config.tokenPath)
        val seen = everything()
        for (secret in listOf("pass word 1", "123456", "JWT-SECRET", "MNEMONIC-SECRET")) {
            assertFalse(secret in seen, "'$secret' must not appear in stdout or stderr")
        }
    }

    @Test
    fun `login without a totp sends none`() {
        val calls = mutableListOf<LoginCall>()
        fakeLogin(calls)

        assertEquals(0, run("auth", "login", "--profile", "ix", "--email", "a@b.test", "--password-stdin", "--json", stdin = "pw\n"))

        assertEquals(null, calls.single().tfa)
    }

    @Test
    fun `login maps the provider's failures to tokens without echoing their messages`() {
        val cases =
            listOf<Pair<() -> Nothing, String>>(
                { throw TfaRequiredException("server says: pw=SHHH") } to "tfa_required",
                { throw TfaInvalidException("Invalid 2FA code: SHHH") } to "tfa_invalid",
                { throw BadCredentialsException("Wrong password: SHHH") } to "bad_credentials",
                { throw org.krost.unidrive.AuthenticationException("Access failed: SHHH") } to "auth_failed",
                { throw IOException("timeout SHHH") } to "network_error",
                { throw IllegalStateException("SHHH") } to "login_failed",
            )
        for ((fail, token) in cases) {
            fakeLogin(mutableListOf()) { fail() }
            assertEquals(1, run("auth", "login", "--profile", "ix", "--email", "a@b.test", "--password-stdin", "--json", stdin = "pw\n"), token)
            assertEquals(token, errorToken())
            assertFalse("SHHH" in everything(), "$token must not echo the provider's message")
        }
    }

    @Test
    fun `login refuses a missing password flag, an empty password, another provider and an argument password`() {
        val calls = mutableListOf<LoginCall>()
        fakeLogin(calls)

        assertEquals(1, run("auth", "login", "--profile", "ix", "--email", "a@b.test", "--json", stdin = "pw\n"))
        assertEquals("password_stdin_required", errorToken())

        assertEquals(1, run("auth", "login", "--profile", "ix", "--email", "a@b.test", "--password-stdin", "--json", stdin = "\n"))
        assertEquals("empty_password", errorToken())

        assertEquals(1, run("auth", "login", "--profile", "ix", "--email", " ", "--password-stdin", "--json", stdin = "pw\n"))
        assertEquals("missing_email", errorToken())

        assertEquals(1, run("auth", "login", "--profile", "od", "--email", "a@b.test", "--password-stdin", "--json", stdin = "pw\n"))
        assertEquals("login_not_supported", errorToken())

        // There is no --password option at all: a password cannot be put in an argument list.
        assertEquals(2, run("auth", "login", "--profile", "ix", "--email", "a@b.test", "--password", "SHHH", "--json"))
        assertFalse("SHHH" in out.toString(Charsets.UTF_8))

        assertTrue(calls.isEmpty(), "no refused call reached the provider")
    }

    @Test
    fun `the existing console auth is still the command's own action`() {
        val spec = CommandLine(Main()).subcommands.getValue("auth")
        assertEquals(setOf("begin", "complete", "login"), spec.subcommands.keys)
        assertTrue(spec.commandSpec.findOption("--device-code") != null, "-d/--device-code is unchanged")
    }
}
