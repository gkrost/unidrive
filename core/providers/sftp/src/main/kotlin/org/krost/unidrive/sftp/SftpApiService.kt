package org.krost.unidrive.sftp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.sftp.client.SftpClient
import org.apache.sshd.sftp.client.SftpClientFactory
import org.apache.sshd.sftp.common.SftpConstants
import org.krost.unidrive.AuthenticationException
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/**
 * Thin SFTP client wrapping Apache MINA SSHD.
 *
 * A single [ClientSession] is created on [connect] and reused for all
 * operations.  Concurrent SFTP subsystem channels are limited by
 * [SftpConfig.maxConcurrency] (default 4) to avoid overwhelming servers
 * like Synology NAS that reject parallel channel opens beyond ~10.
 *
 * [close] terminates all pooled channels, the session, and the SSH client.
 *
 * All blocking SSHD calls are dispatched to [Dispatchers.IO].
 */
class SftpApiService(
    private val config: SftpConfig,
) : AutoCloseable {
    private val log = LoggerFactory.getLogger(SftpApiService::class.java)

    private var sshClient: SshClient? = null
    private var session: ClientSession? = null

    /** Pool of idle SFTP subsystem clients, bounded by [concurrencySemaphore]. */
    private val clientPool = ConcurrentLinkedQueue<SftpClient>()
    private val concurrencySemaphore = Semaphore(config.maxConcurrency)

    // ── Connection lifecycle ──────────────────────────────────────────────────

    /**
     * Establish the SSH session.  Must be called once before any operation.
     * Throws [AuthenticationException] on auth failure.
     */
    suspend fun connect() =
        withContext(Dispatchers.IO) {
            val client = SshClient.setUpDefaultClient()
            client.start()

            // Known-hosts verification
            if (config.knownHostsFile == null) {
                // Explicit opt-out: null means "accept any host key" (documented in SftpConfig).
                // Intended for first-time connection tests on mobile / one-shot tools where no
                // known_hosts exists. Production sync should always set a known_hosts path.
                client.serverKeyVerifier = org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier.INSTANCE
                log.warn(
                    "SFTP knownHostsFile is null; accepting any server host key. " +
                        "This is insecure — set knownHostsFile for production use.",
                )
            } else if (Files.exists(config.knownHostsFile)) {
                try {
                    // Probe-parse the file; MINA 2.x cannot parse OpenSSH hashed entries (|1|…)
                    // and throws on the first unrecognised line instead of skipping it.
                    val lines = Files.readAllLines(config.knownHostsFile)
                    val hasHashed = lines.any { it.trimStart().startsWith("|") }
                    if (hasHashed) {
                        // Write a filtered copy containing only plain (non-hashed) entries
                        val filtered =
                            lines.filter { line ->
                                val t = line.trimStart()
                                t.isNotEmpty() && !t.startsWith("#") && !t.startsWith("|")
                            }
                        if (filtered.isNotEmpty()) {
                            val tmp = Files.createTempFile("unidrive-known_hosts-", ".tmp")
                            tmp.toFile().deleteOnExit()
                            Files.write(tmp, filtered)
                            client.serverKeyVerifier =
                                org.apache.sshd.client.keyverifier.KnownHostsServerKeyVerifier(
                                    org.apache.sshd.client.keyverifier.RejectAllServerKeyVerifier.INSTANCE,
                                    tmp,
                                )
                            log.warn(
                                "known_hosts contains hashed entries (OpenSSH format) that MINA cannot parse; " +
                                    "using ${filtered.size} plain entries from ${config.knownHostsFile}",
                            )
                        } else {
                            // All entries are hashed — nothing MINA can verify; reject with clear error
                            client.serverKeyVerifier = org.apache.sshd.client.keyverifier.RejectAllServerKeyVerifier.INSTANCE
                            log.error(
                                "known_hosts at ${config.knownHostsFile} contains only hashed entries; " +
                                    "host key verification cannot proceed. Run: ssh-keyscan -H ${config.host} >> ${config.knownHostsFile}",
                            )
                        }
                    } else {
                        client.serverKeyVerifier =
                            org.apache.sshd.client.keyverifier.KnownHostsServerKeyVerifier(
                                org.apache.sshd.client.keyverifier.RejectAllServerKeyVerifier.INSTANCE,
                                config.knownHostsFile,
                            )
                    }
                } catch (e: Exception) {
                    log.error("Failed to load known_hosts from ${config.knownHostsFile}: ${e.message}; rejecting host key verification")
                    client.serverKeyVerifier = org.apache.sshd.client.keyverifier.RejectAllServerKeyVerifier.INSTANCE
                }
            } else {
                // Reject all — known_hosts doesn't exist; user must add host key manually
                client.serverKeyVerifier = org.apache.sshd.client.keyverifier.RejectAllServerKeyVerifier.INSTANCE
                if (config.knownHostsFile != null) {
                    log.error(
                        "SFTP known_hosts file not found at ${config.knownHostsFile}; " +
                            "run: ssh-keyscan -H ${config.host} >> ${config.knownHostsFile}",
                    )
                }
            }

            val sess =
                client
                    .connect(config.username, config.host, config.port)
                    .verify(30, TimeUnit.SECONDS)
                    .session

            // Authenticate
            try {
                if (config.identityFile != null && Files.exists(config.identityFile)) {
                    sess.addPublicKeyIdentity(loadKeyPair(config.identityFile))
                }
                if (config.password != null) {
                    sess.addPasswordIdentity(config.password)
                }
                sess.auth().verify(30, TimeUnit.SECONDS)
            } catch (e: Exception) {
                sess.close()
                client.stop()
                throw AuthenticationException("SFTP authentication failed for ${config.username}@${config.host}: ${e.message}")
            }

            sshClient = client
            session = sess
        }

    override fun close() {
        while (true) {
            val client = clientPool.poll() ?: break
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
        try {
            session?.close()
        } catch (_: Exception) {
        }
        try {
            sshClient?.stop()
        } catch (_: Exception) {
        }
    }

    // ── File operations ───────────────────────────────────────────────────────

    /** Download remote file at [remotePath] to [destination]. Returns bytes written. */
    suspend fun download(
        remotePath: String,
        destination: Path,
    ): Long =
        withContext(Dispatchers.IO) {
            withSftp { sftp ->
                Files.createDirectories(destination.parent)
                sftp.read(serverPath(remotePath)).use { input ->
                    val bytes =
                        destination.toFile().outputStream().use { out ->
                            input.copyTo(out)
                        }
                    bytes
                }
            }
        }

    /** Upload [localPath] to [remotePath] on the server. Returns a [SftpEntry] for the new object. */
    suspend fun upload(
        localPath: Path,
        remotePath: String,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): SftpEntry =
        withContext(Dispatchers.IO) {
            val fileSize = Files.size(localPath)
            withSftp { sftp ->
                val remote = serverPath(remotePath)
                ensureParentDirs(sftp, remote)
                val handle =
                    sftp.open(
                        remote,
                        java.util.EnumSet.of(
                            SftpClient.OpenMode.Write,
                            SftpClient.OpenMode.Create,
                            SftpClient.OpenMode.Truncate,
                        ),
                    )
                try {
                    var offset = 0L
                    Files.newInputStream(localPath).use { inp ->
                        val buf = ByteArray(32768)
                        var n: Int
                        while (inp.read(buf).also { n = it } >= 0) {
                            sftp.write(handle, offset, buf, 0, n)
                            offset += n
                        }
                    }
                } finally {
                    sftp.close(handle)
                }
                onProgress?.invoke(fileSize, fileSize)
                val attrs = sftp.stat(remote)
                SftpEntry(
                    path = remotePath,
                    size = attrs.size,
                    mtimeSeconds = attrs.modifyTime.toInstant().epochSecond,
                    isFolder = false,
                )
            }
        }

    /** Delete the remote file or empty directory at [remotePath]. 404-equivalent is silently ignored. */
    suspend fun delete(remotePath: String) =
        withContext(Dispatchers.IO) {
            withSftp { sftp ->
                try {
                    val attrs = sftp.stat(serverPath(remotePath))
                    if (attrs.isDirectory) {
                        sftp.rmdir(serverPath(remotePath))
                    } else {
                        sftp.remove(serverPath(remotePath))
                    }
                } catch (e: org.apache.sshd.sftp.common.SftpException) {
                    if (e.status == SftpConstants.SSH_FX_NO_SUCH_FILE ||
                        e.status == SftpConstants.SSH_FX_NO_SUCH_PATH
                    ) {
                        return@withSftp
                    }
                    throw SftpException("SFTP delete failed for $remotePath: ${e.message}", e.status)
                }
            }
        }

    /** Create a directory (and all parents) at [remotePath]. */
    suspend fun mkdir(remotePath: String) =
        withContext(Dispatchers.IO) {
            withSftp { sftp -> ensureParentDirs(sftp, serverPath(remotePath)) }
        }

    /**
     * Rename/move [fromPath] to [toPath] using the SFTP rename command.
     * Most servers (OpenSSH, ProFTPD, etc.) implement this as an atomic operation.
     */
    suspend fun rename(
        fromPath: String,
        toPath: String,
    ) = withContext(Dispatchers.IO) {
        withSftp { sftp ->
            val from = serverPath(fromPath)
            val to = serverPath(toPath)
            ensureParentDirs(sftp, to)
            sftp.rename(from, to)
        }
    }

    /** Get attributes for a single remote path. Returns null if not found. */
    suspend fun stat(remotePath: String): SftpEntry? =
        withContext(Dispatchers.IO) {
            withSftp { sftp ->
                try {
                    val attrs = sftp.stat(serverPath(remotePath))
                    SftpEntry(
                        path = remotePath,
                        size = attrs.size,
                        mtimeSeconds = attrs.modifyTime.toInstant().epochSecond,
                        isFolder = attrs.isDirectory,
                    )
                } catch (e: org.apache.sshd.sftp.common.SftpException) {
                    if (e.status == SftpConstants.SSH_FX_NO_SUCH_FILE ||
                        e.status == SftpConstants.SSH_FX_NO_SUCH_PATH
                    ) {
                        null
                    } else {
                        throw SftpException("SFTP stat failed for $remotePath: ${e.message}", e.status)
                    }
                }
            }
        }

    /**
     * Recursively list all files and directories under [remotePath].
     * Breadth-first traversal; returns every entry (files and folders).
     */
    suspend fun listAll(remotePath: String = ""): List<SftpEntry> =
        withContext(Dispatchers.IO) {
            withSftp { sftp ->
                val root = serverPath(remotePath)
                val results = mutableListOf<SftpEntry>()
                val queue = ArrayDeque<String>()
                queue.add(root)

                while (queue.isNotEmpty()) {
                    val dir = queue.removeFirst()
                    val entries: Iterable<SftpClient.DirEntry> =
                        try {
                            sftp.readDir(dir)
                        } catch (e: org.apache.sshd.sftp.common.SftpException) {
                            log.warn("SFTP readdir failed for $dir: ${e.message}")
                            continue
                        }
                    for (entry in entries) {
                        val name = entry.filename
                        if (name == "." || name == "..") continue
                        val fullPath = if (dir.endsWith("/")) "$dir$name" else "$dir/$name"
                        val virtualPath = serverToVirtual(fullPath)
                        val attrs = entry.attributes
                        val isDir = attrs.isDirectory
                        results.add(
                            SftpEntry(
                                path = virtualPath,
                                size = attrs.size,
                                mtimeSeconds = attrs.modifyTime.toInstant().epochSecond,
                                isFolder = isDir,
                            ),
                        )
                        if (isDir) queue.add(fullPath)
                    }
                }
                results
            }
        }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Convert a virtual path (/foo/bar.txt) to a server-absolute path. */
    fun serverPath(virtualPath: String): String {
        val base = config.remotePath.trimEnd('/')
        val rel = virtualPath.trimStart('/')
        return when {
            rel.isEmpty() -> base.ifEmpty { "." }
            base.isEmpty() -> rel
            else -> "$base/$rel"
        }
    }

    /** Convert a server-absolute path back to a virtual path (/foo/bar.txt). */
    fun serverToVirtual(serverPath: String): String {
        val base = config.remotePath.trimEnd('/')
        val stripped =
            if (base.isNotEmpty() && serverPath.startsWith(base)) {
                serverPath.removePrefix(base)
            } else {
                serverPath
            }
        return "/${stripped.trimStart('/')}"
    }

    private fun ensureParentDirs(
        sftp: SftpClient,
        serverPath: String,
    ) {
        val parts = serverPath.trimStart('/').split("/").dropLast(1)
        var current = if (serverPath.startsWith("/")) "" else "."
        for (part in parts) {
            current = if (current.isEmpty() || current == ".") part else "$current/$part"
            val abs = if (serverPath.startsWith("/")) "/$current" else current
            try {
                sftp.mkdir(abs)
            } catch (e: org.apache.sshd.sftp.common.SftpException) {
                // SSH_FX_FAILURE (4) is what OpenSSH returns for "mkdir on existing dir".
                // SSH_FX_FILE_ALREADY_EXISTS (11) is the explicit code in SFTPv6+.
                // Any other status (permission denied, disk full, etc.) must propagate.
                if (e.status != SftpConstants.SSH_FX_FAILURE &&
                    e.status != SftpConstants.SSH_FX_FILE_ALREADY_EXISTS
                ) {
                    throw SftpException("SFTP mkdir failed for $abs: ${e.message}", e.status)
                }
            }
        }
    }

    private fun loadKeyPair(identityFile: Path): java.security.KeyPair {
        // Use SecurityUtils so OpenSSH-format keys (-----BEGIN OPENSSH PRIVATE KEY-----)
        // are handled by OpenSSHKeyPairResourceParser, not just legacy PEM/PKCS8.
        val resource =
            org.apache.sshd.common.util.io.resource
                .PathResource(identityFile)
        val pairs =
            Files.newInputStream(identityFile).use { stream ->
                org.apache.sshd.common.util.security.SecurityUtils
                    .loadKeyPairIdentities(null, resource, stream, null)
            }
        return pairs?.firstOrNull()
            ?: throw SftpException("No key pairs found in identity file: $identityFile")
    }

    /**
     * Borrow an SFTP subsystem client from the pool, execute [block], and return it.
     *
     * Concurrency is bounded by [concurrencySemaphore] (permits = [SftpConfig.maxConcurrency]).
     * Idle clients are reused from [clientPool]; a new subsystem channel is opened only when
     * the pool is empty.  Broken clients are discarded and replaced transparently.
     */
    private suspend fun <T> withSftp(block: (SftpClient) -> T): T =
        concurrencySemaphore.withPermit {
            val sess = session ?: throw SftpException("Not connected — call connect() first")
            val sftp = borrowClient(sess)
            try {
                val result = withContext(Dispatchers.IO) { block(sftp) }
                clientPool.offer(sftp)
                result
            } catch (e: Exception) {
                // Discard the client on error — the channel may be broken
                try {
                    sftp.close()
                } catch (_: Exception) {
                }
                throw e
            }
        }

    /** Take an idle client from the pool or create a new one. */
    private fun borrowClient(sess: ClientSession): SftpClient {
        while (true) {
            val pooled = clientPool.poll() ?: break
            if (pooled.isOpen) return pooled
            try {
                pooled.close()
            } catch (_: Exception) {
            }
        }
        return SftpClientFactory.instance().createSftpClient(sess)
    }
}

data class SftpEntry(
    val path: String,
    val size: Long,
    val mtimeSeconds: Long,
    val isFolder: Boolean,
) {
    val modified: Instant get() = Instant.ofEpochSecond(mtimeSeconds)
}
