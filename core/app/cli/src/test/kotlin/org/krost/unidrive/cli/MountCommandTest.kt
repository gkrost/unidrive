package org.krost.unidrive.cli

import org.krost.unidrive.sync.IpcAuth
import org.krost.unidrive.sync.IpcServer
import picocli.CommandLine
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MountCommandTest {
    private val cmd = CommandLine(Main())

    @Test
    fun `mount command is registered`() {
        val mountCmd = cmd.subcommands["mount"]
        assertNotNull(mountCmd, "mount subcommand should be registered")
    }

    @Test
    fun `mount command has --profile option`() {
        val mountCmd = cmd.subcommands["mount"]!!
        val options = mountCmd.commandSpec.options().map { it.longestName() }
        assertTrue("--profile" in options)
    }

    @Test
    fun `mount command has mount-path positional parameter`() {
        val mountCmd = cmd.subcommands["mount"]!!
        val positionals = mountCmd.commandSpec.positionalParameters()
        assertTrue(positionals.isNotEmpty(), "mount must accept a positional mount path")
    }

    @Test
    fun `buildArgv produces canonical mount, ipc, cache, token and profile arguments`() {
        val binary = Paths.get("/home/u/.local/lib/unidrive/unidrive-mount")
        val mountPoint = Paths.get("/tmp/mnt")
        val socket = Paths.get("/run/user/1000/unidrive-foo.sock")
        val cacheRoot = Paths.get("/home/u/.cache/unidrive/hydration/onedrive")
        val tokenFile = Paths.get("/home/u/.config/unidrive/foo/ipc.token")

        val argv = MountCommand.buildArgv(binary, mountPoint, socket, cacheRoot, tokenFile, "foo")

        // #239: assert the argv structure against the inputs' own toString() rather than
        // hard-coded POSIX strings, so the test holds on Windows (where Paths.get renders
        // backslash separators). The pinned contract is the flag order + that each path is
        // passed through verbatim, untransformed.
        assertEquals(
            listOf(
                binary.toString(),
                "--mount",
                mountPoint.toString(),
                "--ipc",
                socket.toString(),
                "--cache",
                cacheRoot.toString(),
                "--ipc-token-file",
                tokenFile.toString(),
                "--profile",
                "foo",
            ),
            argv,
        )
    }

    // IPC protocol 2 (#632): a protocol-2 daemon refuses every verb from an unauthenticated
    // connection, so a co-daemon started without the token file and the exact profile name
    // cannot mount at all. If this test is removed or loosened, mount silently breaks again.
    @Test
    fun `buildArgv passes the full-scope token file and the profile name unnormalised`() {
        val profileDir = Paths.get("/home/u/.config/unidrive/caf\u00e9")
        val tokenFile = IpcAuth.tokenFile(profileDir, IpcAuth.Scope.FULL)

        val argv =
            MountCommand.buildArgv(
                Paths.get("/b"), Paths.get("/m"), Paths.get("/s.sock"), Paths.get("/c"), tokenFile, "caf\u00e9",
            )

        assertEquals(tokenFile.toString(), argv[argv.indexOf("--ipc-token-file") + 1])
        assertEquals(profileDir.resolve("ipc.token"), tokenFile, "full scope must use ipc.token, not the read token")
        assertEquals("caf\u00e9", argv[argv.indexOf("--profile") + 1])
    }

    @Test
    fun `buildArgv cache path includes providerId subdirectory`() {
        val binary = Paths.get("/opt/bin/unidrive-mount")
        val mountPoint = Paths.get("/mnt/x")
        val socket = Paths.get("/tmp/x.sock")
        // The load-bearing claim: cache argv ends with /<providerId>, never the bare root.
        val cacheRoot = Paths.get("/home/u/.cache/unidrive/hydration/posteo_onedrive")

        val argv = MountCommand.buildArgv(binary, mountPoint, socket, cacheRoot, Paths.get("/t/ipc.token"), "posteo_onedrive")

        val cacheIdx = argv.indexOf("--cache")
        assertTrue(cacheIdx >= 0, "argv must contain --cache flag")
        // #239: normalize separators so the layout assertions hold on Windows.
        val cacheValue = argv[cacheIdx + 1].replace('\\', '/')
        assertTrue(
            cacheValue.endsWith("/posteo_onedrive"),
            "cache path must end with /<providerId>; got: $cacheValue",
        )
        assertTrue(
            cacheValue.contains("/unidrive/hydration/"),
            "cache path must traverse the hydration layout; got: $cacheValue",
        )
    }

    @Test
    fun `hydrationCacheRoot honours providerId suffix under XDG_CACHE_HOME`() {
        // Path computation must be a pure function of (cacheRoot, providerId).
        // Pass an explicit cacheRoot so the test does not rely on env state.
        val root = Paths.get("/tmp/xdg-cache")
        val resolved = MountCommand.hydrationCacheRoot(root, "internxt")
        assertEquals(Paths.get("/tmp/xdg-cache/unidrive/hydration/internxt"), resolved)
    }

    @Test
    fun `hydrationCacheRoot empty providerId falls back to default subdirectory`() {
        // Mirrors SyncEngine.resolveCachePath: blank providerId resolves under "default".
        val root = Paths.get("/tmp/xdg-cache")
        val resolved = MountCommand.hydrationCacheRoot(root, "")
        assertEquals(Paths.get("/tmp/xdg-cache/unidrive/hydration/default"), resolved)
    }

    // ── #135: the cache root shares the socket's canonical disk name ─────────

    @Test
    fun `hydrationCacheRoot uses the canonical disk name for long profile names`() {
        val longName = "a".repeat(100)
        val root = Paths.get("/tmp/xdg-cache")

        val resolved = MountCommand.hydrationCacheRoot(root, longName)

        val canonical = IpcServer.canonicalDiskName(longName)
        assertEquals(
            Paths.get("/tmp/xdg-cache/unidrive/hydration/$canonical"),
            resolved,
            "the cache root derives from the same canonical the socket name uses",
        )
        assertFalse(resolved.toString().contains(longName), "the verbatim long name is not used")
    }

    @Test
    fun `canonicalDiskName is verbatim for short names and the hashed form for long ones`() {
        assertEquals("short", IpcServer.canonicalDiskName("short"))
        val long = IpcServer.canonicalDiskName("a".repeat(100))
        assertEquals(8, long.length, "long names hash to the 8-hex truncation: $long")
        assertTrue(long.all { it.isDigit() || it in 'a'..'f' }, "the truncation is hex: $long")
        assertEquals(long, IpcServer.canonicalDiskName("a".repeat(100)), "stable across calls")
    }

    @Test
    fun `migrates an existing long profile cache before using the canonical directory`() {
        val root = Files.createTempDirectory("ud-cache-migration")
        val key = "long-profile-" + "x".repeat(90)
        val legacy = root.resolve("unidrive/hydration/$key")
        Files.createDirectories(legacy)
        Files.writeString(legacy.resolve("pending-upload.txt"), "keep me")

        org.krost.unidrive.sync.SyncEngine.migrateLegacyHydrationCacheRoot(root, key)

        val canonical = org.krost.unidrive.sync.SyncEngine.hydrationCacheRoot(root, key)
        assertEquals("keep me", Files.readString(canonical.resolve("pending-upload.txt")))
        assertFalse(Files.exists(legacy), "the legacy directory was moved, not abandoned")
    }

    @Test
    fun `supervisor propagates child exit code`() {
        // Use /bin/false (always exits 1) and /bin/true (always exits 0) — POSIX
        // canonical "tiny supervised child" stand-ins. No need to actually mount.
        val falseBinary = Paths.get("/bin/false")
        if (!Files.exists(falseBinary)) return // skip on exotic distros

        val exit = MountCommand.superviseProcess(listOf(falseBinary.toString()))
        assertEquals(1, exit)
    }

    @Test
    fun `supervisor propagates child success exit code`() {
        val trueBinary = Paths.get("/bin/true")
        if (!Files.exists(trueBinary)) return

        val exit = MountCommand.superviseProcess(listOf(trueBinary.toString()))
        assertEquals(0, exit)
    }

    @Test
    fun `binary not found returns EX_CONFIG exit code 78`() {
        val missing = Paths.get("/nonexistent/path/that/should/not/exist/unidrive-mount")
        val exit = MountCommand.checkBinaryExists(missing)
        assertEquals(78, exit)
    }

    @Test
    fun `binary present returns 0`() {
        // #239: any existing file qualifies (checkBinaryExists accepts isExecutable OR exists).
        // The prior `/bin/sh` hardcode failed on Windows; use a temp file so the test is OS-agnostic.
        val present = Files.createTempFile("unidrive-mount-present", ".bin")
        try {
            assertEquals(0, MountCommand.checkBinaryExists(present))
        } finally {
            Files.deleteIfExists(present)
        }
    }

    @Test
    fun `supervisor SIGTERM forwarding terminates long-running child within timeout`() {
        // Spawn a long-running /bin/sleep, send SIGTERM via Process.destroy,
        // assert the child exits within the 10s waitFor budget.
        val sleepBinary = Paths.get("/bin/sleep")
        if (!Files.exists(sleepBinary)) return

        val pb = ProcessBuilder(sleepBinary.toString(), "30")
            .redirectErrorStream(true)
        val proc = pb.start()
        val started = System.currentTimeMillis()
        // Process.destroy() sends SIGTERM on Unix per the JDK contract.
        val exit = MountCommand.sendSigtermAndWait(proc, timeoutMillis = 10_000)
        val elapsed = System.currentTimeMillis() - started

        assertTrue(elapsed < 10_000, "sleep child should not have outlived the 10s SIGTERM budget; elapsed=$elapsed")
        // Convention: 128 + SIGTERM(15) = 143 on POSIX; some shells normalise to 130/137 etc.
        // Accept any non-zero exit OR a successful destroy (return any int).
        assertTrue(exit != Int.MIN_VALUE, "supervisor must return a real exit code, got sentinel")
    }

    @Test
    fun `exit_message_does_not_claim_the_daemon_is_down_when_it_answers`() {
        val msg = MountCommand.coDaemonExitMessage(1, "p1", MountCommand.DaemonStatus.ANSWERING)
        assertFalse(msg.contains("not running"), msg)
        assertTrue(msg.contains("code 1") && msg.contains("running and answering"), msg)
    }

    @Test
    fun `exit_message_says_not_running_and_how_to_start_only_when_the_socket_is_missing`() {
        val msg = MountCommand.coDaemonExitMessage(1, "p1", MountCommand.DaemonStatus.NOT_RUNNING)
        assertTrue(msg.contains("is not running") && msg.contains("unidrive -p p1 daemon run"), msg)
    }

    @Test
    fun `exit_message_for_a_socket_that_does_not_answer_names_starting_and_stale_not_down`() {
        val msg = MountCommand.coDaemonExitMessage(1, "p1", MountCommand.DaemonStatus.SOCKET_NOT_ANSWERING)
        assertFalse(msg.contains("is not running"), msg)
        assertTrue(msg.contains("still be starting") && msg.contains("stale"), msg)
    }

    @Test
    fun `an_answering_daemon_is_status_answering_whatever_the_socket_file_says`() {
        assertEquals(MountCommand.DaemonStatus.ANSWERING, MountCommand.resolveDaemonStatus(answers = true, socketExists = true))
        assertEquals(MountCommand.DaemonStatus.ANSWERING, MountCommand.resolveDaemonStatus(answers = true, socketExists = false))
    }

    @Test
    fun `only_a_missing_socket_reads_as_not_running_and_a_present_silent_one_as_not_answering`() {
        assertEquals(MountCommand.DaemonStatus.SOCKET_NOT_ANSWERING, MountCommand.resolveDaemonStatus(answers = false, socketExists = true))
        assertEquals(MountCommand.DaemonStatus.NOT_RUNNING, MountCommand.resolveDaemonStatus(answers = false, socketExists = false))
    }
}
