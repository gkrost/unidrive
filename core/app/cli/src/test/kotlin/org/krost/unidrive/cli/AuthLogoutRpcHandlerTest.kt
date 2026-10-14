package org.krost.unidrive.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.krost.unidrive.AuthenticationException
import org.krost.unidrive.Capability
import org.krost.unidrive.CloudItem
import org.krost.unidrive.CloudProvider
import org.krost.unidrive.DeltaPage
import org.krost.unidrive.QuotaInfo
import org.krost.unidrive.auth.CredentialProtectionException
import org.krost.unidrive.sync.StateDatabase
import org.krost.unidrive.sync.model.SyncEntry
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The `auth.logout` verb: the running daemon signs the account out — revoking the token, deleting the
 * credential files, keeping the sync state — and refuses while uploads are pending, because without
 * credentials they cannot finish.
 */
class AuthLogoutRpcHandlerTest {
    private val dir = Files.createTempDirectory("auth-logout-handler-")
    private val db = StateDatabase(dir.resolve("state.db")).also { it.initialize() }
    private val markUnauthenticatedCalls = mutableListOf<Boolean>()

    @AfterTest
    fun tearDown() {
        db.close()
        dir.toFile().deleteRecursively()
    }

    /** The provider whose logout is scripted; the daemon builds one fresh per request, like the quota refresh does. */
    private inner class ScriptedProvider : CloudProvider {
        override val id: String = "stub"
        override val displayName: String = "Stub"
        override var isAuthenticated: Boolean = true
        var logoutCalls: Int = 0

        override fun capabilities(): Set<Capability> = emptySet()

        override suspend fun authenticate() {}

        override suspend fun logout() {
            logoutCalls += 1
            failure?.let { throw it }
            isAuthenticated = false
        }

        var failure: Exception? = null

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
        ): DeltaPage = DeltaPage(items = emptyList(), cursor = "x", hasMore = false)

        override suspend fun quota(): QuotaInfo = QuotaInfo(total = 0L, used = 0L, remaining = 0L)
    }

    private var provider = ScriptedProvider()

    private fun handler(): AuthLogoutRpcHandler =
        AuthLogoutRpcHandler(
            profileDir = dir,
            db = db,
            providerFactory = { provider },
            markUnauthenticated = { markUnauthenticatedCalls += true },
        )

    private fun reply(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    /** One request through the handler, as the IPC server would run it: on a coroutine. */
    private fun call(request: String): JsonObject = kotlinx.coroutines.runBlocking { reply(handler().handle(request)) }

    private fun seedPending(vararg rows: Pair<String, Boolean>) {
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
    }

    @Test
    fun `signs out - revokes, removes the credential files, keeps the state and unmarks the provider`() {
        Files.writeString(dir.resolve("credentials.json"), """{"secret":"x"}""")
        seedPending("/docs/failed.txt" to true)

        val r = call("""{"verb":"auth.logout"}""")

        assertTrue(r.getValue("ok").jsonPrimitive.booleanOrNull == true)
        assertTrue(r.getValue("revoked").jsonPrimitive.booleanOrNull == true)
        assertTrue(r.getValue("credentials_removed").jsonPrimitive.booleanOrNull == true)
        assertFalse(Files.exists(dir.resolve("credentials.json")))
        assertTrue(Files.exists(dir.resolve("state.db")), "the sync state is never touched")
        assertEquals(listOf(true), markUnauthenticatedCalls)
        assertEquals(1, provider.logoutCalls)
    }

    @Test
    fun `uploads on their way refuse and change nothing`() {
        Files.writeString(dir.resolve("token.json"), "tok")
        seedPending("/docs/a.txt" to false, "/docs/b.txt" to true, "/docs/c.txt" to false)

        val r = call("""{"verb":"auth.logout"}""")

        assertEquals("uploads_pending", r.getValue("error").jsonPrimitive.content)
        assertEquals(3, r.getValue("pending").jsonPrimitive.int)
        assertEquals(1, r.getValue("failed").jsonPrimitive.int)
        assertTrue(Files.exists(dir.resolve("token.json")), "nothing is deleted")
        assertEquals(0, provider.logoutCalls, "the token is not revoked")
        assertTrue(markUnauthenticatedCalls.isEmpty())
    }

    @Test
    fun `force signs out past pending uploads`() {
        seedPending("/docs/a.txt" to false)

        val r = call("""{"verb":"auth.logout","force":true}""")

        assertTrue(r.getValue("ok").jsonPrimitive.booleanOrNull == true)
        assertEquals(1, provider.logoutCalls)
    }

    @Test
    fun `a revocation failure still cleans up locally and says so`() {
        provider.failure = RuntimeException("gateway down")

        val r = call("""{"verb":"auth.logout"}""")

        assertTrue(r.getValue("ok").jsonPrimitive.booleanOrNull == true)
        assertFalse(r.getValue("revoked").jsonPrimitive.booleanOrNull == true)
    }

    @Test
    fun `an already unauthenticated profile signs out with revoked null`() {
        provider.failure = AuthenticationException("no token")

        val r = call("""{"verb":"auth.logout"}""")

        assertTrue(r.getValue("ok").jsonPrimitive.booleanOrNull == true)
        assertTrue(r.getValue("revoked") is JsonNull)
        assertFalse(r.getValue("credentials_removed").jsonPrimitive.booleanOrNull == true)
    }

    @Test
    fun `a locked secret store refuses before anything is deleted`() {
        Files.writeString(dir.resolve("credentials.json"), "marker")
        provider.failure = CredentialProtectionException("keyring locked")

        val r = call("""{"verb":"auth.logout"}""")

        assertEquals("credential_protection_unavailable", r.getValue("error").jsonPrimitive.content)
        assertTrue(Files.exists(dir.resolve("credentials.json")), "the marker survives")
    }

    @Test
    fun `a force that is not a boolean is refused`() {
        assertEquals("invalid_force", call("""{"verb":"auth.logout","force":"yes"}""").getValue("error").jsonPrimitive.content)
        assertEquals("invalid_force", call("""{"verb":"auth.logout","force":1}""").getValue("error").jsonPrimitive.content)
        assertEquals(0, provider.logoutCalls)
    }
}
