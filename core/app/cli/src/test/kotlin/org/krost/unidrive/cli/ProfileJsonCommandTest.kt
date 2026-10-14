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
    private val originalIn = System.`in`

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
        System.setIn(originalIn)
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

    private fun stdin(text: String) = System.setIn(text.toByteArray(Charsets.UTF_8).inputStream())

    private fun listed(name: String): JsonObject {
        assertEquals(0, run("profile", "list", "--json"))
        return json().getValue("profiles").jsonArray.first { it.jsonObject["name"]!!.jsonPrimitive.content == name }.jsonObject
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

    // -- profile default -------------------------------------------------------------

    private val oddConfig =
        "# my config\r\n[general]\r\n  poll_interval   =   60 # keep\r\ndefault_profile=\"work\"\r\nother = 1\r\n\r\n" +
            "[providers.work]\r\ntype = \"localfs\"\r\nroot_path = \"/w\"\r\n\r\n[providers.other]\r\ntype = \"localfs\"\r\nroot_path   =   \"/o\" # c\r\n"

    @Test
    fun `default changes only the default_profile line and reports the previous one`() {
        writeConfig(oddConfig)

        assertEquals(0, run("profile", "default", "other", "--json"), stderr())

        val r = json()
        assertTrue(r.getValue("ok").jsonPrimitive.boolean)
        assertEquals("other", r.getValue("default_profile").jsonPrimitive.content)
        assertEquals("work", r.getValue("previous").jsonPrimitive.content)
        assertEquals(oddConfig.replace("default_profile=\"work\"", "default_profile = \"other\""), config())
    }

    @Test
    fun `default sets the key when none exists and clear removes it, keeping every other line`() {
        val text = "# c\n[general]\n  poll_interval   =   60\n\n[providers.work]\ntype = \"localfs\"\nroot_path = \"/w\" # i\n"
        writeConfig(text)

        assertEquals(0, run("profile", "default", "work", "--json"), stderr())
        assertEquals(JsonNull, json().getValue("previous"))
        assertEquals(text.replace("[general]\n", "[general]\ndefault_profile = \"work\"\n"), config())

        assertEquals(0, run("profile", "default", "--clear", "--json"), stderr())
        val r = json()
        assertTrue(r.getValue("ok").jsonPrimitive.boolean)
        assertEquals(JsonNull, r.getValue("default_profile"))
        assertEquals("work", r.getValue("previous").jsonPrimitive.content)
        assertEquals(text, config())

        assertEquals(0, run("profile", "default", "--clear", "--json"), "clearing nothing is not an error")
        assertEquals(JsonNull, json().getValue("previous"))
        assertEquals(text, config())
    }

    @Test
    fun `default clear keeps CRLF and the other sections byte for byte`() {
        writeConfig(oddConfig)

        assertEquals(0, run("profile", "default", "--clear", "--json"), stderr())

        assertEquals(oddConfig.replace("default_profile=\"work\"\r\n", ""), config())
    }

    @Test
    fun `default refuses an unknown or invalid name and changes nothing`() {
        writeConfig(twoProfiles)
        val before = config()

        assertEquals(1, run("profile", "default", "nope", "--json"))
        assertFalse(json().getValue("ok").jsonPrimitive.boolean)
        assertEquals("profile_not_found", json().getValue("error").jsonPrimitive.content)

        assertEquals(1, run("profile", "default", "../etc", "--json"))
        assertEquals("invalid_profile_name", json().getValue("error").jsonPrimitive.content)

        assertEquals(1, run("profile", "default", "--json"))
        assertEquals("missing_value", json().getValue("error").jsonPrimitive.content)

        assertEquals(1, run("profile", "default", "work", "--clear", "--json"))
        assertEquals("invalid_value", json().getValue("error").jsonPrimitive.content)

        assertEquals(before, config())
    }

    @Test
    fun `default without a config is refused and starts nothing`() {
        assertEquals(1, run("profile", "default", "work", "--json"))
        assertEquals("config_not_found", json().getValue("error").jsonPrimitive.content)
        assertEquals(emptyList(), Files.list(dir).use { s -> s.map { it.fileName.toString() }.toList() })
    }

    @Test
    fun `default in plain mode prints the result and a refusal goes to stderr`() {
        writeConfig(twoProfiles)

        assertEquals(0, run("profile", "default", "old"))
        assertTrue("old" in stdout(), stdout())

        assertEquals(1, run("profile", "default", "nope"))
        assertEquals("", stdout())
        assertTrue("nope" in stderr(), stderr())
    }

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

    @Test
    fun `list --json still prints the JSON when one sync_root is not a path on this system`() {
        // A NUL is not a path character on any platform (TOML escape; the file itself stays text).
        writeConfig("[providers.bad]\ntype = \"localfs\"\nmode = \"mirror\"\nroot_path = \"/tmp/bad\"\nsync_root = \"a\\u0000b\"\n")

        assertEquals(0, run("profile", "list", "--json"), stderr())

        val profile = json().getValue("profiles").jsonArray.single().jsonObject
        assertEquals("bad", profile.getValue("name").jsonPrimitive.content)
        assertTrue(profile.getValue("auth").jsonPrimitive.content in setOf("ok", "expired", "none"))
    }

    // -- profile set ---------------------------------------------------------------

    @Test
    fun `set label with control characters keeps config toml valid and reads back unchanged`() {
        writeConfig(twoProfiles)
        val label = "a\u0001b\u007fc\td"

        assertEquals(0, run("profile", "set", "work", "label", label, "--json"), stderr())

        assertEquals(label, json().getValue("value").jsonPrimitive.content)
        assertTrue(config().none { (it < ' ' && it != '\n' && it != '\r') || it == '\u007f' }, "raw control characters in config.toml")
        run("profile", "list", "--json")
        val work = json().getValue("profiles").jsonArray.first { it.jsonObject["name"]!!.jsonPrimitive.content == "work" }
        assertEquals(label, work.jsonObject.getValue("label").jsonPrimitive.content)
    }

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

        assertEquals(0, run("profile", "list", "--json"), "list succeeds")
        assertEquals(0, run("profile", "set", "work", "label", "x", "--json"), "set on an existing profile succeeds")
        assertEquals(1, run("profile", "set", "nope", "label", "x", "--json"), "set on a missing profile is refused")

        val names = Files.list(dir).use { s -> s.map { it.fileName.toString() }.toList() }
        assertEquals(listOf("config.toml"), names, "no profile folder, socket, token file or temp file appears")
        assertNull(System.console(), "this suite runs the way CI does: without a console")
    }

    // -- profile set / list: the account's ignore keys --------------------------------

    private val withPoll = "daemon_poll_seconds = 30\n"

    @Test
    fun `a profile without the ignore keys lists their defaults`() {
        writeConfig(twoProfiles)

        val work = listed("work")
        assertFalse(work.getValue("honor_gitignore").jsonPrimitive.boolean)
        assertEquals(JsonArray(emptyList()), work.getValue("ignore_rules"))
    }

    @Test
    fun `set honor_gitignore turns it on and off, reports the old value and leaves the rest byte for byte`() {
        writeConfig(twoProfiles)

        assertEquals(0, run("profile", "set", "work", "honor_gitignore", "true", "--json"), stderr())
        val r = json()
        assertTrue(r.getValue("ok").jsonPrimitive.boolean)
        assertTrue(r.getValue("value").jsonPrimitive.boolean)
        assertFalse(r.getValue("previous").jsonPrimitive.boolean)
        assertTrue(r.getValue("restart_required").jsonPrimitive.boolean)
        assertEquals(twoProfiles.replace(withPoll, withPoll + "honor_gitignore = true\n"), config())
        assertTrue(listed("work").getValue("honor_gitignore").jsonPrimitive.boolean)

        assertEquals(0, run("profile", "set", "work", "honor_gitignore", "false", "--json"), stderr())
        assertTrue(json().getValue("previous").jsonPrimitive.boolean)
        assertEquals(twoProfiles.replace(withPoll, withPoll + "honor_gitignore = false\n"), config())

        assertEquals(0, run("profile", "set", "work", "honor_gitignore", "--unset", "--json"), stderr())
        assertEquals(twoProfiles, config())
    }

    @Test
    fun `set honor_gitignore refuses anything but true or false`() {
        writeConfig(twoProfiles)
        for (bad in listOf("yes", "1", "", "maybe")) {
            assertEquals(1, run("profile", "set", "work", "honor_gitignore", bad, "--json"), "value '$bad'")
            assertEquals("invalid_value", json().getValue("error").jsonPrimitive.content, "value '$bad'")
        }
        assertEquals(twoProfiles, config())
    }

    @Test
    fun `set ignore_rules reads the lines from stdin, changes them, and clears them on empty input`() {
        writeConfig(twoProfiles)
        val rules = listOf("*.log", "!keep.log", "# note", "sub dir/\\#x \"q\"")

        stdin("*.log\r\n!keep.log\r\n\r\n# note\r\nsub dir/\\#x \"q\"\r\n")
        assertEquals(0, run("profile", "set", "work", "ignore_rules", "--json"), stderr())
        val r = json()
        assertTrue(r.getValue("ok").jsonPrimitive.boolean)
        assertEquals(rules, r.getValue("value").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(0, r.getValue("previous").jsonArray.size)
        assertTrue(r.getValue("restart_required").jsonPrimitive.boolean)
        assertEquals(rules, listed("work").getValue("ignore_rules").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(twoProfiles, config().replace(Regex("ignore_rules = .*\n"), ""), "only the ignore_rules line was added")

        stdin("/build\n")
        assertEquals(0, run("profile", "set", "work", "ignore_rules", "--json"), stderr())
        assertEquals(4, json().getValue("previous").jsonArray.size)
        assertEquals(listOf("/build"), listed("work").getValue("ignore_rules").jsonArray.map { it.jsonPrimitive.content })

        stdin("\r\n \n")
        assertEquals(0, run("profile", "set", "work", "ignore_rules", "--json"), stderr())
        assertEquals(listOf("/build"), json().getValue("previous").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(twoProfiles, config(), "empty input removes the key")
        assertEquals(JsonArray(emptyList()), listed("work").getValue("ignore_rules"))
    }

    @Test
    fun `set ignore_rules --unset clears without reading stdin, and a value argument is refused`() {
        writeConfig(twoProfiles)
        stdin("/x\n")
        assertEquals(0, run("profile", "set", "work", "ignore_rules", "--json"))
        stdin("/must-not-be-read\n")
        assertEquals(0, run("profile", "set", "work", "ignore_rules", "--unset", "--json"), stderr())
        assertEquals(twoProfiles, config())

        assertEquals(1, run("profile", "set", "work", "ignore_rules", "*.log", "--json"))
        assertEquals("invalid_value", json().getValue("error").jsonPrimitive.content)
        assertEquals(twoProfiles, config())
    }

    @Test
    fun `set ignore_rules refuses control characters and an oversized input, and changes nothing`() {
        writeConfig(twoProfiles)

        stdin("ok\n\u0001bad\n")
        assertEquals(1, run("profile", "set", "work", "ignore_rules", "--json"))
        assertEquals("invalid_value", json().getValue("error").jsonPrimitive.content)

        stdin("a\n".repeat(40_000))
        assertEquals(1, run("profile", "set", "work", "ignore_rules", "--json"))
        assertEquals("invalid_value", json().getValue("error").jsonPrimitive.content)

        assertEquals(twoProfiles, config())
    }

    @Test
    fun `set ignore_rules on an unknown profile is refused`() {
        writeConfig(twoProfiles)
        stdin("/x\n")
        assertEquals(1, run("profile", "set", "nope", "ignore_rules", "--json"))
        assertEquals("profile_not_found", json().getValue("error").jsonPrimitive.content)
        assertEquals(twoProfiles, config())
    }
}
