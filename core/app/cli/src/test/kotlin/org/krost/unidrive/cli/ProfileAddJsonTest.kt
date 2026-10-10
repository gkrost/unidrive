package org.krost.unidrive.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.ProviderFactory
import org.krost.unidrive.ProviderMetadata
import org.krost.unidrive.ProviderRegistry
import org.krost.unidrive.PromptSpec
import org.krost.unidrive.io.OwnerOnly
import org.krost.unidrive.sync.SyncConfig
import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `profile add --type ...`: the non-interactive creation. Every test drives the real command line
 * against a temp config directory with no console, the way a front-end or CI calls it.
 */
class ProfileAddJsonTest {
    private lateinit var dir: Path
    private val out = ByteArrayOutputStream()
    private val err = ByteArrayOutputStream()
    private val originalOut = System.out
    private val originalErr = System.err

    /** A provider type with a secret prompt, to prove secrets are refused by name. */
    private class SecretFactory(
        private val prompts: List<PromptSpec>,
    ) : ProviderFactory {
        override val id = "secretive"
        override val metadata =
            ProviderMetadata("secretive", "Secretive", "test", "key", "none", "x", true, false, null, "Test")

        override fun create(
            properties: Map<String, String?>,
            tokenPath: Path,
        ): CloudProvider = throw UnsupportedOperationException()

        override fun isAuthenticated(
            properties: Map<String, String?>,
            profileDir: Path,
        ) = false

        override fun credentialPrompts() = prompts
    }

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("profile-add-json")
        System.setOut(PrintStream(out, true, Charsets.UTF_8))
        System.setErr(PrintStream(err, true, Charsets.UTF_8))
    }

    @AfterTest
    fun tearDown() {
        System.setOut(originalOut)
        System.setErr(originalErr)
        LifecycleHooks.factoryFor = { ProviderRegistry.get(it) }
        dir.toFile().deleteRecursively()
    }

    private val configFile: Path get() = dir.resolve("config.toml")

    private fun run(vararg args: String): Int {
        out.reset()
        err.reset()
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

    @Test
    fun `adds a mount profile with no console and no prompt`() {
        assertNull0(System.console())

        val code = run("profile", "add", "--type", "localfs", "--name", "docs", "--mode", "mount", "--label", "My \"docs\"", "--option", "root_path=/data/docs", "--json")

        assertEquals(0, code, err.toString())
        val r = json()
        assertTrue(r.getValue("ok").jsonPrimitive.boolean)
        assertEquals("docs", r.getValue("profile").jsonPrimitive.content)
        assertEquals("localfs", r.getValue("type").jsonPrimitive.content)
        assertEquals("mount", r.getValue("mode").jsonPrimitive.content)
        assertEquals("My \"docs\"", r.getValue("label").jsonPrimitive.content)
        assertEquals(dir.resolve("docs").resolve("sync-root").toString(), r.getValue("sync_root").jsonPrimitive.content)
        assertEquals(JsonNull, r.getValue("default_profile"))
        assertEquals(0, r.getValue("warnings").jsonArray.size)
        assertEquals("none", r.getValue("next").jsonPrimitive.content)

        // The config reads back as asked.
        val raw = SyncConfig.parseRaw(Files.readString(configFile)).providers.getValue("docs")
        assertEquals("localfs", raw.type)
        assertEquals("mount", raw.mode)
        assertEquals("/data/docs", raw.root_path)
        assertTrue("label = \"My \\\"docs\\\"\"" in Files.readString(configFile))
        // The mount root is not created; the profile folder is, owner-only.
        assertFalse(Files.exists(dir.resolve("docs").resolve("sync-root")))
        assertTrue(Files.isDirectory(dir.resolve("docs")))
        val outcome = OwnerOnly.restrictDirectory(dir.resolve("docs"))
        assertTrue(outcome is OwnerOnly.Outcome.Unchanged || outcome is OwnerOnly.Outcome.Unsupported, "profile folder not owner-only: $outcome")
    }

    @Test
    fun `adds a mirror profile with its sync root and makes it the default on request`() {
        val root = dir.resolve("mirror-root").toString()

        assertEquals(0, run("profile", "add", "--type", "localfs", "--mode", "mirror", "--sync-root", root, "--option", "root_path=/data/m", "--make-default", "--json"))

        val r = json()
        assertEquals("localfs", r.getValue("profile").jsonPrimitive.content, "the name defaults to the type")
        assertEquals(root, r.getValue("sync_root").jsonPrimitive.content)
        assertEquals("localfs", r.getValue("default_profile").jsonPrimitive.content)
        assertEquals(JsonNull, r.getValue("label"))
        assertEquals("localfs", SyncConfig.parseRaw(Files.readString(configFile)).general.default_profile)
    }

    @Test
    fun `the new profile appends to an existing config and leaves the rest alone`() {
        Files.writeString(configFile, "# mine\n[general]\ndefault_profile = \"a\"\n\n[providers.a]\ntype = \"localfs\"\nmode = \"mirror\"\nroot_path = \"/a\"\nsync_root = \"${dir.resolve("a-root").toString().replace("\\", "/")}\"\n")

        assertEquals(0, run("profile", "add", "--type", "localfs", "--name", "b", "--mode", "mount", "--option", "root_path=/b", "--json"))

        val text = Files.readString(configFile)
        assertTrue(text.startsWith("# mine\n[general]\ndefault_profile = \"a\"\n"))
        assertEquals(setOf("a", "b"), SyncConfig.parseRaw(text).providers.keys)
        assertEquals("a", json().getValue("default_profile").jsonPrimitive.content, "an existing default is not taken over")
    }

    @Test
    fun `an Internxt folder as the mirror root draws a warning, not a refusal`() {
        val root = dir.resolve("InternxtDrive").toString()

        assertEquals(0, run("profile", "add", "--type", "localfs", "--mode", "mirror", "--sync-root", root, "--option", "root_path=/x", "--json"))

        assertEquals(1, json().getValue("warnings").jsonArray.size)
    }

    @Test
    fun `refusals are machine-readable and leave nothing behind`() {
        val base = arrayOf("profile", "add", "--json")
        val cases =
            listOf(
                listOf("--type", "nope", "--mode", "mount") to "unknown_type",
                listOf("--type", "localfs", "--option", "root_path=/x") to "missing_mode",
                listOf("--type", "localfs", "--mode", "both", "--option", "root_path=/x") to "invalid_mode",
                listOf("--type", "localfs", "--mode", "mount", "--name", "a.b", "--option", "root_path=/x") to "invalid_profile_name",
                listOf("--type", "localfs", "--mode", "mount", "--sync-root", "/r", "--option", "root_path=/x") to "sync_root_not_allowed",
                listOf("--type", "localfs", "--mode", "mount") to "missing_option",
                listOf("--type", "localfs", "--mode", "mount", "--option", "root_path=/x", "--option", "colour=blue") to "unknown_option",
                listOf("--type", "localfs", "--mode", "mount", "--option", "root_path") to "invalid_option",
                listOf("--type", "localfs", "--mode", "mount", "--option", "root_path=/x", "--option", "root_path=/y") to "duplicate_option",
            )
        for ((args, token) in cases) {
            assertEquals(1, run(*base, *args.toTypedArray()), "args $args")
            assertEquals(token, errorToken(), "args $args")
        }
        assertFalse(Files.exists(configFile), "a refused creation writes no config")
        assertEquals(0, Files.list(dir).use { it.count() }, "and creates no folder")
    }

    @Test
    fun `a name that exists is refused and an overlapping sync root is refused`() {
        assertEquals(0, run("profile", "add", "--type", "localfs", "--name", "one", "--mode", "mirror", "--sync-root", dir.resolve("r").toString(), "--option", "root_path=/x", "--json"))
        val before = Files.readString(configFile)

        assertEquals(1, run("profile", "add", "--type", "localfs", "--name", "one", "--mode", "mount", "--option", "root_path=/x", "--json"))
        assertEquals("profile_exists", errorToken())

        assertEquals(1, run("profile", "add", "--type", "localfs", "--name", "two", "--mode", "mirror", "--sync-root", dir.resolve("r").toString(), "--option", "root_path=/y", "--json"))
        assertEquals("sync_root_conflict", errorToken())

        assertEquals(before, Files.readString(configFile))
        assertFalse(Files.exists(dir.resolve("two")), "the refused profile left no folder")
    }

    @Test
    fun `an unparsable config is refused without quoting it`() {
        Files.writeString(configFile, "[providers.x]\ntype = \"localfs\"\npassword = \"hunter2-secret\"\nbroken = = =\n")

        assertEquals(1, run("profile", "add", "--type", "localfs", "--mode", "mount", "--option", "root_path=/x", "--json"))

        assertEquals("config_invalid", errorToken())
        assertFalse("hunter2-secret" in out.toString() + err.toString())
    }

    @Test
    fun `secrets never come from arguments - a supplied secret and a required secret are both refused by key`() {
        LifecycleHooks.factoryFor = { t ->
            if (t == "secretive") {
                SecretFactory(
                    listOf(
                        PromptSpec("bucket", "Bucket"),
                        PromptSpec("secret_access_key", "Secret", isMasked = true),
                    ),
                )
            } else {
                ProviderRegistry.get(t)
            }
        }

        assertEquals(1, run("profile", "add", "--type", "secretive", "--mode", "mount", "--option", "bucket=b", "--option", "secret_access_key=TOP-SECRET-VALUE", "--json"))
        assertEquals("secret_not_allowed_in_argv", errorToken())
        assertFalse("TOP-SECRET-VALUE" in out.toString() + err.toString(), "the refusal names the key, never the value")

        assertEquals(1, run("profile", "add", "--type", "secretive", "--mode", "mount", "--option", "bucket=b", "--json"))
        assertEquals("secret_prompt_unsupported", errorToken())
        assertFalse(Files.exists(configFile))
    }

    @Test
    fun `an optional secret prompt is skipped and a default fills a missing prompt`() {
        LifecycleHooks.factoryFor = { t ->
            if (t == "secretive") {
                SecretFactory(
                    listOf(
                        PromptSpec("region", "Region", default = "auto"),
                        PromptSpec("session_token", "Token", isMasked = true, required = false),
                    ),
                )
            } else {
                ProviderRegistry.get(t)
            }
        }

        assertEquals(0, run("profile", "add", "--type", "secretive", "--mode", "mount", "--json"))

        assertTrue("region = \"auto\"" in Files.readString(configFile))
        assertFalse("session_token" in Files.readString(configFile))
    }

    @Test
    fun `plain mode without --json prints a line and refusals go to stderr`() {
        assertEquals(0, run("profile", "add", "--type", "localfs", "--name", "p", "--mode", "mount", "--option", "root_path=/x"))
        assertTrue("'p' added" in out.toString(), out.toString())

        assertEquals(1, run("profile", "add", "--type", "localfs", "--name", "p", "--mode", "mount", "--option", "root_path=/x"))
        assertEquals("", out.toString())
        assertTrue("already exists" in err.toString(), err.toString())
    }

    @Test
    fun `a device-code provider points at auth begin`() {
        assertEquals(0, run("profile", "add", "--type", "onedrive", "--name", "od", "--mode", "mount", "--json"))
        assertEquals("auth_begin", json().getValue("next").jsonPrimitive.content)

        assertEquals(0, run("profile", "add", "--type", "internxt", "--name", "ix", "--mode", "mount", "--json"))
        assertEquals("auth_login", json().getValue("next").jsonPrimitive.content)
    }

    private fun assertNull0(value: Any?) = assertEquals(null, value, "this suite runs the way CI does: without a console")
}
