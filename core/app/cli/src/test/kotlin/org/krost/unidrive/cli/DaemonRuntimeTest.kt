package org.krost.unidrive.cli

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.ProviderException
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.ScanProgress
import org.krost.unidrive.io.OwnerOnly
import org.krost.unidrive.io.grantProblem
import org.krost.unidrive.sync.ProfileMode
import org.krost.unidrive.sync.IpcAuth
import org.krost.unidrive.sync.IpcAuthClient
import org.krost.unidrive.sync.IpcAuthException
import org.krost.unidrive.sync.IpcEndpoint
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Spec test T1: `daemon_binds_socket_and_serves_hydration_verbs`.
 *
 * Pins the end-to-end happy path of the daemon lifecycle from
 * unidrive-daemon-design.md §3.2: ProcessLock(DAEMON) acquired →
 * StateDatabase opened → provider.authenticateAndLog() succeeded →
 * IpcServer bound → hydration.* handlers wired → graceful close.
 *
 * Does NOT use mockk — :app:cli has only kotlin-test on its test
 * classpath. The test stubs CloudProvider directly with a tiny inline
 * object since the only method T1 exercises is authenticateAndLog().
 */
class DaemonRuntimeTest {
    private lateinit var tempDir: Path
    private lateinit var lockFile: Path
    private lateinit var dbPath: Path
    private lateinit var socketPath: Path

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("daemon-runtime-test")
        lockFile = tempDir.resolve(".lock")
        dbPath = tempDir.resolve("state.db")
        socketPath = tempDir.resolve("daemon.sock")
    }

    @AfterTest
    fun tearDown() {
        runCatching { Files.deleteIfExists(socketPath) }
        runCatching { Files.deleteIfExists(dbPath) }
        runCatching { Files.deleteIfExists(lockFile) }
        runCatching { Files.deleteIfExists(lockFile.resolveSibling(".lock.pid")) }
        runCatching { tempDir.toFile().deleteRecursively() }
    }

    // The daemon writes its IPC tokens next to the lock (the profile folder); every client authenticates.
    private fun connect(scope: IpcAuth.Scope = IpcAuth.Scope.FULL): SocketChannel =
        IpcAuthClient.connect(IpcEndpoint(socketPath, tempDir, "test_profile"), scope)

    private fun rawConnect(): SocketChannel = SocketChannel.open(UnixDomainSocketAddress.of(socketPath))

    @Test
    fun `the daemon writes owner-only tokens before it listens and serves only authenticated connections`() =
        runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            val runtime = startDaemon(StubProvider())
            val daemonJob = launch { runtime.start() }
            try {
                awaitSocket()
                for (scope in IpcAuth.Scope.entries) {
                    val token = IpcAuth.tokenFile(tempDir, scope)
                    assertTrue(Files.exists(token), "token file $token must exist once the socket does")
                    assertEquals(null, OwnerOnly.grantProblem(token), "token file must be owner-only")
                }

                rawConnect().use { ch ->
                    ch.configureBlocking(false)
                    ch.write(ByteBuffer.wrap("{\"verb\":\"hydration.list\",\"prefix\":\"/\"}\n".toByteArray()))
                    assertEquals("""{"ok":false,"error":"auth_required"}""", readFirstReplyLine(ch, 5_000))
                    ch.write(ByteBuffer.wrap("{\"verb\":\"daemon.status\"}\n".toByteArray()))
                    val status = Json.parseToJsonElement(readFirstReplyLine(ch, 5_000)).jsonObject
                    // A client without a token file decides on exactly these two: version 2 = cannot
                    // authenticate, version 1 = a daemon without the handshake. Numbers and booleans, not strings.
                    assertEquals(setOf("ok", "protocol_version", "engine_version", "auth_required"), status.keys, "minimal before authentication")
                    assertEquals(kotlinx.serialization.json.JsonPrimitive(2), status.getValue("protocol_version"))
                    assertEquals(kotlinx.serialization.json.JsonPrimitive(true), status.getValue("auth_required"))
                    assertEquals(kotlinx.serialization.json.JsonPrimitive(true), status.getValue("ok"))
                }

                connect(IpcAuth.Scope.READ).use { ch ->
                    ch.configureBlocking(false)
                    ch.write(ByteBuffer.wrap("{\"verb\":\"daemon.shutdown\"}\n".toByteArray()))
                    assertEquals("""{"ok":false,"error":"forbidden","scope":"read"}""", readFirstReplyLine(ch, 5_000))
                    ch.write(ByteBuffer.wrap("{\"verb\":\"daemon.status\"}\n".toByteArray()))
                    assertTrue("\"uptime_ms\"" in readFirstReplyLine(ch, 5_000), "a read client gets the full status")
                }
            } finally {
                runtime.close()
                daemonJob.join()
            }
        }

    @Test
    fun `the daemon rotates its tokens at every start`() =
        runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            val first = startDaemon(StubProvider())
            val job1 = launch { first.start() }
            awaitSocket()
            val oldToken = Files.readString(IpcAuth.tokenFile(tempDir, IpcAuth.Scope.FULL))
            first.close()
            job1.join()

            val second = startDaemon(StubProvider())
            val job2 = launch { second.start() }
            try {
                awaitSocket()
                val newToken = Files.readString(IpcAuth.tokenFile(tempDir, IpcAuth.Scope.FULL))
                assertTrue(oldToken != newToken, "a new start writes new tokens")
                // A client still holding the previous token is refused; one that re-reads the file is not.
                val stale = Files.createDirectories(tempDir.resolve("stale"))
                Files.writeString(IpcAuth.tokenFile(stale, IpcAuth.Scope.FULL), oldToken)
                kotlin.test.assertFailsWith<IpcAuthException> {
                    IpcAuthClient.connect(IpcEndpoint(socketPath, stale, "test_profile"), IpcAuth.Scope.FULL)
                }
                connect().close()
            } finally {
                second.close()
                job2.join()
            }
        }

    @Test
    fun `a second start that loses the profile lock leaves the running daemon's tokens untouched`() =
        runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            val first = startDaemon(StubProvider())
            val job = launch { first.start() }
            try {
                awaitSocket()
                val files = IpcAuth.Scope.entries.map { IpcAuth.tokenFile(tempDir, it) }
                // Digests, so a failure message never shows a token.
                fun state() =
                    files.map {
                        java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(it)).joinToString("") { b -> "%02x".format(b) } to
                            Files.getLastModifiedTime(it)
                    }
                val before = state()

                // Same profile (lock), its own socket path: only the lock can stop it.
                var exitCode: Int? = null
                val secondSocket = tempDir.resolve("second.sock")
                val second =
                    DaemonRuntime(
                        profileMode = ProfileMode.MOUNT,
                        profileName = "test_profile",
                        lockFile = lockFile,
                        dbPath = dbPath,
                        syncRoot = tempDir,
                        socketPath = secondSocket,
                        providerFactory = { error("a start that lost the lock must not get this far") },
                        exitProcess = { exitCode = it },
                    )
                second.start()

                assertEquals(1, exitCode, "the second start is refused by the lock")
                assertEquals(before, state(), "token files untouched (content digest and mtime)")
                assertFalse(Files.exists(secondSocket))
                connect().close() // the running daemon's clients still authenticate
            } finally {
                first.close()
                job.join()
            }
        }

    @Test
    fun `a daemon that cannot write its token files refuses to start and never binds the socket`() =
        runBlocking {
            // A regular file where the profile folder should be: the token files cannot be created.
            val notAFolder = Files.writeString(tempDir.resolve("not-a-folder"), "x")
            val runtime =
                DaemonRuntime(
                    profileMode = ProfileMode.MOUNT,
                    profileName = "test_profile",
                    lockFile = lockFile,
                    dbPath = dbPath,
                    syncRoot = tempDir,
                    socketPath = socketPath,
                    providerFactory = { StubProvider() },
                    ipcTokenDir = notAFolder,
                )
            val stderr = java.io.ByteArrayOutputStream()
            val originalErr = System.err
            System.setErr(java.io.PrintStream(stderr, true, Charsets.UTF_8))
            val ex =
                try {
                    runCatching { runtime.start() }.exceptionOrNull()
                } finally {
                    System.setErr(originalErr)
                }

            assertTrue(ex is IpcAuth.StartupRefused, "got $ex")
            assertTrue(ex.message!!.startsWith("IPC startup refused: "), ex.message)
            assertTrue(notAFolder.toString() in ex.message!!, "the line names the path: ${ex.message}")
            val lines = stderr.toString(Charsets.UTF_8).lines().filter { it.startsWith("IPC startup refused:") }
            assertEquals(listOf(ex.message), lines, "exactly one refusal line on stderr")
            assertFalse(Files.exists(socketPath), "the socket is never bound without its tokens")
            assertFalse(Files.exists(lockFile.resolveSibling("${lockFile.fileName}.pid")), "the lock is released")
            assertEquals(78, IpcAuth.STARTUP_REFUSED_EXIT_CODE)
        }

    @Test
    fun daemon_fail_fast_on_auth_failure_does_not_bind_socket() = runBlocking {
        // Spec T4: provider.authenticate() throws → daemon refuses to bind
        // the socket, releases the lock, exits non-zero.
        val provider: CloudProvider = AuthFailingStubProvider()

        val runtime = DaemonRuntime(
            profileMode = ProfileMode.MOUNT,
            profileName = "test_profile",
            lockFile = lockFile,
            dbPath = dbPath,
            syncRoot = tempDir,
            socketPath = socketPath,
            providerFactory = { provider },
        )

        // start() rethrows the auth exception (per DaemonRuntime catch+rethrow).
        // For T4 to be cleanly testable, DaemonRuntime.start() must propagate
        // auth exceptions BEFORE the socket is bound. The catch block in start()
        // rethrows after cleanup() — that's the design contract this test pins.
        val ex = kotlin.runCatching {
            runtime.start()
        }.exceptionOrNull()

        assertTrue(
            ex is org.krost.unidrive.AuthenticationException ||
                ex?.cause is org.krost.unidrive.AuthenticationException,
            "auth failure must propagate; got: $ex",
        )

        // Invariant I4: socket file must NOT exist after auth failure.
        assertTrue(
            !Files.exists(socketPath),
            "socket file must not be left behind after auth failure; found $socketPath",
        )

        // Lock must be released — the .lock.pid sidecar should be gone.
        val pidFile = lockFile.resolveSibling("${lockFile.fileName}.pid")
        assertTrue(
            !Files.exists(pidFile),
            ".lock.pid sidecar must be cleaned up after auth failure; found $pidFile",
        )
    }

    @Test
    fun daemon_binds_socket_and_serves_hydration_verbs() = runBlocking {
        val provider: CloudProvider = StubProvider()

        val runtime = DaemonRuntime(
            profileMode = ProfileMode.MOUNT,
            profileName = "test_profile",
            lockFile = lockFile,
            dbPath = dbPath,
            syncRoot = tempDir,
            socketPath = socketPath,
            providerFactory = { provider },
        )

        val daemonJob = launch { runtime.start() }

        repeat(50) {
            if (Files.exists(socketPath)) return@repeat
            delay(50)
        }
        assertTrue(Files.exists(socketPath), "socket must be bound within 2.5s")

        val channel = connect()
        try {
            // hydration.list takes "prefix", not "path" (cf. HydrationIpcHandler.handle()).
            // On a brand-new empty StateDatabase, list("/") returns Ok(emptyList()) → ok:true.
            val req = """{"verb":"hydration.list","prefix":"/"}""" + "\n"
            channel.write(ByteBuffer.wrap(req.toByteArray()))
            val buf = ByteBuffer.allocate(4096)
            channel.read(buf)
            buf.flip()
            val reply = String(buf.array(), 0, buf.limit())
            assertTrue(reply.contains("\"ok\":true"), "expected ok:true reply; got: $reply")
        } finally {
            channel.close()
        }

        runtime.close()
        daemonJob.join()
    }

    /**
     * A request for every documented verb, sent the instant the socket file
     * appears, must get a reply line. Pins the startup ordering: the daemon
     * binds the socket only after all handlers are registered, so there is no
     * window in which an accepted request is dropped ("no handler") and the
     * sender hangs. An unknown verb must also be answered (unknown_verb), not
     * dropped. daemon.shutdown goes last because its ack stops the daemon.
     */
    @Test
    fun verbs_sent_as_soon_as_the_socket_appears_all_get_a_reply() =
        runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            val runtime = DaemonRuntime(
                profileMode = ProfileMode.MOUNT,
                profileName = "test_profile",
                lockFile = lockFile,
                dbPath = dbPath,
                syncRoot = tempDir,
                socketPath = socketPath,
                providerFactory = { StubProvider() },
            )
            val daemonJob = launch { runtime.start() }
            try {
                repeat(100) {
                    if (Files.exists(socketPath)) return@repeat
                    delay(50)
                }
                assertTrue(Files.exists(socketPath), "socket must be bound within 5s")

                // Each documented verb with its minimal real request. Distinct
                // paths so state-mutating verbs (create, open_write_begin) never
                // interact; every one of these is answered by a registered
                // handler, whether the outcome is ok or a typed error.
                val requests = listOf(
                    """{"verb":"daemon.status"}""",
                    """{"verb":"hydration.open_read","handle_id":"h1","path":"/startup/read.txt"}""",
                    // The cache path is a JSON string: a Windows path's backslashes must be escaped.
                    """{"verb":"hydration.open_write","handle_id":"h2","path":"/startup/write.txt","cache_path":${kotlinx.serialization.json.JsonPrimitive(tempDir.resolve("write-cache.bin").toString())}}""",
                    """{"verb":"hydration.open_write_begin","path":"/startup/truncate.txt"}""",
                    """{"verb":"hydration.close_handle","handle_id":"h3"}""",
                    """{"verb":"hydration.hydrate","path":"/startup/hydrate.txt"}""",
                    """{"verb":"hydration.dehydrate","path":"/startup/dehydrate.txt"}""",
                    """{"verb":"hydration.subscribe"}""",
                    """{"verb":"hydration.last_synced","path":"/startup/ls.txt"}""",
                    """{"verb":"hydration.list","prefix":"/"}""",
                    """{"verb":"hydration.mkdir","path":"/startup"}""",
                    """{"verb":"hydration.unlink","path":"/startup/unlink.txt"}""",
                    """{"verb":"hydration.rmdir","path":"/startup/rmdir.txt"}""",
                    """{"verb":"hydration.cancel","path":"/startup/cancel.txt"}""",
                    """{"verb":"hydration.create","handle_id":"h4","path":"/startup/create.txt"}""",
                    """{"verb":"hydration.rename","old_path":"/startup/r1.txt","new_path":"/startup/r2.txt"}""",
                    """{"verb":"sync.subscribe"}""",
                    """{"verb":"refresh.run"}""",
                    """{"verb":"sync.enumerate"}""",
                    // No handler exists for this verb; the client must still learn that.
                    """{"verb":"daemon.statusx"}""",
                    """{"verb":"daemon.shutdown"}""",
                )
                // The IPC verb classes (IpcAuth) name exactly these verbs: each reaches a handler below,
                // so the class table holds no verb the daemon does not register, and none is missing.
                // (A pattern, not a JSON parse: the open_write line carries a raw Windows path there.)
                assertEquals(
                    IpcAuth.VERB_CLASSES.keys,
                    requests.map { Regex(""""verb":"([^"]+)"""").find(it)!!.groupValues[1] }.toSet() - "daemon.statusx",
                )
                for (request in requests) {
                    val channel = connect()
                    try {
                        channel.configureBlocking(false)
                        val req = request + "\n"
                        val w = ByteBuffer.wrap(req.toByteArray(Charsets.UTF_8))
                        while (w.hasRemaining()) channel.write(w)
                        val reply = readFirstReplyLine(channel, timeoutMs = 5_000)
                        val json = kotlinx.serialization.json.Json.parseToJsonElement(reply)
                        assertTrue(
                            json is kotlinx.serialization.json.JsonObject && json.containsKey("ok"),
                            "verb must be answered with a reply carrying ok; sent $request, got: $reply",
                        )
                        // A reply carrying ok is not enough: the server's own fallback for a
                        // verb with no handler is {"ok":false,"error":"unknown_verb"}, so with
                        // the socket bound before the handlers were registered every documented
                        // verb would still "get a reply" and this test would stay green. A
                        // documented verb must reach its handler; only the made-up one is unknown.
                        val error = json["error"]
                            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                        if (request.contains("daemon.statusx")) {
                            assertTrue(error == "unknown_verb", "an unregistered verb must be answered unknown_verb, got: $reply")
                        } else {
                            assertTrue(
                                error != "unknown_verb" && error != "missing_verb",
                                "documented verb reached no handler (socket bound before registration?); sent $request, got: $reply",
                            )
                        }
                    } finally {
                        channel.close()
                    }
                }
            } finally {
                runtime.close()
                daemonJob.join()
            }
        }

    /** Reads one \n-terminated reply line, failing loudly when none arrives in [timeoutMs]. */
    private suspend fun readFirstReplyLine(channel: SocketChannel, timeoutMs: Long): String {
        val collected = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !collected.contains('\n')) {
            val buf = ByteBuffer.allocate(8192)
            val n = channel.read(buf)
            if (n > 0) {
                buf.flip()
                collected.append(String(buf.array(), 0, buf.limit()))
            } else {
                delay(20)
            }
        }
        val newline = collected.indexOf('\n')
        check(newline >= 0) { "no reply line within ${timeoutMs}ms; collected: $collected" }
        return collected.substring(0, newline)
    }

    @Test
    fun `#419 graceful shutdown removes the socket file and its meta sibling`() = runBlocking {
        // IpcServer.defaultSocketPath writes this `.meta` next to a hashed socket name so a UI can
        // recover the profile name; a stopped daemon must not leave it (or the socket) behind.
        val metaPath = socketPath.resolveSibling("${socketPath.fileName}.meta")
        Files.writeString(metaPath, "test_profile\n")

        val runtime = DaemonRuntime(
            profileMode = ProfileMode.MOUNT,
            profileName = "test_profile",
            lockFile = lockFile,
            dbPath = dbPath,
            syncRoot = tempDir,
            socketPath = socketPath,
            providerFactory = { StubProvider() },
        )

        val daemonJob = launch { runtime.start() }
        repeat(50) {
            if (Files.exists(socketPath)) return@repeat
            delay(50)
        }
        assertTrue(Files.exists(socketPath), "socket must be bound within 2.5s")

        runtime.close()
        daemonJob.join()

        assertFalse(Files.exists(socketPath), "socket file must be gone after the daemon stopped")
        assertFalse(Files.exists(metaPath), "meta file must be gone after the daemon stopped")
    }

    @Test
    fun `shutdownAndWait returns only after cleanup has released the socket and the lock`() = runBlocking {
        val runtime = DaemonRuntime(
            profileMode = ProfileMode.MOUNT,
            profileName = "test_profile",
            lockFile = lockFile,
            dbPath = dbPath,
            syncRoot = tempDir,
            socketPath = socketPath,
            providerFactory = { StubProvider() },
        )
        // Dispatchers.IO: shutdownAndWait blocks its caller, so start() must not share runBlocking's thread.
        val daemonJob = launch(kotlinx.coroutines.Dispatchers.IO) { runtime.start() }
        // Startup is fixture setup; the shutdown deadline below is the behavior under test.
        kotlinx.coroutines.withTimeout(30_000) {
            while (!Files.exists(socketPath)) {
                check(!daemonJob.isCompleted) { "daemon stopped before binding its socket" }
                delay(50)
            }
        }
        val pidFile = lockFile.resolveSibling("${lockFile.fileName}.pid")
        assertTrue(Files.exists(pidFile), "daemon must hold the lock before shutdown")

        // What the JVM shutdown hook does: the hook thread must not return (letting the JVM halt)
        // before the main thread's cleanup has finished.
        val clean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            runtime.shutdownAndWait(DaemonRuntime.SHUTDOWN_DEADLINE_MS)
        }

        assertTrue(clean, "shutdownAndWait must report that cleanup completed within the deadline")
        assertFalse(Files.exists(socketPath), "socket file must be gone when shutdownAndWait returns")
        assertFalse(Files.exists(pidFile), ".lock.pid must be gone when shutdownAndWait returns")
        daemonJob.join()
    }

    @Test
    fun `shutdownAndWait on a runtime that never started does not block`() {
        val runtime = DaemonRuntime(
            profileMode = ProfileMode.MOUNT,
            profileName = "test_profile",
            lockFile = lockFile,
            dbPath = dbPath,
            syncRoot = tempDir,
            socketPath = socketPath,
            providerFactory = { StubProvider() },
        )
        val t0 = System.nanoTime()
        assertTrue(runtime.shutdownAndWait(10_000))
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2_000, "must return immediately when nothing was started")
    }

    @Test
    fun `daemon_shutdown verb acks then runs the clean shutdown path`() = runBlocking {
        val runtime = DaemonRuntime(
            profileMode = ProfileMode.MOUNT,
            profileName = "test_profile",
            lockFile = lockFile,
            dbPath = dbPath,
            syncRoot = tempDir,
            socketPath = socketPath,
            providerFactory = { StubProvider() },
        )
        val daemonJob = launch { runtime.start() }
        repeat(50) {
            if (Files.exists(socketPath)) return@repeat
            delay(50)
        }
        assertTrue(Files.exists(socketPath), "socket must be bound within 2.5s")
        val pidFile = lockFile.resolveSibling("${lockFile.fileName}.pid")
        assertTrue(Files.exists(pidFile), "daemon must hold the lock before shutdown")

        val reply = sendOneRequest("""{"verb":"daemon.shutdown"}""")
        assertTrue(reply.contains("\"ok\":true"), "daemon.shutdown must ack before stopping; got: $reply")

        // start() returning means the serve scope was cancelled and cleanup() ran.
        kotlinx.coroutines.withTimeout(10_000) { daemonJob.join() }
        assertFalse(Files.exists(socketPath), "socket file must be gone after daemon.shutdown")
        assertFalse(Files.exists(pidFile), ".lock.pid must be gone after daemon.shutdown")
    }

    @Test
    fun `daemon_status reports the effective scope`() = runBlocking {
        val runtime = DaemonRuntime(
            profileMode = ProfileMode.MOUNT,
            profileName = "test_profile",
            lockFile = lockFile,
            dbPath = dbPath,
            syncRoot = tempDir,
            socketPath = socketPath,
            providerFactory = { StubProvider() },
            syncPaths = listOf("/_INBOX", "/Docs \"x\""),
        )
        val daemonJob = launch { runtime.start() }
        repeat(50) {
            if (Files.exists(socketPath)) return@repeat
            delay(50)
        }
        assertTrue(Files.exists(socketPath), "socket must be bound within 2.5s")
        try {
            val reply = sendOneRequest("""{"verb":"daemon.status"}""")
            val scope = kotlinx.serialization.json.Json.parseToJsonElement(reply)
                .let { it as kotlinx.serialization.json.JsonObject }["sync_paths"]
                .let { it as kotlinx.serialization.json.JsonArray }
                .map { (it as kotlinx.serialization.json.JsonPrimitive).content }
            assertEquals(listOf("/_INBOX", "/Docs \"x\""), scope, "daemon.status must list the scope as sync_paths; got: $reply")
        } finally {
            runtime.close()
            daemonJob.join()
        }
    }

    // #463: a status client shows how often the daemon polls the cloud (0 = off).
    @Test
    fun `daemon_status reports the effective poll interval`() = runBlocking {
        val runtime = DaemonRuntime(
            profileMode = ProfileMode.MOUNT,
            profileName = "test_profile",
            lockFile = lockFile,
            dbPath = dbPath,
            syncRoot = tempDir,
            socketPath = socketPath,
            providerFactory = { StubProvider() },
            pollIntervalMs = 60_000,
        )
        val daemonJob = launch { runtime.start() }
        repeat(50) {
            if (Files.exists(socketPath)) return@repeat
            delay(50)
        }
        assertTrue(Files.exists(socketPath), "socket must be bound within 2.5s")
        try {
            val reply = sendOneRequest("""{"verb":"daemon.status"}""")
            val interval = kotlinx.serialization.json.Json.parseToJsonElement(reply)
                .let { it as kotlinx.serialization.json.JsonObject }["poll_interval_ms"]
                .let { (it as kotlinx.serialization.json.JsonPrimitive).content }
            assertEquals("60000", interval, "daemon.status must report poll_interval_ms; got: $reply")
        } finally {
            runtime.close()
            daemonJob.join()
        }
    }

    private fun sendOneRequest(request: String): String {
        val channel = connect()
        try {
            channel.configureBlocking(false)
            channel.write(ByteBuffer.wrap((request + "\n").toByteArray()))
            val collected = StringBuilder()
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline && !collected.contains('\n')) {
                val buf = ByteBuffer.allocate(4096)
                val n = channel.read(buf)
                if (n > 0) {
                    buf.flip()
                    collected.append(String(buf.array(), 0, buf.limit()))
                } else {
                    Thread.sleep(20)
                }
            }
            check(collected.contains('\n')) { "no reply line within 5s; collected: $collected" }
            return collected.toString().substringBefore('\n')
        } finally {
            channel.close()
        }
    }

    @Test
    fun refresh_run_emits_terminal_event_after_completing_enumeration() = runBlocking {
        // Spec T5: subscribe -> refresh.run -> await refresh.done terminal event.
        // Then re-issue refresh.run; must succeed (not stuck in 'busy').
        val provider: CloudProvider = StubProvider()

        val runtime = DaemonRuntime(
            profileMode = ProfileMode.MOUNT,
            profileName = "test_profile",
            lockFile = lockFile,
            dbPath = dbPath,
            syncRoot = tempDir,
            socketPath = socketPath,
            providerFactory = { provider },
        )

        val daemonJob = launch { runtime.start() }

        repeat(50) {
            if (Files.exists(socketPath)) return@repeat
            delay(50)
        }
        assertTrue(Files.exists(socketPath), "socket must be bound within 2.5s")

        val channel = connect()
        try {
            // Non-blocking reads so the deadline loop below actually polls the
            // deadline. With blocking reads, a read with nothing buffered hangs
            // forever and the test JVM never exits even on assertion failure.
            channel.configureBlocking(false)
            // Subscribe first
            channel.write(ByteBuffer.wrap(("""{"verb":"sync.subscribe"}""" + "\n").toByteArray()))
            val subReply = readUntil(channel, "\"ok\":true", timeoutMs = 5_000)
            assertTrue(subReply.contains("\"ok\":true"), "subscribe must succeed; got: $subReply")

            // Issue refresh.run
            channel.write(ByteBuffer.wrap(("""{"verb":"refresh.run"}""" + "\n").toByteArray()))

            // Read until we see the terminal event "refresh.done"
            val collected = readUntil(channel, "refresh.done", timeoutMs = 10_000)
            assertTrue(
                collected.contains("\"event\":\"refresh.done\""),
                "expected refresh.done terminal event within 10s; got: $collected",
            )
            assertTrue(
                collected.contains("\"ok\":true"),
                "expected ok:true on terminal event; got: $collected",
            )

            // Issue refresh.run again - must NOT be 'busy' since first one completed.
            channel.write(ByteBuffer.wrap(("""{"verb":"refresh.run"}""" + "\n").toByteArray()))
            val secondReply = readUntil(channel, "\"job_id\"", timeoutMs = 5_000)
            assertTrue(
                secondReply.contains("\"ok\":true"),
                "second refresh.run must succeed (not busy); got: $secondReply",
            )
        } finally {
            channel.close()
        }

        runtime.close()
        daemonJob.join()
    }

    @Test
    fun view_invalidated_pushed_on_detected_remote_change_after_subscribe() = runBlocking {
        // Reactive-freshness invariant: when the FUSE co-daemon issues hydration.subscribe
        // on mount, the daemon runs ONE reactive enumerate; because the provider's delta
        // reports a remote file, state.db is mutated and the engine pushes a view.invalidated
        // event back over the SAME subscribe stream — without any manual `refresh` or poll loop.
        val provider: CloudProvider = OneRemoteFileStubProvider()

        val runtime = DaemonRuntime(
            profileMode = ProfileMode.MOUNT,
            profileName = "test_profile",
            lockFile = lockFile,
            dbPath = dbPath,
            syncRoot = tempDir,
            socketPath = socketPath,
            providerFactory = { provider },
        )

        val daemonJob = launch { runtime.start() }
        repeat(50) {
            if (Files.exists(socketPath)) return@repeat
            delay(50)
        }
        assertTrue(Files.exists(socketPath), "socket must be bound within 2.5s")

        val channel = connect()
        try {
            channel.configureBlocking(false)
            // Subscribe via hydration.subscribe (the verb the co-daemon issues on mount).
            channel.write(ByteBuffer.wrap(("""{"verb":"hydration.subscribe"}""" + "\n").toByteArray()))
            // First line is the subscribe ok-reply; the view.invalidated event follows
            // post-reply once the auto-enumerate mutates state.db.
            val collected = readUntil(channel, "view.invalidated", timeoutMs = 10_000)
            assertTrue(
                collected.contains("\"event\":\"view.invalidated\""),
                "expected a view.invalidated push after subscribe-triggered enumerate; got: $collected",
            )
            assertTrue(
                collected.contains("/remote-new.txt"),
                "view.invalidated must name the newly-enumerated path; got: $collected",
            )
        } finally {
            channel.close()
        }

        runtime.close()
        daemonJob.join()
    }

    @Test
    fun daemon_status_returns_uptime_clients_and_refresh_state() = runBlocking {
        val provider: CloudProvider = StubProvider()

        val runtime = DaemonRuntime(
            profileMode = ProfileMode.MOUNT,
            profileName = "test_profile",
            lockFile = lockFile,
            dbPath = dbPath,
            syncRoot = tempDir,
            socketPath = socketPath,
            providerFactory = { provider },
        )

        val daemonJob = launch { runtime.start() }
        repeat(50) {
            if (Files.exists(socketPath)) return@repeat
            delay(50)
        }
        assertTrue(Files.exists(socketPath), "socket must be bound")

        val channel = connect()
        channel.configureBlocking(false)
        try {
            channel.write(ByteBuffer.wrap(("""{"verb":"daemon.status"}""" + "\n").toByteArray()))
            val reply = readUntil(channel, "\"ok\"", timeoutMs = 5_000L)
            assertTrue(reply.contains("\"ok\":true"), "expected ok:true; got: $reply")
            assertTrue(
                reply.contains("\"protocol_version\":${DaemonRuntime.IPC_PROTOCOL_VERSION}"),
                "expected protocol_version handshake field; got: $reply",
            )
            assertTrue(reply.contains("\"uptime_ms\""), "expected uptime_ms field; got: $reply")
            assertTrue(reply.contains("\"clients_connected\""), "expected clients_connected; got: $reply")
            assertTrue(reply.contains("\"refresh_in_flight\":false"), "expected refresh_in_flight:false; got: $reply")
            assertTrue(reply.contains("\"refresh_job_id\":null"), "expected refresh_job_id:null; got: $reply")
            assertTrue(reply.contains("\"provider\":\"stub\""), "expected provider id; got: $reply")
            assertTrue(reply.contains("\"provider_name\":\"Stub\""), "expected provider display name; got: $reply")
            assertTrue(reply.contains("\"authenticated\":true"), "expected auth state; got: $reply")
        } finally {
            channel.close()
        }

        runtime.close()
        daemonJob.join()
    }

    /**
     * Poll [channel] (configured non-blocking) into a StringBuilder until either
     * [needle] appears in collected bytes or [timeoutMs] elapses. Used by T5 to
     * avoid the blocking-read hang that would otherwise prevent the test JVM
     * from exiting on assertion failure or daemon stall.
     */
    private suspend fun readUntil(
        channel: SocketChannel,
        needle: String,
        timeoutMs: Long,
    ): String {
        val collected = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !collected.contains(needle)) {
            val buf = ByteBuffer.allocate(4096)
            val n = channel.read(buf)
            if (n > 0) {
                buf.flip()
                collected.append(String(buf.array(), 0, buf.limit()))
            } else {
                delay(20)
            }
        }
        return collected.toString()
    }

    // ---- the enumeration object of daemon.status -----------------------------------------------------------------------

    private fun enumerationOf(reply: String): JsonObject = Json.parseToJsonElement(reply).jsonObject.getValue("enumeration").jsonObject

    private fun startDaemon(provider: CloudProvider) =
        DaemonRuntime(
            profileMode = ProfileMode.MOUNT,
            profileName = "test_profile",
            lockFile = lockFile,
            dbPath = dbPath,
            syncRoot = tempDir,
            socketPath = socketPath,
            providerFactory = { provider },
        )

    private suspend fun awaitSocket() {
        repeat(100) {
            if (Files.exists(socketPath)) return
            delay(50)
        }
        assertTrue(Files.exists(socketPath), "socket must be bound within 5s")
    }

    // Polls daemon.status until its enumeration object satisfies [predicate]; fails with the last reply otherwise.
    private suspend fun awaitEnumeration(
        timeoutMs: Long = 10_000,
        predicate: (JsonObject) -> Boolean,
    ): JsonObject {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last = ""
        while (System.currentTimeMillis() < deadline) {
            last = sendOneRequest("""{"verb":"daemon.status"}""")
            enumerationOf(last).let { if (predicate(it)) return it }
            delay(50)
        }
        error("the enumeration object never matched; last daemon.status reply: $last")
    }

    @Test
    fun `daemon_status carries an enumeration object that is idle before any enumeration and leaves the other fields alone`() = runBlocking {
        val runtime = startDaemon(StubProvider())
        val daemonJob = launch { runtime.start() }
        awaitSocket()
        try {
            val reply = sendOneRequest("""{"verb":"daemon.status"}""")

            val status = Json.parseToJsonElement(reply).jsonObject
            assertEquals(DaemonRuntime.IPC_PROTOCOL_VERSION, status.getValue("protocol_version").jsonPrimitive.int)
            assertEquals("stub", status.getValue("provider").jsonPrimitive.content)
            assertEquals("Stub", status.getValue("provider_name").jsonPrimitive.content)
            assertTrue(status.getValue("authenticated").jsonPrimitive.boolean)
            assertFalse(status.getValue("refresh_in_flight").jsonPrimitive.boolean)
            assertTrue(status.getValue("uptime_ms").jsonPrimitive.long >= 0)
            val enumeration = status.getValue("enumeration").jsonObject
            assertEquals(setOf("state", "first", "attempt"), enumeration.keys, "nothing else is known before a run: $reply")
            assertEquals("idle", enumeration.getValue("state").jsonPrimitive.content)
            assertTrue(enumeration.getValue("first").jsonPrimitive.boolean, "no enumeration has completed")
            assertEquals(0, enumeration.getValue("attempt").jsonPrimitive.int)
        } finally {
            runtime.close()
            daemonJob.join()
        }
    }

    @Test
    fun `daemon_status follows an enumeration from running to done`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val runtime = startDaemon(ScriptedDeltaProvider(gate = gate))
        val daemonJob = launch { runtime.start() }
        awaitSocket()
        try {
            val started = sendOneRequest("""{"verb":"sync.enumerate"}""")
            assertTrue(started.contains("\"ok\":true"), started)

            val running = awaitEnumeration { it.getValue("state").jsonPrimitive.content == "running" && "items" in it }
            assertEquals("listing", running.getValue("phase").jsonPrimitive.content)
            assertEquals("tree", running.getValue("listing").jsonPrimitive.content)
            assertTrue(running.getValue("first").jsonPrimitive.boolean)
            assertEquals(1, running.getValue("attempt").jsonPrimitive.int)
            assertEquals(1_234, running.getValue("items").jsonPrimitive.int)
            assertEquals(10, running.getValue("folders_done").jsonPrimitive.int)
            assertEquals(40, running.getValue("folders_known").jsonPrimitive.int)
            assertEquals(1, running.getValue("folders_skipped").jsonPrimitive.int)
            assertTrue(running.getValue("started_at_ms").jsonPrimitive.long > 0)
            assertTrue(running.getValue("elapsed_ms").jsonPrimitive.long >= 0)
            assertFalse("last_success_at_ms" in running)

            gate.complete(Unit)
            val done = awaitEnumeration { it.getValue("state").jsonPrimitive.content == "idle" && "last_success_at_ms" in it }
            assertFalse(done.getValue("first").jsonPrimitive.boolean, "the cursor is promoted: the view is complete")
            assertEquals(0, done.getValue("attempt").jsonPrimitive.int)
            assertTrue(done.getValue("last_success_at_ms").jsonPrimitive.long > 0)
            assertFalse("items" in done, "an idle enumeration shows no progress")
            assertFalse("phase" in done)
        } finally {
            gate.complete(Unit)
            runtime.close()
            daemonJob.join()
        }
    }

    @Test
    fun `daemon_status reports a failed enumeration with a reason that names no path`() = runBlocking {
        val runtime = startDaemon(ScriptedDeltaProvider(failure = ProviderException("cannot list /Secret/Plans: boom")))
        val daemonJob = launch { runtime.start() }
        awaitSocket()
        try {
            sendOneRequest("""{"verb":"sync.enumerate"}""")
            val failed = awaitEnumeration { it.getValue("state").jsonPrimitive.content == "failed" }

            assertEquals(1, failed.getValue("attempt").jsonPrimitive.int)
            assertEquals("cannot list <path>: boom", failed.getValue("last_error").jsonPrimitive.content)
            assertTrue(failed.getValue("first").jsonPrimitive.boolean, "the view is still incomplete")
            assertFalse("next_attempt_at_ms" in failed, "no poller runs, so nothing is scheduled")
            assertFalse("phase" in failed)

            sendOneRequest("""{"verb":"sync.enumerate"}""")
            val again = awaitEnumeration { it.getValue("state").jsonPrimitive.content == "failed" && it.getValue("attempt").jsonPrimitive.int == 2 }
            assertEquals(2, again.getValue("attempt").jsonPrimitive.int, "attempts since the last success")
        } finally {
            runtime.close()
            daemonJob.join()
        }
    }

    /**
     * Minimal CloudProvider stub. Only authenticateAndLog() (via authenticate())
     * is exercised by T1's happy-path lifecycle; the rest must compile but never
     * runs in this test.
     */
    private open class StubProvider : CloudProvider {
        override val id: String = "stub"
        override val displayName: String = "Stub"
        override var isAuthenticated: Boolean = true

        override fun capabilities(): Set<Capability> = emptySet()

        override suspend fun authenticate() { /* no-op: already authenticated */ }

        override suspend fun listChildren(path: String): List<CloudItem> = emptyList()

        override suspend fun getMetadata(path: String): CloudItem =
            error("not used in T1")

        override suspend fun download(
            remotePath: String,
            destination: Path,
        ): Long = error("not used in T1")

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            ifMatchETag: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem = error("not used in T1")

        override suspend fun delete(remotePath: String, ifMatchETag: String?) = error("not used in T1")

        override suspend fun createFolder(path: String): CloudItem = error("not used in T1")

        override suspend fun move(fromPath: String, toPath: String): CloudItem =
            error("not used in T1")

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((Int) -> Unit)?,
            scanContext: org.krost.unidrive.ScanContext?,
        ): DeltaPage = DeltaPage(items = emptyList(), cursor = "x", hasMore = false)

        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0L, used = 0L, remaining = 0L)
    }

    /**
     * A provider whose listing reports tree progress to the engine, then waits for [gate] and fails with
     * [failure] or ends with an empty page: what a long first enumeration looks like from outside.
     */
    private class ScriptedDeltaProvider(
        private val gate: CompletableDeferred<Unit>? = null,
        private val failure: Exception? = null,
    ) : StubProvider() {
        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((Int) -> Unit)?,
            scanContext: org.krost.unidrive.ScanContext?,
        ): DeltaPage {
            scanContext?.onProgress?.invoke(
                ScanProgress(items = 1_234, foldersDone = 10, foldersKnown = 40, foldersSkipped = 1, listing = ScanProgress.LISTING_TREE),
            )
            gate?.await()
            failure?.let { throw it }
            return DeltaPage(items = emptyList(), cursor = "cursor-1", hasMore = false)
        }
    }

    /**
     * Stub that throws AuthenticationException from authenticate().
     * Used by T4 to pin the fail-fast contract: socket must NOT be bound
     * when auth fails.
     */
    private class AuthFailingStubProvider : CloudProvider {
        override val id: String = "stub-auth-failing"
        override val displayName: String = "Stub (auth fails)"
        override var isAuthenticated: Boolean = false

        override fun capabilities(): Set<Capability> = emptySet()

        override suspend fun authenticate() {
            throw org.krost.unidrive.AuthenticationException("test auth failure (T4)")
        }

        override suspend fun listChildren(path: String): List<CloudItem> = emptyList()

        override suspend fun getMetadata(path: String): CloudItem =
            error("not used in T4")

        override suspend fun download(
            remotePath: String,
            destination: Path,
        ): Long = error("not used in T4")

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            ifMatchETag: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem = error("not used in T4")

        override suspend fun delete(remotePath: String, ifMatchETag: String?) = error("not used in T4")

        override suspend fun createFolder(path: String): CloudItem = error("not used in T4")

        override suspend fun move(fromPath: String, toPath: String): CloudItem =
            error("not used in T4")

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((Int) -> Unit)?,
            scanContext: org.krost.unidrive.ScanContext?,
        ): DeltaPage = DeltaPage(items = emptyList(), cursor = "x", hasMore = false)

        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0L, used = 0L, remaining = 0L)
    }

    /**
     * Stub whose delta reports a single remote file on a complete (hasMore=false)
     * enumeration. Used by the reactive-freshness test to make the subscribe-triggered enumerate
     * mutate state.db so a view.invalidated event fires on the subscribe stream.
     */
    private class OneRemoteFileStubProvider : CloudProvider {
        override val id: String = "stub-one-file"
        override val displayName: String = "Stub (one remote file)"
        override var isAuthenticated: Boolean = true

        override fun capabilities(): Set<Capability> = emptySet()

        override suspend fun authenticate() { /* no-op */ }

        override suspend fun listChildren(path: String): List<CloudItem> = emptyList()

        override suspend fun getMetadata(path: String): CloudItem = error("not used")

        override suspend fun download(remotePath: String, destination: Path): Long = error("not used")

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            ifMatchETag: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem = error("not used")

        override suspend fun delete(remotePath: String, ifMatchETag: String?) = error("not used")

        override suspend fun createFolder(path: String): CloudItem = error("not used")

        override suspend fun move(fromPath: String, toPath: String): CloudItem = error("not used")

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((Int) -> Unit)?,
            scanContext: org.krost.unidrive.ScanContext?,
        ): DeltaPage =
            DeltaPage(
                items = listOf(
                    CloudItem(
                        id = "remote-1",
                        name = "remote-new.txt",
                        path = "/remote-new.txt",
                        size = 7L,
                        isFolder = false,
                        modified = java.time.Instant.now(),
                        created = java.time.Instant.now(),
                        hash = "abc123",
                        mimeType = "text/plain",
                    ),
                ),
                cursor = "cursor-1",
                hasMore = false,
            )

        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0L, used = 0L, remaining = 0L)
    }
}
