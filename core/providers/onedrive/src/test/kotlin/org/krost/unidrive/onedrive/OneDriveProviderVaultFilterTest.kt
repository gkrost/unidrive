package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * UD-315: verifies that `OneDriveProvider.listChildren` and `.delta` filter out OneDrive's
 * Personal Vault stub.
 *
 * We build a real `OneDriveProvider`, then swap its private `graphApi`'s `httpClient` for one
 * backed by a Ktor `MockEngine` that returns canned JSON (same pattern as
 * `ListChildrenPaginationTest`). The filter pipeline runs end-to-end; only the transport is
 * faked.
 */
class OneDriveProviderVaultFilterTest {
    private fun mockedProvider(responseBody: String): OneDriveProvider {
        val engine =
            MockEngine { _ ->
                respond(
                    content = responseBody,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        val provider = OneDriveProvider()

        // Build a fresh GraphApiService with a non-refreshing token provider, then swap its
        // internal httpClient for the MockEngine. Install that service into the provider via
        // reflection (same pattern as ListChildrenPaginationTest).
        val freshGraphApi =
            GraphApiService(
                config = OneDriveConfig(),
                tokenProvider = { _ -> "test-token" },
            )
        val httpClientField = GraphApiService::class.java.getDeclaredField("httpClient")
        httpClientField.isAccessible = true
        (httpClientField.get(freshGraphApi) as? HttpClient)?.close()
        httpClientField.set(freshGraphApi, HttpClient(engine))

        // Replace the provider's private `graphApi` with our mocked instance.
        val graphApiField = OneDriveProvider::class.java.getDeclaredField("graphApi")
        graphApiField.isAccessible = true
        (graphApiField.get(provider) as? GraphApiService)?.close()
        graphApiField.set(provider, freshGraphApi)
        return provider
    }

    @Test
    fun `listChildren filters out German-locale Personal Vault stub`() =
        runTest {
            // Mixed payload: one real folder, the Vault (no facets, size=0, German name), one real file.
            val body =
                """
                {
                  "value": [
                    {
                      "id": "real-folder",
                      "name": "Dokumente",
                      "size": 0,
                      "folder": {"childCount": 3},
                      "parentReference": {"path": "/drive/root:"}
                    },
                    {
                      "id": "vault-stub",
                      "name": "Persönlicher Tresor",
                      "size": 0,
                      "parentReference": {"path": "/drive/root:"}
                    },
                    {
                      "id": "real-file",
                      "name": "readme.txt",
                      "size": 128,
                      "file": {"mimeType": "text/plain"},
                      "parentReference": {"path": "/drive/root:"}
                    }
                  ]
                }
                """.trimIndent()

            val provider = mockedProvider(body)
            val children = provider.listChildren("/")

            assertEquals(2, children.size, "Vault must be filtered; 2 real items remain")
            assertTrue(children.any { it.name == "Dokumente" }, "Real folder must pass through")
            assertTrue(children.any { it.name == "readme.txt" }, "Real file must pass through")
            assertFalse(
                children.any { it.name == "Persönlicher Tresor" },
                "Persönlicher Tresor must not appear in listChildren output",
            )
            provider.close()
        }

    @Test
    fun `listChildren filters out facet-less zero-size stub by fallback rule`() =
        runTest {
            // No name match — a hypothetical future-locale vault. The belt-and-braces
            // zero-facet + size=0 rule should still catch it.
            val body =
                """
                {
                  "value": [
                    {
                      "id": "future-locale-vault",
                      "name": "SomeFutureLocaleVault",
                      "size": 0,
                      "parentReference": {"path": "/drive/root:"}
                    },
                    {
                      "id": "real-file",
                      "name": "notes.md",
                      "size": 42,
                      "file": {"mimeType": "text/markdown"},
                      "parentReference": {"path": "/drive/root:"}
                    }
                  ]
                }
                """.trimIndent()

            val provider = mockedProvider(body)
            val children = provider.listChildren("/")

            assertEquals(1, children.size, "Fallback rule must filter facet-less stub")
            assertEquals("notes.md", children[0].name)
            provider.close()
        }

    @Test
    fun `listChildren maps fileSystemInfo timestamps in preference to top-level lastModifiedDateTime`() =
        runTest {
            // Graph's top-level lastModifiedDateTime is server-stamped on commit and bumps on
            // permission changes too; the fileSystemInfo facet is the client-supplied local
            // mtime/ctime that round-trips through the API. Linux users see broken timestamps
            // immediately if we read the wrong field — this pins the precedence.
            val body =
                """
                {
                  "value": [
                    {
                      "id": "fsi-file",
                      "name": "round-trip.bin",
                      "size": 7,
                      "lastModifiedDateTime": "2030-01-01T00:00:00Z",
                      "createdDateTime":      "2030-01-01T00:00:00Z",
                      "fileSystemInfo": {
                        "createdDateTime":      "2024-06-01T08:00:00Z",
                        "lastModifiedDateTime": "2024-06-01T09:30:00Z"
                      },
                      "file": {"mimeType": "application/octet-stream"},
                      "parentReference": {"path": "/drive/root:"}
                    },
                    {
                      "id": "no-fsi-file",
                      "name": "legacy.bin",
                      "size": 11,
                      "lastModifiedDateTime": "2025-03-15T12:00:00Z",
                      "createdDateTime":      "2025-03-10T11:00:00Z",
                      "file": {"mimeType": "application/octet-stream"},
                      "parentReference": {"path": "/drive/root:"}
                    }
                  ]
                }
                """.trimIndent()

            val provider = mockedProvider(body)
            val children = provider.listChildren("/")

            val fsi = children.single { it.name == "round-trip.bin" }
            assertEquals(
                java.time.Instant.parse("2024-06-01T09:30:00Z"),
                fsi.modified,
                "modified must come from fileSystemInfo.lastModifiedDateTime when present",
            )
            assertEquals(
                java.time.Instant.parse("2024-06-01T08:00:00Z"),
                fsi.created,
                "created must come from fileSystemInfo.createdDateTime when present",
            )

            val legacy = children.single { it.name == "legacy.bin" }
            assertEquals(
                java.time.Instant.parse("2025-03-15T12:00:00Z"),
                legacy.modified,
                "legacy items without fileSystemInfo must fall back to top-level lastModifiedDateTime",
            )
            assertEquals(
                java.time.Instant.parse("2025-03-10T11:00:00Z"),
                legacy.created,
                "legacy items without fileSystemInfo must fall back to top-level createdDateTime",
            )
            provider.close()
        }

    @Test
    fun `delta maps hard deletions to deleted CloudItems but not a soft removal with state-aware logging`() =
        runTest {
            // Mixed-state delta page: one hard-delete (state=deleted), one soft-remove
            // (state=removed), one stateless removed marker (pre-2017 schema), and one
            // top-level `deleted` facet. The three HARD signals surface as
            // CloudItem.deleted = true; the SOFT removal (state=removed) must NOT — the
            // item still exists, the user merely lost access, and reaping the local copy
            // would destroy still-valid data. The provider's DEBUG log still distinguishes
            // hard from soft from unspecified for diagnostics (logDeletionStateSummary is
            // independent of the deleted-flag decision). Real-world delta deletion items
            // just carry id + removal marker (no name, no facets); richer tombstone
            // shapes are covered by the dedicated short-circuit tests below.
            val body =
                """
                {
                  "value": [
                    {"id":"hd","@microsoft.graph.removed":{"state":"deleted"},"parentReference":{"path":"/drive/root:"}},
                    {"id":"sr","@microsoft.graph.removed":{"state":"removed"},"parentReference":{"path":"/drive/root:"}},
                    {"id":"un","@microsoft.graph.removed":{},"parentReference":{"path":"/drive/root:"}},
                    {"id":"de","deleted":{"state":"deleted"},"parentReference":{"path":"/drive/root:"}},
                    {"id":"al","name":"alive.txt","size":4,"file":{"mimeType":"text/plain"},"parentReference":{"path":"/drive/root:"}}
                  ],
                  "@odata.deltaLink": "https://graph.microsoft.com/v1.0/me/drive/root/delta?token=DONE"
                }
                """.trimIndent()

            val providerLogger =
                org.slf4j.LoggerFactory.getLogger("org.krost.unidrive.onedrive.OneDriveProvider")
                    as ch.qos.logback.classic.Logger
            val previousLevel = providerLogger.level
            providerLogger.level = ch.qos.logback.classic.Level.DEBUG
            val appender =
                ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().also { it.start() }
            providerLogger.addAppender(appender)

            try {
                val provider = mockedProvider(body)
                val page = provider.delta(cursor = "old-cursor")

                assertEquals(5, page.items.size, "All five items surface; deleted ones still appear (engine consumes the flag)")
                assertEquals(
                    3,
                    page.items.count { it.deleted },
                    "Only the three HARD signals (state=deleted, stateless removed, deleted-facet) flag deleted=true",
                )
                assertTrue(
                    page.items.single { it.id == "sr" }.deleted.not(),
                    "the soft removal (state=removed) must NOT be flagged deleted",
                )
                assertEquals(1, page.items.count { !it.deleted && it.name == "alive.txt" })

                val debug =
                    appender.list.firstOrNull {
                        it.level == ch.qos.logback.classic.Level.DEBUG &&
                            it.formattedMessage.contains("Delta page deletion mix")
                    }
                kotlin.test.assertNotNull(debug, "expected diagnostic DEBUG with deletion-kind counts, got: ${appender.list.map { it.formattedMessage }}")
                kotlin.test.assertTrue(
                    debug.formattedMessage.contains("hardDelete=2") &&
                        debug.formattedMessage.contains("softRemove=1") &&
                        debug.formattedMessage.contains("unspecified=1"),
                    "Counts must distinguish hard delete (state=deleted OR deleted-facet) " +
                        "from soft remove (state=removed) from unspecified (facet present, no state). " +
                        "Was: ${debug.formattedMessage}",
                )
                provider.close()
            } finally {
                providerLogger.detachAppender(appender)
                providerLogger.level = previousLevel
            }
        }

    @Test
    fun `delta surfaces a named facet-less zero-size tombstone instead of vault-filtering it`() =
        runTest {
            // A hard-deleted file's tombstone can carry name + size 0 and no
            // file/folder facet — exactly the vault fallback signature. The
            // deletion facet must short-circuit the vault filter, or the remote
            // delete never propagates and a later local edit resurrects the file.
            // The genuine vault stub (no deletion facet) is still filtered.
            val body =
                """
                {
                  "value": [
                    {
                      "id": "vault-stub",
                      "name": "Personal Vault",
                      "size": 0,
                      "parentReference": {"path": "/drive/root:"}
                    },
                    {
                      "id": "tomb-named",
                      "name": "quarterly-report.docx",
                      "size": 0,
                      "@microsoft.graph.removed": {"state": "deleted"},
                      "parentReference": {"path": "/drive/root:"}
                    }
                  ],
                  "@odata.deltaLink": "https://graph.microsoft.com/v1.0/me/drive/root/delta?token=NEXT"
                }
                """.trimIndent()

            val provider = mockedProvider(body)
            val page = provider.delta(cursor = null)

            assertFalse(
                page.items.any { it.id == "vault-stub" },
                "genuine vault stub must still be filtered from delta output",
            )
            val tombstone = page.items.find { it.id == "tomb-named" }
            kotlin.test.assertNotNull(tombstone, "named facet-less zero-size tombstone must survive the vault filter")
            assertTrue(tombstone.deleted, "surviving tombstone must surface as deleted")
            provider.close()
        }

    @Test
    fun `delta surfaces a zero-byte deleted file tombstone as deleted`() =
        runTest {
            // Deleting a zero-byte file yields a tombstone indistinguishable from
            // the vault stub except for the deleted facet — it must reach the
            // engine flagged deleted.
            val body =
                """
                {
                  "value": [
                    {
                      "id": "zero-byte-tomb",
                      "name": "empty.txt",
                      "size": 0,
                      "deleted": {"state": "deleted"},
                      "parentReference": {"path": "/drive/root:"}
                    }
                  ],
                  "@odata.deltaLink": "https://graph.microsoft.com/v1.0/me/drive/root/delta?token=NEXT"
                }
                """.trimIndent()

            val provider = mockedProvider(body)
            val page = provider.delta(cursor = null)

            val tombstone = page.items.find { it.id == "zero-byte-tomb" }
            kotlin.test.assertNotNull(tombstone, "zero-byte deleted file tombstone must survive both delta filters")
            assertTrue(tombstone.deleted, "surviving tombstone must surface as deleted")
            provider.close()
        }

    @Test
    fun `listChildren keeps an ordinary folder that shares the vault display name`() =
        runTest {
            // A real user folder named exactly like the vault carries a folder
            // facet; the name match alone must not drop it. Only the facet-less
            // zero-size stub is the genuine vault.
            val body =
                """
                {
                  "value": [
                    {
                      "id": "user-folder",
                      "name": "Personal Vault",
                      "size": 0,
                      "folder": {"childCount": 4},
                      "parentReference": {"path": "/drive/root:"}
                    },
                    {
                      "id": "vault-stub",
                      "name": "Personal Vault",
                      "size": 0,
                      "parentReference": {"path": "/drive/root:"}
                    }
                  ]
                }
                """.trimIndent()

            val provider = mockedProvider(body)
            val children = provider.listChildren("/")

            assertEquals(1, children.size, "only the genuine facet-less stub is filtered")
            assertEquals("user-folder", children[0].id)
            provider.close()
        }

    @Test
    fun `delta filters out Personal Vault alongside root-item filter`() =
        runTest {
            // Delta response includes:
            //  - the root item itself (filtered by isRootItem — parentReference.path is null)
            //  - the vault (filtered by isPersonalVault)
            //  - one real file
            val body =
                """
                {
                  "value": [
                    {
                      "id": "root-id",
                      "name": "root",
                      "size": 0,
                      "root": {},
                      "folder": {"childCount": 10}
                    },
                    {
                      "id": "vault-stub",
                      "name": "Personal Vault",
                      "size": 0,
                      "parentReference": {"path": "/drive/root:"}
                    },
                    {
                      "id": "real-file",
                      "name": "report.pdf",
                      "size": 2048,
                      "file": {"mimeType": "application/pdf"},
                      "parentReference": {"path": "/drive/root:"}
                    }
                  ],
                  "@odata.deltaLink": "https://graph.microsoft.com/v1.0/me/drive/root/delta?token=NEXT"
                }
                """.trimIndent()

            val provider = mockedProvider(body)
            val page = provider.delta(cursor = null)

            assertEquals(1, page.items.size, "Only the real file survives delta filter")
            assertEquals("report.pdf", page.items[0].name)
            assertFalse(
                page.items.any { it.name == "Personal Vault" },
                "Personal Vault must be filtered from delta output",
            )
            provider.close()
        }
}
