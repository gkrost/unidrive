package org.krost.unidrive.onedrive

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import java.nio.file.Files
import kotlin.test.*
import kotlin.time.Duration.Companion.minutes

class DeltaDiagnosticTest {
    companion object {
        private const val TEST_FOLDER = "/unidrive_ci_test"
        private val shouldRun = System.getenv("UNIDRIVE_INTEGRATION_TESTS")?.toBoolean() ?: false

        private fun checkEnabled(): Boolean {
            if (!shouldRun) return false
            val config = OneDriveConfig()
            val tokenFile = config.tokenPath.resolve("token.json")
            return Files.exists(tokenFile)
        }
    }

    private lateinit var provider: OneDriveProvider

    @BeforeTest
    fun setUp() =
        runTest {
            if (!checkEnabled()) return@runTest
            provider = OneDriveProvider()
            provider.authenticate()
            try {
                provider.getMetadata(TEST_FOLDER)
            } catch (e: Exception) {
                provider.createFolder(TEST_FOLDER)
            }
        }

    @Test
    fun `diagnostic - observe delta behavior after upload`() =
        runTest(timeout = 3.minutes) {
            if (!checkEnabled()) return@runTest
            val ts = System.currentTimeMillis()
            val remotePath = "$TEST_FOLDER/diag_$ts.txt"

            // Get baseline cursor — follow all pages
            var baseline = provider.delta(cursor = null)
            var totalBaselineItems = baseline.items.size
            while (baseline.hasMore) {
                baseline = provider.delta(baseline.cursor)
                totalBaselineItems += baseline.items.size
            }
            val cursor = baseline.cursor
            println("DIAG: baseline cursor obtained after full pagination, $totalBaselineItems total items")
            println("DIAG: cursor value (first 80 chars): ${cursor.take(80)}")

            // Show all items in /unidrive_ci_test
            val testItems = baseline.items.filter { it.path.startsWith(TEST_FOLDER) }
            println("DIAG: items under $TEST_FOLDER: ${testItems.map { "${it.path} (deleted=${it.deleted})" }}")

            // Upload
            val tempFile = Files.createTempFile("diag", ".txt")
            Files.writeString(tempFile, "diagnostic content $ts")
            val uploadResult = provider.upload(tempFile, remotePath)
            Files.delete(tempFile)
            println("DIAG: uploaded $remotePath -> id=${uploadResult.id}, path=${uploadResult.path}")

            // Poll delta with retries — follow ALL pages
            for (attempt in 1..10) {
                delay(3000)
                val allItems = mutableListOf<org.krost.unidrive.CloudItem>()
                var page = provider.delta(cursor = cursor)
                allItems.addAll(page.items)
                while (page.hasMore) {
                    page = provider.delta(page.cursor)
                    allItems.addAll(page.items)
                }
                val testItems = allItems.filter { it.path.startsWith(TEST_FOLDER) || it.name.startsWith("diag_") }
                println("DIAG: attempt $attempt - ${allItems.size} total items, ${testItems.size} test-related")
                for (item in testItems) {
                    println("DIAG:   test item: path='${item.path}' name='${item.name}' deleted=${item.deleted}")
                }
                val found = allItems.find { it.path == remotePath }
                if (found != null) {
                    println("DIAG: FOUND by exact path on attempt $attempt")
                    break
                }
                val foundByName = allItems.find { it.name == "diag_$ts.txt" }
                if (foundByName != null) {
                    println("DIAG: FOUND by name on attempt $attempt: path='${foundByName.path}' (expected '$remotePath')")
                    break
                }
                if (allItems.isEmpty()) {
                    println("DIAG: empty delta (no changes) on attempt $attempt — this is correct incremental behavior")
                    break
                }
                println("DIAG: not found yet in ${allItems.size} items, retrying...")
            }

            // Get the cursor AFTER upload was detected (need fresh incremental cursor)
            var postUploadPage = provider.delta(cursor = cursor)
            while (postUploadPage.hasMore) {
                postUploadPage = provider.delta(postUploadPage.cursor)
            }
            val cursor2 = postUploadPage.cursor
            println("DIAG: post-upload cursor obtained")

            // Delete and observe with fresh cursor
            provider.delete(remotePath)
            println("DIAG: deleted $remotePath")

            // Make raw HTTP call to see exact JSON the Graph API returns
            val tokenManager =
                OneDriveProvider::class.java
                    .getDeclaredField(
                        "tokenManager",
                    ).apply { isAccessible = true }
                    .get(provider) as TokenManager
            val token = tokenManager.getValidToken().accessToken
            val httpClient = HttpClient()

            for (attempt in 1..10) {
                delay(5000)
                val response =
                    httpClient.request(cursor2) {
                        bearerAuth(token)
                        method = HttpMethod.Get
                    }
                val rawBody = response.bodyAsText()
                println("DIAG: delete attempt $attempt (${attempt * 5}s) - raw response length=${rawBody.length}")
                // Print first 2000 chars of raw JSON for visibility
                println("DIAG: raw JSON (first 2000): ${rawBody.take(2000)}")
                val json = Json { ignoreUnknownKeys = true }
                val parsed = json.parseToJsonElement(rawBody).jsonObject
                val items = parsed["value"]?.jsonArray ?: JsonArray(emptyList())
                println("DIAG: ${items.size} items in delta response")
                for (item in items) {
                    val obj = item.jsonObject
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "(null)"
                    val hasRemoved = obj["@microsoft.graph.removed"] != null
                    val hasDeleted = obj["deleted"] != null
                    val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: ""
                    // Print ALL keys for each item to see what fields are present
                    println("DIAG:   item keys: ${obj.keys}")
                    println("DIAG:   raw: name='$name' id='$id' hasRemoved=$hasRemoved hasDeleted=$hasDeleted")
                    if (hasRemoved || hasDeleted) {
                        println("DIAG:   >>> FOUND DELETION MARKER: removed=${obj["@microsoft.graph.removed"]} deleted=${obj["deleted"]}")
                    }
                }
                if (items.any { it.jsonObject["@microsoft.graph.removed"] != null || it.jsonObject["deleted"] != null }) {
                    println("DIAG: DELETION DETECTED in raw JSON on attempt $attempt")
                    break
                }
                // Also check if there's a nextLink for more pages
                val nextLink = parsed["@odata.nextLink"]?.jsonPrimitive?.contentOrNull
                val deltaLink = parsed["@odata.deltaLink"]?.jsonPrimitive?.contentOrNull
                println("DIAG: nextLink=${nextLink != null} deltaLink=${deltaLink != null}")
            }
            httpClient.close()
        }
}
