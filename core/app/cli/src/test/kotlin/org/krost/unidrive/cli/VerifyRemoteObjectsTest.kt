package org.krost.unidrive.cli

import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.RemoteObjectAuditRecord
import org.krost.unidrive.RemoteObjectAuditReport
import org.krost.unidrive.RemoteObjectAuditor
import org.krost.unidrive.RemoteObjectStatus
import org.krost.unidrive.io.OwnerOnly
import org.krost.unidrive.io.grantProblem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import picocli.CommandLine
import java.io.ByteArrayOutputStream
import java.io.PrintStream
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
 * `verify --remote-objects` through the real picocli command against a fake provider and auditor: exit
 * codes, the saved report, and the read-only promise (#769).
 */
class VerifyRemoteObjectsTest {
    private lateinit var configDir: Path
    private lateinit var reportDir: Path
    private val originalOut = System.out
    private val originalErr = System.err
    private lateinit var captured: ByteArrayOutputStream
    private lateinit var capturedErr: ByteArrayOutputStream

    @BeforeTest
    fun setUp() {
        configDir = Files.createTempDirectory("ud-verify-ro-cfg")
        reportDir = Files.createTempDirectory("ud-verify-ro-report")
        val syncRoot = Files.createTempDirectory("ud-verify-ro-local")
        Files.writeString(
            configDir.resolve("config.toml"),
            """
            |[providers.fake]
            |type = "localfs"
            |sync_root = "${syncRoot.toString().replace('\\', '/')}"
            |root_path = "${syncRoot.toString().replace('\\', '/')}"
            """.trimMargin(),
        )
        captured = ByteArrayOutputStream()
        capturedErr = ByteArrayOutputStream()
        System.setOut(PrintStream(captured, true, Charsets.UTF_8))
        System.setErr(PrintStream(capturedErr, true, Charsets.UTF_8))
    }

    @AfterTest
    fun tearDown() {
        System.setOut(originalOut)
        System.setErr(originalErr)
    }

    private class FakeProvider(
        private val files: List<CloudItem>,
        override val remoteObjectAuditor: RemoteObjectAuditor?,
    ) : CloudProvider {
        override val id = "fake"
        override val displayName = "Fake"
        override var isAuthenticated = true

        override fun capabilities(): Set<Capability> = emptySet()

        override suspend fun authenticate() {}

        override suspend fun listChildren(path: String): List<CloudItem> = if (path == "/") files else emptyList()

        override suspend fun getMetadata(path: String): CloudItem = error("not used")

        override suspend fun download(
            remotePath: String,
            destination: Path,
        ): Long = error("an audit must never download")

        override suspend fun upload(
            localPath: Path,
            remotePath: String,
            existingRemoteId: String?,
            ifMatchETag: String?,
            onProgress: ((Long, Long) -> Unit)?,
        ): CloudItem = error("an audit must never upload")

        override suspend fun delete(
            remotePath: String,
            ifMatchETag: String?,
        ): Unit = error("an audit must never delete")

        override suspend fun createFolder(path: String): CloudItem = error("not used")

        override suspend fun move(
            fromPath: String,
            toPath: String,
        ): CloudItem = error("an audit must never move")

        override suspend fun delta(
            cursor: String?,
            onPageProgress: ((Int) -> Unit)?,
            scanContext: org.krost.unidrive.ScanContext?,
        ): org.krost.unidrive.DeltaPage = error("an audit lists, it does not use the delta feed")

        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0L, used = 0L, remaining = 0L)
    }

    private fun file(name: String) =
        CloudItem(
            id = "id-$name",
            name = name,
            path = "/$name",
            size = 100,
            isFolder = false,
            modified = Instant.EPOCH,
            created = Instant.EPOCH,
            hash = null,
            mimeType = null,
        )

    private class FakeAuditor(
        private val statuses: Map<String, RemoteObjectStatus>,
    ) : RemoteObjectAuditor {
        var calls = 0

        override suspend fun auditRemoteObjects(files: List<CloudItem>): RemoteObjectAuditReport {
            calls++
            return RemoteObjectAuditReport(
                checkedCount = files.size,
                objects =
                    files.map {
                        RemoteObjectAuditRecord(
                            path = it.path,
                            status = statuses[it.path] ?: RemoteObjectStatus.HEALTHY,
                            driveSize = it.size,
                        )
                    },
            )
        }
    }

    private fun run(
        provider: CloudProvider,
        vararg extra: String,
    ): Int {
        val main =
            object : Main() {
                override fun createProvider(): CloudProvider = provider
            }
        return CommandLine(main).execute("-c", configDir.toString(), "-p", "fake", "verify", *extra)
    }

    private fun statuses(report: Path): Map<String, String> =
        Json
            .parseToJsonElement(Files.readString(report))
            .jsonObject
            .getValue("objects")
            .jsonArray
            .associate {
                val o = it.jsonObject
                o.getValue("path").jsonPrimitive.content to o.getValue("status").jsonPrimitive.content
            }

    @Test
    fun `findings exit 1, the report keeps every object and no sync state is created`() {
        val auditor = FakeAuditor(mapOf("/b.bin" to RemoteObjectStatus.TRUNCATED))
        val report = reportDir.resolve("audit.json")

        val exit = run(FakeProvider(listOf(file("a.bin"), file("b.bin")), auditor), "--remote-objects", "--json-report", report.toString())

        assertEquals(VerifyCommand.EXIT_DIVERGED, exit)
        assertEquals(mapOf("/a.bin" to "healthy", "/b.bin" to "truncated"), statuses(report))
        assertTrue(captured.toString(Charsets.UTF_8).contains("/b.bin"), "the finding is listed with its path")
        assertFalse(Files.exists(configDir.resolve("fake").resolve("state.db")), "the audit must not create or open the sync state")
    }

    @Test
    fun `an unverified object exits 2 and the report is still saved`() {
        val auditor = FakeAuditor(mapOf("/a.bin" to RemoteObjectStatus.UNVERIFIED))
        val report = reportDir.resolve("audit.json")

        val exit = run(FakeProvider(listOf(file("a.bin")), auditor), "--remote-objects", "--json-report", report.toString())

        assertEquals(VerifyCommand.EXIT_UNAUDITABLE, exit)
        assertEquals(mapOf("/a.bin" to "unverified"), statuses(report))
    }

    @Test
    fun `all healthy exits 0`() {
        val report = reportDir.resolve("audit.json")
        val exit = run(FakeProvider(listOf(file("a.bin")), FakeAuditor(emptyMap())), "--remote-objects", "--json-report", report.toString())
        assertEquals(VerifyCommand.EXIT_CONVERGED, exit)
    }

    @Test
    fun `an unwritable report path fails before any remote request is made`() {
        val auditor = FakeAuditor(emptyMap())
        val blocker = Files.writeString(reportDir.resolve("not-a-directory"), "x")

        val exit = run(FakeProvider(listOf(file("a.bin")), auditor), "--remote-objects", "--json-report", blocker.resolve("audit.json").toString())

        assertEquals(VerifyCommand.EXIT_UNAUDITABLE, exit)
        assertEquals(0, auditor.calls, "a bad report path must be found before a drive-long audit, not after it")
    }

    @Test
    fun `a report path that is a directory fails before any remote request is made`() {
        val auditor = FakeAuditor(emptyMap())
        val exit = run(FakeProvider(listOf(file("a.bin")), auditor), "--remote-objects", "--json-report", reportDir.toString())
        assertEquals(VerifyCommand.EXIT_UNAUDITABLE, exit)
        assertEquals(0, auditor.calls)
    }

    @Test
    fun `the saved report is readable by the current user only`() {
        // The report lists remote paths and sizes. A folder that hands out an inheritable grant (a shared
        // folder, a synced one) must not widen who can read it.
        if (WindowsAclProbe.isWindows) WindowsAclProbe.grantInheritableRead(reportDir, WindowsAclProbe.USERS_SID)
        val report = reportDir.resolve("audit.json")

        val exit = run(FakeProvider(listOf(file("a.bin")), FakeAuditor(emptyMap())), "--remote-objects", "--json-report", report.toString())

        assertEquals(VerifyCommand.EXIT_CONVERGED, exit)
        assertNull(OwnerOnly.grantProblem(report), "the report must be owner-only")
    }

    @Test
    fun `no temporary file is left next to the report`() {
        val report = reportDir.resolve("audit.json")
        run(FakeProvider(listOf(file("a.bin")), FakeAuditor(emptyMap())), "--remote-objects", "--json-report", report.toString())
        assertEquals(listOf("audit.json"), Files.list(reportDir).use { s -> s.map { it.fileName.toString() }.toList() })
    }
}
