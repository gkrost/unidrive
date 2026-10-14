package org.krost.unidrive.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.krost.unidrive.sync.IpcEndpoint
import org.krost.unidrive.sync.ProcessLock
import org.krost.unidrive.sync.StateDatabase
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
import kotlin.test.assertTrue

/**
 * `logout`: the sign-out a front-end can rely on. Every test drives the real command line against a
 * temp config directory; the daemon is a fake (a process lock held by the test, a listening socket,
 * a scripted `auth.logout` reply on the [LogoutHooks] seam). No test starts a daemon, touches the
 * user's real config or sockets, or reads a real credential.
 */
class LogoutCommandJsonTest {
    private lateinit var dir: Path
    private lateinit var socketDir: Path
    private val out = ByteArrayOutputStream()
    private val err = ByteArrayOutputStream()
    private val originalOut = System.out
    private val originalErr = System.err

    private var holder: ProcessLock? = null
    private var listener: ServerSocketChannel? = null

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("logout-json")
        socketDir = Files.createDirectories(dir.resolve("sock"))
        LogoutHooks.socketPath = { socketDir.resolve("$it.sock") }
        LogoutHooks.requestLogout = { _, _ -> error("a test must set requestLogout before it can be called") }
        System.setOut(PrintStream(out, true, Charsets.UTF_8))
        System.setErr(PrintStream(err, true, Charsets.UTF_8))
        writeConfig()
    }

    @AfterTest
    fun tearDown() {
        System.setOut(originalOut)
        System.setErr(originalErr)
        listener?.close()
        holder?.unlock()
        LogoutHooks.socketPath = { org.krost.unidrive.sync.IpcServer.defaultSocketPath(it) }
        LogoutHooks.requestLogout = { endpoint, force -> sendAuthLogout(endpoint, force) }
        dir.toFile().deleteRecursively()
    }

    private val configFile: Path get() = dir.resolve("config.toml")
    private val workDir: Path get() = dir.resolve("work")
    private val workRoot: Path get() = dir.resolve("roots").resolve("work")

    private fun toml(p: Path) = p.toString().replace('\\', '/')

    private fun writeConfig() {
        Files.writeString(
            configFile,
            """
            [general]
            default_profile = "work"

            [providers.work]
            type = "localfs"
            mode = "mirror"
            sync_root = "${toml(workRoot)}"
            root_path = "${toml(dir.resolve("remote-work"))}"
            """.trimIndent() + "\n",
        )
    }

    /** A profile folder with state, a credential file and a pending-upload record, like a signed-in account. */
    private fun populate() {
        Files.createDirectories(workDir)
        Files.writeString(workDir.resolve("credentials.json"), """{"refresh_token":"x"}""")
        Files.writeString(workDir.resolve("device-flow.json"), "{}")
        Files.writeString(workDir.resolve("failures.jsonl"), "{}\n")
    }

    private fun writeState(vararg rows: Pair<String, Boolean>) {
        Files.createDirectories(workDir)
        val db = StateDatabase(workDir.resolve("state.db"))
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

    /** The fake daemon: holds the profile's process lock in DAEMON mode and listens on its socket. */
    private fun fakeDaemon() {
        Files.createDirectories(workDir)
        holder = ProcessLock(workDir.resolve(".lock")).also { assertTrue(it.tryLock(ProcessLock.Mode.DAEMON)) }
        listener =
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).also {
                it.bind(UnixDomainSocketAddress.of(LogoutHooks.socketPath("work")))
            }
    }

    @Test
    fun `signs a stopped account out and keeps its state`() {
        populate()
        writeState()

        assertEquals(0, run("logout", "--json"), stderr())

        val r = json()
        assertTrue(r.getValue("ok").jsonPrimitive.boolean)
        assertEquals("work", r.getValue("profile").jsonPrimitive.content)
        assertTrue(r.getValue("credentials_removed").jsonPrimitive.boolean)
        assertTrue(r.getValue("revoked").jsonPrimitive.booleanOrNull == true)
        assertTrue(r.getValue("state_kept").jsonPrimitive.boolean)
        assertFalse(r.getValue("daemon_unauthenticated").jsonPrimitive.boolean)
        assertFalse(Files.exists(workDir.resolve("credentials.json")))
        assertFalse(Files.exists(workDir.resolve("device-flow.json")))
        assertTrue(Files.exists(workDir.resolve("state.db")), "the sync state survives a plain sign-out")
        assertTrue(Files.exists(workDir.resolve("failures.jsonl")))
    }

    @Test
    fun `delete-state is the asked-for destructive form`() {
        populate()
        writeState()

        assertEquals(0, run("logout", "--json", "--delete-state"), stderr())

        val r = json()
        assertFalse(r.getValue("state_kept").jsonPrimitive.boolean)
        assertFalse(Files.exists(workDir.resolve("state.db")))
        assertFalse(Files.exists(workDir.resolve("failures.jsonl")))
    }

    @Test
    fun `credentials_removed does not count the deleted state files`() {
        populate()
        writeState()
        Files.deleteIfExists(workDir.resolve("credentials.json"))
        Files.deleteIfExists(workDir.resolve("device-flow.json"))

        assertEquals(0, run("logout", "--json", "--delete-state"), stderr())

        val r = json()
        assertFalse(r.getValue("credentials_removed").jsonPrimitive.boolean)
        assertFalse(Files.exists(workDir.resolve("state.db")))
    }

    @Test
    fun `keep-state wins over delete-state`() {
        populate()
        writeState()

        assertEquals(0, run("logout", "--json", "--delete-state", "--keep-state"), stderr())

        assertTrue(Files.exists(workDir.resolve("state.db")))
    }

    @Test
    fun `uploads on their way refuse with the profile-remove token and counts`() {
        populate()
        writeState("/docs/a.txt" to false, "/docs/b.txt" to true, "/docs/c.txt" to false)

        assertEquals(1, run("logout", "--json"), stderr())
        assertEquals("uploads_pending", errorToken())
        assertEquals(3, json().getValue("pending").jsonPrimitive.int)
        assertEquals(1, json().getValue("failed").jsonPrimitive.int)
        assertTrue(Files.exists(workDir.resolve("credentials.json")), "nothing was removed")
    }

    @Test
    fun `force-pending signs a stopped account out anyway`() {
        populate()
        writeState("/docs/a.txt" to false)

        assertEquals(0, run("logout", "--json", "--force-pending"), stderr())
        assertTrue(json().getValue("ok").jsonPrimitive.boolean)
    }

    @Test
    fun `a state db that cannot be read is refused`() {
        populate()
        Files.writeString(workDir.resolve("state.db"), "this is not a database, and it is long enough to be read as one header")

        assertEquals(1, run("logout", "--json"), stderr())
        assertEquals("state_unreadable", errorToken())
        assertTrue(Files.exists(workDir.resolve("credentials.json")))
    }

    @Test
    fun `a profile held by a sync process refuses with profile_in_use`() {
        populate()
        holder = ProcessLock(workDir.resolve(".lock")).also { assertTrue(it.tryLock(ProcessLock.Mode.SYNC)) }

        assertEquals(1, run("logout", "--json"), stderr())
        assertEquals("profile_in_use", errorToken())
    }

    @Test
    fun `a running daemon signs the account out over IPC and keeps serving it unauthenticated`() {
        populate()
        writeState("/docs/a.txt" to true) // failed uploads alone do not block
        fakeDaemon()
        var requestedForce: Boolean? = null
        LogoutHooks.requestLogout = { endpoint, force ->
            requestedForce = force
            assertEquals("work", endpoint.profileName)
            Json.parseToJsonElement("""{"ok":true,"revoked":true,"credentials_removed":true}""").jsonObject
        }

        assertEquals(0, run("logout", "--json"), stderr())

        assertEquals(false, requestedForce)
        val r = json()
        assertTrue(r.getValue("ok").jsonPrimitive.boolean)
        assertTrue(r.getValue("revoked").jsonPrimitive.booleanOrNull == true)
        assertTrue(r.getValue("credentials_removed").jsonPrimitive.boolean)
        assertTrue(r.getValue("state_kept").jsonPrimitive.boolean)
        assertTrue(r.getValue("daemon_unauthenticated").jsonPrimitive.boolean)
        assertTrue(Files.exists(workDir.resolve("state.db")))
    }

    @Test
    fun `the daemon's pending refusal passes through`() {
        fakeDaemon()
        LogoutHooks.requestLogout = { _, _ ->
            Json.parseToJsonElement("""{"ok":false,"error":"uploads_pending","pending":2,"failed":0}""").jsonObject
        }

        assertEquals(1, run("logout", "--json"), stderr())
        assertEquals("uploads_pending", errorToken())
        assertEquals(2, json().getValue("pending").jsonPrimitive.int)
    }

    @Test
    fun `a daemon without the verb refuses with daemon_running`() {
        fakeDaemon()
        LogoutHooks.requestLogout = { _, _ ->
            Json.parseToJsonElement("""{"ok":false,"error":"unknown_verb"}""").jsonObject
        }

        assertEquals(1, run("logout", "--json"), stderr())
        assertEquals("daemon_running", errorToken())
    }

    @Test
    fun `delete-state against a running daemon refuses with daemon_running`() {
        fakeDaemon()
        LogoutHooks.requestLogout = { _, _ -> error("the sign-out must be refused before the daemon is asked") }

        assertEquals(1, run("logout", "--json", "--delete-state"), stderr())
        assertEquals("daemon_running", errorToken())
    }

    @Test
    fun `a daemon that stopped while it was asked leaves the local form to proceed`() {
        populate()
        val lock = ProcessLock(workDir.resolve(".lock"))
        assertTrue(lock.tryLock(ProcessLock.Mode.DAEMON))
        listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX).also {
            it.bind(UnixDomainSocketAddress.of(LogoutHooks.socketPath("work")))
        }
        LogoutHooks.requestLogout = { _, _ ->
            // The daemon stops between the holder check and the answer: it lets go of lock and socket.
            listener?.close()
            listener = null
            lock.unlock()
            null
        }

        assertEquals(0, run("logout", "--json"), stderr())
        assertTrue(json().getValue("ok").jsonPrimitive.boolean)
        assertFalse(Files.exists(workDir.resolve("credentials.json")))
    }

    @Test
    fun `a daemon that does not answer but keeps the profile refuses with daemon_running`() {
        populate()
        fakeDaemon()
        LogoutHooks.requestLogout = { _, _ -> null }

        assertEquals(1, run("logout", "--json"), stderr())
        assertEquals("daemon_running", errorToken())
        assertTrue(Files.exists(workDir.resolve("credentials.json")))
    }

    @Test
    fun `the console form keeps the state and names what it did`() {
        populate()
        writeState()

        assertEquals(0, run("logout"), stderr())

        assertTrue("Logged out of" in stdout(), stdout())
        assertTrue("Sync state preserved." in stdout(), stdout())
        assertTrue("Removed: " in stdout(), stdout())
        assertTrue(Files.exists(workDir.resolve("state.db")))
        assertFalse(Files.exists(workDir.resolve("credentials.json")))
    }

    @Test
    fun `an unknown profile refuses with profile_not_found`() {
        assertEquals(1, run("-p", "nope", "logout", "--json"), stderr())
        assertEquals("profile_not_found", errorToken())
    }
}
