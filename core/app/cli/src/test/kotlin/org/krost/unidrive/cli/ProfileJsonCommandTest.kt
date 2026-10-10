package org.krost.unidrive.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `profile list --json` and `profile set`: the machine-readable account surface for scripts and
 * front-ends. Every test drives the real command line against a temp config directory with no
 * console (the JVM under Gradle has none), the way a front-end or CI calls it, and none of them
 * may start a daemon or leave anything but config.toml behind.
 */
class ProfileJsonCommandTest {
    private lateinit var dir: Path
    private val out = ByteArrayOutputStream()
    private val err = ByteArrayOutputStream()
    private val originalOut = System.out
    private val originalErr = System.err

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("profile-json")
        System.setOut(PrintStream(out, true, Charsets.UTF_8))
        System.setErr(PrintStream(err, true, Charsets.UTF_8))
    }

    @AfterTest
    fun tearDown() {
        System.setOut(originalOut)
        System.setErr(originalErr)
        dir.toFile().deleteRecursively()
    }

    private val configFile: Path get() = dir.resolve("config.toml")

    private fun writeConfig(text: String) = Files.writeString(configFile, text)

    private fun config(): String = Files.readString(configFile)

    private fun run(vararg args: String): Int {
        out.reset()
        err.reset()
        val code = CommandLine(Main()).execute("-c", dir.toString(), *args)
        System.out.flush()
        System.err.flush()
        return code
    }

    private fun stdout(): String = out.toString(Charsets.UTF_8)

    private fun stderr(): String = err.toString(Charsets.UTF_8)

    /** stdout must be exactly one JSON object: anything else on it breaks a front-end's parser. */
    private fun json(): JsonObject {
        val text = stdout().trim()
        assertEquals(1, text.lines().size, "stdout must be a single line of JSON, was: $text")
        return Json.parseToJsonElement(text).jsonObject
    }

    private val twoProfiles =
        """
        [general]
        default_profile = "work"

        [providers.work]
        type = "localfs"
        mode = "mount"
        label = "Work drive"
        root_path = "/tmp/work"
        sync_root = "/tmp/work-root"
        sync_path = ["/a", "/b"]
        hydration_cache_max_bytes = 1024
        daemon_poll_seconds = 30

        [providers.old]
        type = "localfs"
        root_path = "/tmp/old"
        password = "hunter2-secret"
        """.trimIndent() + "\n"

    // -- profile list --json -----------------------------------------------------

    @Test
    fun `list --json reports every profile with unknown values as null and no secrets`() {
        writeConfig(twoProfiles)
        val before = config()

        assertEquals(0, run("profile", "list", "--json"))

        val root = json()
        assertEquals("work", root.getValue("default_profile").jsonPrimitive.content)
        val profiles = root.getValue("profiles").jsonArray
        assertEquals(listOf("old", "work"), profiles.map { it.jsonObject.getValue("name").jsonPrimitive.content })

        val work = profiles.first { it.jsonObject["name"]!!.jsonPrimitive.content == "work" }.jsonObject
        assertEquals("localfs", work.getValue("type").jsonPrimitive.content)
        assertEquals("mount", work.getValue("mode").jsonPrimitive.content)
        assertEquals("Work drive", work.getValue("label").jsonPrimitive.content)
        assertEquals("/tmp/work-root", work.getValue("sync_root").jsonPrimitive.content)
        assertEquals(listOf("/a", "/b"), work.getValue("sync_path").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(1024L, work.getValue("hydration_cache_max_bytes").jsonPrimitive.long)
        assertEquals(30L, work.getValue("daemon_poll_seconds").jsonPrimitive.long)
        assertTrue(work.getValue("auth").jsonPrimitive.content in setOf("ok", "expired", "none"))

        // A modeless profile is reported as such, and unset keys are null, never a default.
        val old = profiles.first { it.jsonObject["name"]!!.jsonPrimitive.content == "old" }.jsonObject
        assertEquals(JsonNull, old.getValue("mode"))
        assertEquals(JsonNull, old.getValue("label"))
        assertEquals(JsonNull, old.getValue("sync_path"))
        assertEquals(JsonNull, old.getValue("hydration_cache_max_bytes"))
        assertEquals(JsonNull, old.getValue("daemon_poll_seconds"))

        assertFalse("hunter2-secret" in stdout(), "a credential must never reach the JSON")
        assertEquals(before, config(), "list is read-only")
    }

    @Test
    fun `list --json without a config file is an empty list, not an error`() {
        assertEquals(0, run("profile", "list", "--json"))
        val root = json()
        assertEquals(JsonNull, root.getValue("default_profile"))
        assertEquals(JsonArray(emptyList()), root.getValue("profiles"))
        assertEquals(listOf<String>(), Files.list(dir).use { s -> s.map { it.fileName.toString() }.toList() }, "nothing is created")
    }

    @Test
    fun `list --json on an unparsable config is a machine-readable error that quotes nothing`() {
        writeConfig("[providers.x]\ntype = \"localfs\"\npassword = \"hunter2-secret\"\nbroken = = =\n")

        assertEquals(1, run("profile", "list", "--json"))

        val root = json()
        assertFalse(root.getValue("ok").jsonPrimitive.boolean)
        assertEquals("config_invalid", root.getValue("error").jsonPrimitive.content)
        assertFalse("hunter2-secret" in stdout() + stderr())
    }

    @Test
    fun `list without --json still prints the table`() {
        writeConfig(twoProfiles)

        assertEquals(0, run("profile", "list"))

        assertTrue("PROFILE" in stdout() && "work" in stdout() && "old" in stdout(), stdout())
    }

    // -- profile set ---------------------------------------------------------------

    @Test
    fun `set label writes the label, keeps the id and the folders, and needs no restart`() {
        writeConfig(twoProfiles)
        Files.createDirectory(dir.resolve("work"))

        assertEquals(0, run("profile", "set", "work", "label", "Büro \"Laufwerk\" \\ 1", "--json"))

        val r = json()
        assertTrue(r.getValue("ok").jsonPrimitive.boolean)
        assertEquals("work", r.getValue("profile").jsonPrimitive.content)
        assertEquals("label", r.getValue("key").jsonPrimitive.content)
        assertEquals("Büro \"Laufwerk\" \\ 1", r.getValue("value").jsonPrimitive.content)
        assertEquals("Work drive", r.getValue("previous").jsonPrimitive.content)
        assertFalse(r.getValue("restart_required").jsonPrimitive.boolean)

        // Stored as typed, read back through the same parser the engine uses, id untouched.
        assertTrue("[providers.work]" in config())
        assertTrue(Files.isDirectory(dir.resolve("work")), "changing a label renames no folder")
        run("profile", "list", "--json")
        val work = json().getValue("profiles").jsonArray.first { it.jsonObject["name"]!!.jsonPrimitive.content == "work" }
        assertEquals("Büro \"Laufwerk\" \\ 1", work.jsonObject.getValue("label").jsonPrimitive.content)
    }

    @Test
    fun `set on an engine key says to restart the daemon`() {
        writeConfig(twoProfiles)

        assertEquals(0, run("profile", "set", "work", "daemon_poll_seconds", "120", "--json"))

        val r = json()
        assertTrue(r.getValue("restart_required").jsonPrimitive.boolean)
        assertTrue("restart the daemon" in r.getValue("message").jsonPrimitive.content.lowercase())
        assertEquals(120L, r.getValue("value").jsonPrimitive.long)
        assertEquals(30L, r.getValue("previous").jsonPrimitive.long)
        assertTrue("daemon_poll_seconds = 120" in config())
        assertFalse("daemon_poll_seconds = 30" in config())
    }

    @Test
    fun `set sync_path takes one path or a JSON list and validates the entries`() {
        writeConfig(twoProfiles)

        assertEquals(0, run("profile", "set", "work", "sync_path", "/only", "--json"))
        assertEquals(listOf("/only"), json().getValue("value").jsonArray.map { it.jsonPrimitive.content })
        assertTrue("sync_path = \"/only\"" in config())

        assertEquals(0, run("profile", "set", "work", "sync_path", "[\"/x\",\"/y z\"]", "--json"))
        assertEquals(listOf("/x", "/y z"), json().getValue("value").jsonArray.map { it.jsonPrimitive.content })

        val before = config()
        for (bad in listOf("relative", "/a/../b", "[]", "[1]", "[\"/ok\", \"nope\"]", "")) {
            assertEquals(1, run("profile", "set", "work", "sync_path", bad, "--json"), "value '$bad'")
            assertEquals("invalid_value", json().getValue("error").jsonPrimitive.content, "value '$bad'")
        }
        assertEquals(before, config(), "a refused value changes nothing")
    }

    @Test
    fun `set refuses a number out of range or not a number`() {
        writeConfig(twoProfiles)
        val before = config()
        for ((key, bad) in listOf(
            "hydration_cache_max_bytes" to "-1",
            "hydration_cache_max_bytes" to "lots",
            "daemon_poll_seconds" to "1.5",
            "daemon_poll_seconds" to "99999999999",
        )) {
            // "--" so a leading minus is a value, not an option.
            assertEquals(1, run("profile", "set", "--json", "work", key, "--", bad), "$key=$bad")
            assertEquals("invalid_value", json().getValue("error").jsonPrimitive.content, "$key=$bad")
        }
        assertEquals(before, config())
    }

    @Test
    fun `set refuses unknown keys with a token and changes nothing`() {
        writeConfig(twoProfiles)
        val before = config()

        assertEquals(1, run("profile", "set", "work", "colour", "blue", "--json"))

        val r = json()
        assertFalse(r.getValue("ok").jsonPrimitive.boolean)
        assertEquals("unknown_key", r.getValue("error").jsonPrimitive.content)
        assertTrue("label" in r.getValue("message").jsonPrimitive.content, "the reply names the settable keys")
        assertEquals(before, config())
    }

    @Test
    fun `set refuses fixed keys and credentials without echoing the value`() {
        writeConfig(twoProfiles)
        val before = config()
        for (key in listOf("type", "mode", "sync_root", "password", "secret_access_key", "client_secret")) {
            assertEquals(1, run("profile", "set", "work", key, "SECRET-VALUE-123", "--json"), key)
            assertEquals("key_not_settable", json().getValue("error").jsonPrimitive.content, key)
            assertFalse("SECRET-VALUE-123" in stdout() + stderr(), "the refusal must not echo the value ($key)")
        }
        assertEquals(before, config())
    }

    @Test
    fun `set reports a missing profile, config, value and an invalid profile name by token`() {
        assertEquals(1, run("profile", "set", "work", "label", "x", "--json"))
        assertEquals("config_not_found", json().getValue("error").jsonPrimitive.content)

        writeConfig(twoProfiles)
        assertEquals(1, run("profile", "set", "nope", "label", "x", "--json"))
        assertEquals("profile_not_found", json().getValue("error").jsonPrimitive.content)

        assertEquals(1, run("profile", "set", "work", "label", "--json"))
        assertEquals("missing_value", json().getValue("error").jsonPrimitive.content)

        assertEquals(1, run("profile", "set", "../etc", "label", "x", "--json"))
        assertEquals("invalid_profile_name", json().getValue("error").jsonPrimitive.content)
    }

    @Test
    fun `set --unset removes the key and reads back as null`() {
        writeConfig(twoProfiles)

        assertEquals(0, run("profile", "set", "work", "label", "--unset", "--json"))

        val r = json()
        assertEquals(JsonNull, r.getValue("value"))
        assertEquals("Work drive", r.getValue("previous").jsonPrimitive.content)
        assertFalse("label" in config().substringAfter("[providers.work]").substringBefore("[providers.old]"))
    }

    @Test
    fun `set keeps comments, other sections and the sub-table layout`() {
        writeConfig(
            "# my config\n[general]\n\n[providers.work]\ntype = \"localfs\"\nmode = \"mirror\"\nroot_path = \"/r\" # inline\n\n" +
                "[providers.work.pin_patterns]\ninclude = [\"*.md\"]\n\n[providers.other]\ntype = \"localfs\"\nroot_path = \"/o\"\n",
        )

        assertEquals(0, run("profile", "set", "work", "label", "L", "--json"))

        val expected =
            "# my config\n[general]\n\n[providers.work]\ntype = \"localfs\"\nmode = \"mirror\"\nroot_path = \"/r\" # inline\nlabel = \"L\"\n\n" +
                "[providers.work.pin_patterns]\ninclude = [\"*.md\"]\n\n[providers.other]\ntype = \"localfs\"\nroot_path = \"/o\"\n"
        assertEquals(expected, config())
    }

    @Test
    fun `set leaves a key that spans several lines alone`() {
        val text = "[providers.work]\ntype = \"localfs\"\nroot_path = \"/r\"\nsync_path = [\n  \"/a\",\n  \"/b\",\n]\n"
        writeConfig(text)

        assertEquals(1, run("profile", "set", "work", "sync_path", "/c", "--json"))

        assertEquals("unsupported_layout", json().getValue("error").jsonPrimitive.content)
        assertEquals(text, config())
    }

    @Test
    fun `set in plain mode prints the restart hint, and a refusal goes to stderr`() {
        writeConfig(twoProfiles)

        assertEquals(0, run("profile", "set", "work", "hydration_cache_max_bytes", "0"))
        assertTrue("restart the daemon" in stdout().lowercase(), stdout())

        assertEquals(1, run("profile", "set", "work", "colour", "blue"))
        assertEquals("", stdout())
        assertTrue("colour" in stderr(), stderr())
    }

    @Test
    fun `the commands leave only config toml behind and start no daemon`() {
        writeConfig(twoProfiles)

        run("profile", "list", "--json")
        run("profile", "set", "work", "label", "x", "--json")
        run("profile", "set", "nope", "label", "x", "--json")

        val names = Files.list(dir).use { s -> s.map { it.fileName.toString() }.toList() }
        assertEquals(listOf("config.toml"), names, "no profile folder, socket, token file or temp file appears")
        assertNull(System.console(), "this suite runs the way CI does: without a console")
    }
}
