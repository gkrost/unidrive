package org.krost.unidrive.internxt

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #486: a new file kept its content and name but not its modification time: the create sent none and the server stamped
 * the upload time. CreateFileDto takes an optional `modificationTime`, as ReplaceFileDto does (which the replace path
 * already sends). Every create now carries the local file's time: a normal upload and an empty one (#485).
 */
class InternxtCreateMtimeTest {
    private val bucket = "6928426c1a2316b856c9ab81"
    private val shardUrl = "https://shard-host.invalid/mtime-put"
    private val creates: MutableList<JsonObject> = Collections.synchronizedList(mutableListOf())
    private val tmp: Path = Files.createTempDirectory("ud-mtime-")
    private val dated = Instant.parse("2001-02-03T04:05:06Z")

    @AfterTest
    fun cleanUp() {
        tmp.toFile().deleteRecursively()
    }

    private fun newProvider(): InternxtProvider {
        val provider = InternxtProvider(InternxtConfig(tokenPath = tmp))
        val authField = InternxtProvider::class.java.getDeclaredField("authService")
        authField.isAccessible = true
        val authService = authField.get(provider)
        val credsField = authService.javaClass.getDeclaredField("credentials")
        credsField.isAccessible = true
        val payloadB64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("""{"exp":9999999999}""".toByteArray())
        credsField.set(
            authService,
            org.krost.unidrive.internxt.model.InternxtCredentials(
                jwt = "header.$payloadB64.signature",
                mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
                rootFolderId = "root-folder-uuid",
                email = "test@example.invalid",
                bridgeUser = "bridge-user",
                bridgeUserId = "bridge-secret",
                bucket = bucket,
            ),
        )
        val apiField = InternxtProvider::class.java.getDeclaredField("api")
        apiField.isAccessible = true
        val api = apiField.get(provider) as InternxtApiService
        val httpField = InternxtApiService::class.java.getDeclaredField("httpClient")
        httpField.isAccessible = true
        (httpField.get(api) as? HttpClient)?.close()
        val json = headersOf("Content-Type", "application/json")
        httpField.set(
            api,
            HttpClient(
                MockEngine { request ->
                    val url = request.url.toString()
                    when {
                        url.contains("/v2/buckets/") && url.contains("/files/start") ->
                            respond("""{"uploads":[{"index":0,"uuid":"shard-1","url":"$shardUrl"}]}""", HttpStatusCode.OK, json)
                        url == shardUrl -> respond("", HttpStatusCode.OK)
                        url.contains("/v2/buckets/") && url.contains("/files/finish") ->
                            respond("""{"id":"bucket-entry-1","index":"${"aa".repeat(32)}","bucket":"$bucket","name":"enc"}""", HttpStatusCode.OK, json)
                        request.url.encodedPath == "/drive/files" && request.method == HttpMethod.Post -> {
                            val text =
                                when (val b = request.body) {
                                    is io.ktor.http.content.ByteArrayContent -> String(b.bytes(), Charsets.UTF_8)
                                    is io.ktor.http.content.TextContent -> b.text
                                    else -> "{}"
                                }
                            creates += Json.parseToJsonElement(text).jsonObject
                            respond("""{"uuid":"new-uuid","plainName":"x","type":"txt","size":"1","bucket":"$bucket","status":"EXISTS"}""", HttpStatusCode.OK, json)
                        }
                        else -> respond("""{"error":"unexpected ${request.method.value} $url"}""", HttpStatusCode.NotFound, json)
                    }
                },
            ),
        )
        return provider
    }

    private fun localFile(name: String, bytes: Int): Path =
        tmp.resolve(name).also {
            Files.write(it, ByteArray(bytes) { 0x41 })
            Files.setLastModifiedTime(it, FileTime.from(dated))
        }

    @Test
    fun `a new file is created with its local modification time`() =
        runTest {
            val provider = newProvider()
            try {
                provider.upload(localFile("letter.txt", 100), "/letter.txt", existingRemoteId = null, onProgress = null)
                assertEquals(dated.toString(), creates.single()["modificationTime"]?.jsonPrimitive?.content)
            } finally {
                provider.close()
            }
        }

    @Test
    fun `a new empty file is created with its local modification time too`() =
        runTest {
            val provider = newProvider()
            try {
                provider.upload(localFile("empty.txt", 0), "/empty.txt", existingRemoteId = null, onProgress = null)
                assertEquals(dated.toString(), creates.single()["modificationTime"]?.jsonPrimitive?.content)
            } finally {
                provider.close()
            }
        }
}
