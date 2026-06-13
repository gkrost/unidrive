package org.krost.unidrive.internxt

import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

// getMetadata name matching: an exact full-name (base + type) match anywhere in
// the parent listing must always win over an extension-stripped baseName match.
// A folder containing both `Makefile` (plainName=Makefile, type=null) and
// `Makefile.am` (plainName=Makefile, type=am) used to resolve `/x/Makefile` to
// whichever sibling the listing happened to return first — and the path-based
// consumers (SyncEngine.deleteRemote, download, move) then trashed, overwrote,
// or renamed the wrong file. The baseName fallback may only fire when NO exact
// match exists in the whole listing.
class InternxtGetMetadataExactMatchTest {
    private val makefileUuid = "makefile-uuid"
    private val makefileAmUuid = "makefile-am-uuid"
    private val folderUuid = "x-folder-uuid"

    private val makefileJson =
        """{"uuid":"$makefileUuid","plainName":"Makefile","type":null,"size":"100",
            "folderUuid":"$folderUuid","status":"EXISTS"}"""
    private val makefileAmJson =
        """{"uuid":"$makefileAmUuid","plainName":"Makefile","type":"am","size":"200",
            "folderUuid":"$folderUuid","status":"EXISTS"}"""

    private fun newProviderRooted(tokenPath: java.nio.file.Path): InternxtProvider {
        val provider = InternxtProvider(InternxtConfig(tokenPath = tokenPath))
        val authField = InternxtProvider::class.java.getDeclaredField("authService")
        authField.isAccessible = true
        val authService = authField.get(provider)
        val credsField = authService.javaClass.getDeclaredField("credentials")
        credsField.isAccessible = true
        val payload = """{"exp":9999999999}"""
        val payloadB64 =
            java.util.Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString(payload.toByteArray())
        credsField.set(
            authService,
            org.krost.unidrive.internxt.model.InternxtCredentials(
                jwt = "header.$payloadB64.signature",
                mnemonic =
                    "abandon abandon abandon abandon abandon abandon " +
                        "abandon abandon abandon abandon abandon about",
                rootFolderId = "root-folder-uuid",
                email = "test@example.invalid",
                bridgeUser = "bridge-user",
                bridgeUserId = "bridge-secret",
                bucket = "6928426c1a2316b856c9ab81",
            ),
        )
        return provider
    }

    private fun installMockClientOnProvider(
        provider: InternxtProvider,
        engine: io.ktor.client.engine.mock.MockEngine,
    ) {
        val apiField = InternxtProvider::class.java.getDeclaredField("api")
        apiField.isAccessible = true
        val api = apiField.get(provider) as InternxtApiService
        val field = InternxtApiService::class.java.getDeclaredField("httpClient")
        field.isAccessible = true
        (field.get(api) as? io.ktor.client.HttpClient)?.close()
        field.set(api, io.ktor.client.HttpClient(engine))
    }

    // Root listing resolves /x; the /x listing returns the two siblings in
    // [filesJson] order so each test controls which one the API yields first.
    private fun engineWithSiblings(vararg filesJson: String) =
        io.ktor.client.engine.mock.MockEngine { request ->
            val url = request.url.toString()
            when {
                url.contains("/drive/folders/content/root-folder-uuid") ->
                    respond(
                        content =
                            """{"children":[{"uuid":"$folderUuid","plainName":"x",""" +
                                """"parentUuid":"root-folder-uuid","status":"EXISTS"}],"files":[]}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", "application/json"),
                    )
                url.contains("/drive/folders/content/$folderUuid") ->
                    respond(
                        content = """{"children":[],"files":[${filesJson.joinToString(",")}]}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf("Content-Type", "application/json"),
                    )
                else -> error("unexpected URL in exact-match test: $url (method=${request.method})")
            }
        }

    private suspend fun getMetadataAgainst(
        path: String,
        vararg filesJson: String,
    ): org.krost.unidrive.CloudItem {
        val tmp = java.nio.file.Files.createTempDirectory("ud-exact-")
        try {
            val provider = newProviderRooted(tmp)
            try {
                installMockClientOnProvider(provider, engineWithSiblings(*filesJson))
                return provider.getMetadata(path)
            } finally {
                provider.close()
            }
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }

    @Test
    fun `Makefile resolves the extensionless sibling when the am sibling lists first`() =
        runTest {
            // Pre-fix failure mode: `Makefile.am` (plainName=Makefile, type=am)
            // appears first in the listing, its baseName equals the requested
            // name, and the single-pass predicate returned it.
            val item = getMetadataAgainst("/x/Makefile", makefileAmJson, makefileJson)
            assertEquals(makefileUuid, item.id, "exact full-name match must beat an earlier baseName match")
            assertEquals("Makefile", item.name)
        }

    @Test
    fun `Makefile resolves the extensionless sibling when it lists first`() =
        runTest {
            val item = getMetadataAgainst("/x/Makefile", makefileJson, makefileAmJson)
            assertEquals(makefileUuid, item.id)
            assertEquals("Makefile", item.name)
        }

    @Test
    fun `Makefile_am resolves the am sibling regardless of listing order`() =
        runTest {
            val first = getMetadataAgainst("/x/Makefile.am", makefileJson, makefileAmJson)
            assertEquals(makefileAmUuid, first.id)
            assertEquals("Makefile.am", first.name)

            val second = getMetadataAgainst("/x/Makefile.am", makefileAmJson, makefileJson)
            assertEquals(makefileAmUuid, second.id)
        }

    @Test
    fun `baseName fallback still resolves an extension-less request when no exact match exists`() =
        runTest {
            // Back-compat: a lone `report.txt` (plainName=report, type=txt) must
            // still answer a request for `/x/report` — no exact full-name match
            // exists, so the fallback fires (and warns) instead of throwing.
            val reportJson =
                """{"uuid":"report-uuid","plainName":"report","type":"txt","size":"42",
                    "folderUuid":"$folderUuid","status":"EXISTS"}"""
            val item = getMetadataAgainst("/x/report", reportJson)
            assertEquals("report-uuid", item.id)
            assertEquals("report.txt", item.name)
        }
}
