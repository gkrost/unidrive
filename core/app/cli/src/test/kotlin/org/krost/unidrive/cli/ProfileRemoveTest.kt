package org.krost.unidrive.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.krost.unidrive.sync.IpcAuth
import org.krost.unidrive.sync.ProcessLock
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.SyncConfig
import org.krost.unidrive.sync.model.SyncEntry
import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `profile remove`: the safe removal. Every test drives the real command line against a temp config
 * directory with no console (the JVM under Gradle has none), the way a front-end or CI calls it. The
 * daemon is a fake: a process lock held by the test, and a listening socket in a temp folder. No
 * test starts a daemon, touches the user's real config or sockets, or reads a real credential.
 */
class ProfileRemoveTest {
    private lateinit var dir: Path
    private lateinit var socketDir: Path
    private val out = ByteArrayOutputStream()
    private val err = ByteArrayOutputStream()
    private val originalOut = System.out
    private val originalErr = System.err

    private var holder: ProcessLock? = null
    private var listener: ServerSocketChannel? = null

    /** A value that must never appear in any output of the command. */
    private val secret = "s3cr3t-marker-value"

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("profile-remove")
        socketDir = Files.createDirectories(dir.resolve("sock"))
        ProfileRemoveHooks.socketPath = { socketDir.resolve("$it.sock") }
        ProfileRemoveHooks.requestShutdown = { error("a test must set requestShutdown before it can be called") }
        ProfileRemoveHooks.stopWaitMs = 2_000
        System.setOut(PrintStream(out, true, Charsets.UTF_8))
        System.setErr(PrintStream(err, true, Charsets.UTF_8))
        writeConfig()
    }

    @AfterTest
    fun tearDown() {
        System.setOut(originalOut)
        System.setErr(originalErr)
        holder?.unlock()
        listener?.close()
        ProfileRemoveHooks.socketPath = { org.krost.unidrive.sync.IpcServer.defaultSocketPath(it) }
        ProfileRemoveHooks.requestShutdown = { requestDaemonShutdown(it) }
        ProfileRemoveHooks.stopWaitMs = 15_000
        dir.toFile().deleteRecursively()
    }

    private val configFile: Path get() = dir.resolve("config.toml")
    private val goneDir: Path get() = dir.resolve("gone")
    private val mirrorRoot: Path get() = dir.resolve("roots").resolve("gone")

    private fun toml(p: Path) = p.toString().replace('\\', '/')

    private fun writeConfig() {
        Files.writeString(
            configFile,
            """
            [general]
            default_profile = "gone"

            [providers.gone]
            type = "localfs"
            mode = "mirror"
            sync_root = "${toml(mirrorRoot)}"
            root_path = "${toml(dir.resolve("remote-gone"))}"
            password = "$secret"

            [providers.keep]
            type = "localfs"
            mode = "mirror"
            sync_root = "${toml(dir.resolve("roots").resolve("keep"))}"
            root_path = "${toml(dir.resolve("remote-keep"))}"
            """.trimIndent() + "\n",
        )
    }

    /** A profile folder with state, a credential file holding [secret], IPC tokens and a local root with a user file. */
    private fun populate() {
        Files.createDirectories(goneDir)
        writeState()
        Files.writeString(goneDir.resolve("credentials.json"), """{"refresh_token":"$secret"}""")
        Files.writeString(goneDir.resolve(IpcAuth.TOKEN_FILE), "tok-full")
        Files.writeString(goneDir.resolve(IpcAuth.READ_TOKEN_FILE), "tok-read")
        Files.createDirectories(mirrorRoot)
        Files.writeString(mirrorRoot.resolve("my-document.txt"), "precious")
    }

    /** A real, empty state.db (the removal opens it read-only, so a stand-in text file would be unreadable). */
    private fun writeState(vararg rows: Pair<String, Boolean>) {
        Files.createDirectories(goneDir)
        val db = StateDatabase(goneDir.resolve("state.db"))
        db.initialize()
        try {
            for ((path, failed) in rows) {
                db.upsertEntry(
                    SyncEntry(
                        path = path,
                        remoteId = "local:$path",
                        remoteHash = null,
                        remoteSize = 0,
                        remoteModified = null,
                        localMtime = 1L,
                        localSize = 1L,
                        isFolder = false,
                        isPinned = false,
                        isHydrated = true,
                        lastSynced = Instant.parse("2000-01-01T00:00:00Z"),
                    ),
                )
                if (failed) db.markUploadFailed(path, Instant.parse("2000-01-02T00:00:00Z"))
            }
        } finally {
            db.close()
        }
    }

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

    private fun json(): JsonObject {
        val text = stdout().trim()
        assertEquals(1, text.lines().size, "stdout must be a single line of JSON, was: $text")
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun errorToken(): String {
        val r = json()
        assertFalse(r.getValue("ok").jsonPrimitive.boolean)
        return r.getValue("error").jsonPrimitive.content
    }

    private fun profiles() = SyncConfig.parseRaw(Files.readString(configFile)).providers.keys

    private fun assertNoSecret() {
        assertFalse(secret in stdout(), "stdout leaked a secret")
        assertFalse(secret in stderr(), "stderr leaked a secret")
    }

    /** The fake daemon: holds the profile's process lock in DAEMON mode, like `daemon run`. */
    private fun fakeDaemonLock() {
        Files.createDirectories(goneDir)
        holder = ProcessLock(goneDir.resolve(".lock")).also { assertTrue(it.tryLock(ProcessLock.Mode.DAEMON)) }
    }

    /** The fake daemon's socket: a listening unix socket where the profile's socket is expected. */
    private fun fakeDaemonSocket() {
        val path = socketDir.resolve("gone.sock")
        listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX).also { it.bind(UnixDomainSocketAddress.of(path)) }
    }

    private fun assertNothingRemoved() {
        assertTrue("gone" in profiles(), "the config section must still be there")
        assertTrue(Files.exists(goneDir.resolve("state.db")), "the profile folder must be untouched")
        assertTrue(Files.exists(goneDir.resolve("credentials.json")), "the credentials must be untouched")
        assertTrue(Files.exists(mirrorRoot.resolve("my-document.txt")), "the sync root must be untouched")
    }

    // ── no console ──────────────────────────────────────────────────────────

    @Test
    fun `yes and json remove a profile with no console and no prompt`() {
        assertNull(System.console(), "this test must run without a console")
        populate()

        val code = run("profile", "remove", "gone", "--yes", "--json")

        assertEquals(0, code, stderr())
        val r = json()
        assertTrue(r.getValue("ok").jsonPrimitive.boolean)
        assertEquals("gone", r.getValue("profile").jsonPrimitive.content)
        assertFalse(r.getValue("daemon_stopped").jsonPrimitive.boolean)
        assertTrue(r.getValue("was_default_profile").jsonPrimitive.boolean)
        val removed = r.getValue("removed").jsonObject
        assertTrue(removed.getValue("config_section").jsonPrimitive.boolean)
        assertTrue(removed.getValue("state_folder").jsonPrimitive.boolean)
        assertTrue(removed.getValue("credentials").jsonPrimitive.boolean)
        assertTrue(removed.getValue("ipc_tokens").jsonPrimitive.boolean)
        val kept = r.getValue("kept").jsonObject
        assertEquals(toml(mirrorRoot), kept.getValue("sync_root").jsonPrimitive.content.replace('\\', '/'))
        assertFalse(kept.getValue("state_folder").jsonPrimitive.boolean)
        assertFalse(kept.getValue("credentials").jsonPrimitive.boolean)
        assertEquals("not touched", kept.getValue("secret_store").jsonPrimitive.content)

        assertEquals(setOf("keep"), profiles(), "only the named profile goes")
        assertFalse(Files.exists(goneDir), "the profile folder goes")
        assertTrue(Files.exists(mirrorRoot.resolve("my-document.txt")), "the sync root is never deleted")
        assertNoSecret()
    }

    @Test
    fun `json without yes never prompts and removes nothing`() {
        populate()

        assertEquals(1, run("profile", "remove", "gone", "--json"))

        assertEquals("confirmation_required", errorToken())
        assertNothingRemoved()
    }

    @Test
    fun `without a console and without yes the human form refuses as before`() {
        populate()

        assertEquals(1, run("profile", "remove", "gone"))

        assertTrue("interactive terminal required" in stderr(), stderr())
        assertNothingRemoved()
    }

    @Test
    fun `the human form with yes still prints its lines`() {
        populate()

        assertEquals(0, run("profile", "remove", "gone", "-y"), stderr())

        assertTrue("Deleted: $goneDir" in stdout(), stdout())
        assertTrue("Profile 'gone' removed." in stdout(), stdout())
        assertEquals(setOf("keep"), profiles())
    }

    @Test
    fun `an unknown profile is a json refusal`() {
        assertEquals(1, run("profile", "remove", "nope", "--yes", "--json"))
        assertEquals("profile_not_found", errorToken())

        assertEquals(1, run("profile", "remove", "../gone", "--yes", "--json"))
        assertEquals("invalid_profile_name", errorToken())
    }

    // ── the refusal while a daemon holds the profile ────────────────────────

    @Test
    fun `a daemon holding the process lock refuses the removal`() {
        populate()
        fakeDaemonLock()

        assertEquals(1, run("profile", "remove", "gone", "--yes", "--json"))

        assertEquals("daemon_running", errorToken())
        assertNothingRemoved()
        assertNoSecret()
    }

    @Test
    fun `a socket that answers refuses the removal even without a lock`() {
        populate()
        fakeDaemonSocket()

        assertEquals(1, run("profile", "remove", "gone", "--yes", "--json"))

        assertEquals("daemon_running", errorToken())
        assertNothingRemoved()
    }

    @Test
    fun `the human form refuses while a daemon runs and says how to go on`() {
        populate()
        fakeDaemonLock()

        assertEquals(1, run("profile", "remove", "gone", "-y"))

        assertTrue("--stop" in stderr(), stderr())
        assertNothingRemoved()
    }

    @Test
    fun `a sync holding the lock is profile_in_use and stop does not apply`() {
        populate()
        Files.createDirectories(goneDir)
        holder = ProcessLock(goneDir.resolve(".lock")).also { assertTrue(it.tryLock(ProcessLock.Mode.SYNC)) }

        assertEquals(1, run("profile", "remove", "gone", "--yes", "--stop", "--json"))

        assertEquals("profile_in_use", errorToken())
        assertNothingRemoved()
    }

    @Test
    fun `the check starts no daemon and creates no lock file`() {
        populate()
        fakeDaemonSocket()
        var asked = false
        ProfileRemoveHooks.requestShutdown = {
            asked = true
            true
        }

        assertEquals(1, run("profile", "remove", "gone", "--yes", "--json"))
        assertEquals("daemon_running", errorToken())

        assertFalse(asked, "without --stop the daemon is never asked anything")
        assertFalse(Files.exists(goneDir.resolve(".lock")), "the read-only check must not create the lock file")
    }

    // ── --stop ──────────────────────────────────────────────────────────────

    @Test
    fun `stop asks the daemon over ipc, waits, and then removes`() {
        populate()
        fakeDaemonLock()
        fakeDaemonSocket()
        var endpointName: String? = null
        ProfileRemoveHooks.requestShutdown = { endpoint ->
            endpointName = endpoint.profileName
            // The daemon's clean shutdown: it lets go of the lock and closes its socket.
            holder?.unlock()
            holder = null
            listener?.close()
            Files.deleteIfExists(endpoint.socketPath)
            true
        }

        assertEquals(0, run("profile", "remove", "gone", "--yes", "--stop", "--json"), stderr())

        assertEquals("gone", endpointName)
        val r = json()
        assertTrue(r.getValue("ok").jsonPrimitive.boolean)
        assertTrue(r.getValue("daemon_stopped").jsonPrimitive.boolean)
        assertEquals(setOf("keep"), profiles())
        assertFalse(Files.exists(goneDir))
        assertTrue(Files.exists(mirrorRoot.resolve("my-document.txt")))
    }

    @Test
    fun `stop that the daemon does not accept fails with a token and removes nothing`() {
        populate()
        fakeDaemonLock()
        ProfileRemoveHooks.requestShutdown = { false }

        assertEquals(1, run("profile", "remove", "gone", "--yes", "--stop", "--json"))

        assertEquals("daemon_stop_failed", errorToken())
        assertNothingRemoved()
    }

    @Test
    fun `stop that the daemon accepts but never completes gives up after the bounded wait`() {
        populate()
        fakeDaemonLock()
        ProfileRemoveHooks.requestShutdown = { true }
        ProfileRemoveHooks.stopWaitMs = 300

        val started = System.currentTimeMillis()
        assertEquals(1, run("profile", "remove", "gone", "--yes", "--stop", "--json"))

        assertTrue(System.currentTimeMillis() - started < 10_000, "the wait is bounded")
        assertEquals("daemon_stop_failed", errorToken())
        assertNothingRemoved()
    }

    @Test
    fun `stop with no daemon running asks nobody`() {
        populate()
        ProfileRemoveHooks.requestShutdown = { error("there is no daemon to ask") }

        assertEquals(0, run("profile", "remove", "gone", "--yes", "--stop", "--json"), stderr())

        assertFalse(json().getValue("daemon_stopped").jsonPrimitive.boolean)
        assertEquals(setOf("keep"), profiles())
    }

    // ── what is removed and what is kept ────────────────────────────────────

    @Test
    fun `the ipc token files and the socket go with the profile`() {
        populate()
        val socket = socketDir.resolve("gone.sock")
        Files.writeString(socket, "stale")
        Files.writeString(socketDir.resolve("gone.sock.meta"), "gone\n")

        assertEquals(0, run("profile", "remove", "gone", "--yes", "--json"), stderr())

        val removed = json().getValue("removed").jsonObject
        assertTrue(removed.getValue("ipc_tokens").jsonPrimitive.boolean)
        assertTrue(removed.getValue("socket").jsonPrimitive.boolean)
        assertFalse(Files.exists(socket))
        assertFalse(Files.exists(socketDir.resolve("gone.sock.meta")))
        assertFalse(Files.exists(goneDir.resolve(IpcAuth.TOKEN_FILE)))
    }

    @Test
    fun `keep-data keeps the folder and the credentials but still drops the ipc tokens`() {
        populate()

        assertEquals(0, run("profile", "remove", "gone", "--yes", "--keep-data", "--json"), stderr())

        val r = json()
        val removed = r.getValue("removed").jsonObject
        assertTrue(removed.getValue("config_section").jsonPrimitive.boolean)
        assertFalse(removed.getValue("state_folder").jsonPrimitive.boolean)
        assertFalse(removed.getValue("credentials").jsonPrimitive.boolean)
        assertTrue(removed.getValue("ipc_tokens").jsonPrimitive.boolean)
        val kept = r.getValue("kept").jsonObject
        assertTrue(kept.getValue("state_folder").jsonPrimitive.boolean)
        assertTrue(kept.getValue("credentials").jsonPrimitive.boolean)
        assertEquals(setOf("keep"), profiles())
        assertTrue(Files.exists(goneDir.resolve("state.db")))
        assertTrue(Files.exists(goneDir.resolve("credentials.json")))
        assertFalse(Files.exists(goneDir.resolve(IpcAuth.TOKEN_FILE)))
        assertNoSecret()
    }

    @Test
    fun `a mount root inside the profile folder survives, everything else of the folder goes`() {
        // A mount profile keeps its root under the profile folder; the files there may be uploads not yet sent.
        val mountRoot = goneDir.resolve("sync-root")
        Files.writeString(
            configFile,
            """
            [general]
            default_profile = "keep"

            [providers.gone]
            type = "localfs"
            mode = "mount"
            sync_root = "${toml(mountRoot)}"
            root_path = "${toml(dir.resolve("remote-gone"))}"

            [providers.keep]
            type = "localfs"
            mode = "mirror"
            sync_root = "${toml(dir.resolve("roots").resolve("keep"))}"
            root_path = "${toml(dir.resolve("remote-keep"))}"
            """.trimIndent() + "\n",
        )
        Files.createDirectories(mountRoot.resolve("sub"))
        Files.writeString(mountRoot.resolve("sub").resolve("pending-upload.bin"), "bytes")
        writeState()
        Files.writeString(goneDir.resolve("token.json"), """{"access_token":"$secret"}""")
        Files.createDirectories(goneDir.resolve("journal"))
        Files.writeString(goneDir.resolve("journal").resolve("failures.jsonl"), "x")

        assertEquals(0, run("profile", "remove", "gone", "--yes", "--json"), stderr())

        val r = json()
        assertEquals(toml(mountRoot), r.getValue("kept").jsonObject.getValue("sync_root").jsonPrimitive.content.replace('\\', '/'))
        assertTrue(r.getValue("kept").jsonObject.getValue("state_folder").jsonPrimitive.boolean, "the folder stays because it holds the root")
        assertTrue(Files.exists(mountRoot.resolve("sub").resolve("pending-upload.bin")), "the mount root is never deleted")
        assertFalse(Files.exists(goneDir.resolve("state.db")))
        assertFalse(Files.exists(goneDir.resolve("token.json")))
        assertFalse(Files.exists(goneDir.resolve("journal")))
        assertFalse(Files.exists(goneDir.resolve(".lock")))
        assertEquals(setOf("keep"), profiles())
        assertNoSecret()
    }

    // ── links and junctions are leaves ──────────────────────────────────────

    /** A folder outside the profile with a file in it, which no removal may touch. */
    private fun outsideFolder(): Path {
        val outside = Files.createDirectories(dir.resolve("outside"))
        Files.writeString(outside.resolve("must-survive.txt"), "keep me")
        return outside
    }

    @Test
    fun `a junction in the profile folder is removed as an entry and its target is left alone`() {
        assumeTrue("junctions (mklink /J) exist only on Windows", System.getProperty("os.name").lowercase().contains("win"))
        populate()
        val outside = outsideFolder()
        val junction = goneDir.resolve("linked")
        val made = ProcessBuilder("cmd", "/c", "mklink", "/J", junction.toString(), outside.toString()).redirectErrorStream(true).start()
        val output = made.inputStream.readBytes().toString(Charsets.UTF_8)
        assumeTrue("mklink /J is not available here: $output", made.waitFor() == 0 && Files.exists(junction))

        assertEquals(0, run("profile", "remove", "gone", "--yes", "--json"), stderr())

        assertFalse(Files.exists(junction, java.nio.file.LinkOption.NOFOLLOW_LINKS), "the junction itself goes")
        assertTrue(Files.exists(outside.resolve("must-survive.txt")), "what the junction points to is never touched")
        assertFalse(Files.exists(goneDir), "the profile folder goes")
        assertEquals(setOf("keep"), profiles())
    }

    @Test
    fun `a symbolic link in the profile folder is removed as an entry and its target is left alone`() {
        populate()
        val outside = outsideFolder()
        val link = goneDir.resolve("linked")
        val made = runCatching { Files.createSymbolicLink(link, outside) }
        assumeTrue("symbolic links are not available here", made.isSuccess)

        assertEquals(0, run("profile", "remove", "gone", "--yes", "--json"), stderr())

        assertFalse(Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.exists(outside.resolve("must-survive.txt")))
        assertFalse(Files.exists(goneDir))
    }

    // ── no secret in any output ─────────────────────────────────────────────

    @Test
    fun `no output of any path carries a secret from the config or the credentials`() {
        populate()
        fakeDaemonLock()

        assertEquals(1, run("profile", "remove", "gone", "--yes", "--json"))
        assertTrue(stdout().contains("daemon_running"), stdout())
        assertNoSecret()
        assertEquals(1, run("profile", "remove", "gone", "-y"))
        assertTrue(stderr().isNotBlank())
        assertNoSecret()

        holder?.unlock()
        holder = null
        assertEquals(0, run("profile", "remove", "gone", "--yes", "--json"), stderr())
        assertTrue(stdout().contains("\"ok\":true"), stdout())
        assertNoSecret()
    }

    @Test
    fun `an unparsable config is a token that does not quote the file`() {
        Files.writeString(configFile, "[providers.gone\npassword = \"$secret\"\n")

        assertEquals(1, run("profile", "remove", "gone", "--yes", "--json"))

        assertNoSecret()
        assertEquals("config_invalid", errorToken())
    }

    // ── uploads still on their way ──────────────────────────────────────────

    @Test
    fun `uploads on their way refuse a stopped profile with both counts`() {
        populate()
        writeState("/a.txt" to false, "/b.txt" to false, "/c.txt" to true)

        assertEquals(1, run("profile", "remove", "gone", "--yes", "--json"))

        assertEquals("uploads_pending", errorToken())
        assertEquals(3, json().getValue("pending").jsonPrimitive.int)
        assertEquals(1, json().getValue("failed").jsonPrimitive.int)
        assertNothingRemoved()
    }

    @Test
    fun `only pending uploads refuse`() {
        populate()
        writeState("/a.txt" to false)

        assertEquals(1, run("profile", "remove", "gone", "--yes", "--json"))

        assertEquals("uploads_pending", errorToken())
        assertEquals(1, json().getValue("pending").jsonPrimitive.int)
        assertEquals(0, json().getValue("failed").jsonPrimitive.int)
        assertNothingRemoved()
    }

    @Test
    fun `failed uploads alone do not block the removal`() {
        populate()
        writeState("/a.txt" to true, "/b.txt" to true)

        assertEquals(0, run("profile", "remove", "gone", "--yes", "--json"), stderr())

        assertTrue(json().getValue("ok").jsonPrimitive.boolean)
        assertFalse("gone" in profiles())
    }

    @Test
    fun `force-pending removes a profile with uploads on their way`() {
        populate()
        writeState("/a.txt" to false, "/c.txt" to true)

        assertEquals(0, run("profile", "remove", "gone", "--yes", "--json", "--force-pending"), stderr())

        assertTrue(json().getValue("ok").jsonPrimitive.boolean)
        assertFalse("gone" in profiles())
        assertFalse(Files.exists(goneDir))
    }

    @Test
    fun `the human form names the counts and the override`() {
        populate()
        writeState("/a.txt" to false)

        assertEquals(1, run("profile", "remove", "gone", "--yes"))

        assertTrue("--force-pending" in stderr(), stderr())
        assertTrue("1 pending" in stderr(), stderr())
        assertNothingRemoved()
    }

    @Test
    fun `a state db that cannot be read is refused`() {
        populate()
        Files.writeString(goneDir.resolve("state.db"), "this is not a database, and it is long enough to be read as one header")

        assertEquals(1, run("profile", "remove", "gone", "--yes", "--json"))

        assertEquals("state_unreadable", errorToken())
        assertNothingRemoved()
    }

    @Test
    fun `a profile without a state db has nothing pending`() {
        populate()
        Files.delete(goneDir.resolve("state.db"))

        assertEquals(0, run("profile", "remove", "gone", "--yes", "--json"), stderr())

        assertFalse("gone" in profiles())
    }

    @Test
    fun `the pending check reads the state db read-only`() {
        populate()
        writeState("/a.txt" to false)
        val db = goneDir.resolve("state.db")
        val before = Files.readAllBytes(db)

        assertEquals(1, run("profile", "remove", "gone", "--yes", "--json"))

        assertEquals("uploads_pending", errorToken())
        assertTrue(before.contentEquals(Files.readAllBytes(db)), "state.db must be byte-identical")
        assertFalse(Files.exists(goneDir.resolve("state.db-wal")), "no -wal file may appear")
        assertFalse(Files.exists(goneDir.resolve("state.db-journal")), "no journal may appear")
    }

    @Test
    fun `a running daemon is still refused as before, pending or not`() {
        populate()
        writeState("/a.txt" to false)
        fakeDaemonLock()

        assertEquals(1, run("profile", "remove", "gone", "--yes", "--json"))

        assertEquals("daemon_running", errorToken())
    }
}
