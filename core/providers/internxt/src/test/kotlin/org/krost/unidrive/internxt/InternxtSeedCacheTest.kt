package org.krost.unidrive.internxt

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.krost.unidrive.internxt.model.InternxtCredentials
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #730: the BIP39 seed used to be re-derived (PBKDF2-HMAC-SHA512, 2048 iterations) on every
 * download and upload — 8.2 % of the Java execution samples in the live JFR. The provider now
 * derives it at most once per mnemonic for the life of the provider: repeated transfers reuse
 * the cached seed, a credentials change re-derives (the replaced bytes are zeroed, never
 * reused), and close/logout zero it.
 *
 * The counting subclass of [InternxtCrypto] is the observation point: `mnemonicToSeed` counts
 * at the exact call the transfer path makes. A provider-level test, not a cache-internals test,
 * so the assertions stay true regardless of how the cache is held.
 */
class InternxtSeedCacheTest {
    private val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    private val otherMnemonic = "legal winner thank year wave sausage worth useful legal winner thank yellow"
    private val bucket = "6928426c1a2316b856c9ab81"
    private val fileUuid = "file-uuid-1"
    private val fileId = "bridge-file-id-1"
    private val index64 = "11".repeat(32)
    private val shardUrl = "https://shard-host.invalid/seed-cache-shard"

    private val tmp: Path = Files.createTempDirectory("ud-seed-cache-")
    private val counting = CountingCrypto()
    private val reference = InternxtCrypto()

    @AfterTest
    fun cleanUp() {
        tmp.toFile().deleteRecursively()
    }

    private class CountingCrypto : InternxtCrypto() {
        val derived: MutableList<Pair<String, ByteArray>> = Collections.synchronizedList(mutableListOf())

        val calls: Int get() = derived.size

        override fun mnemonicToSeed(mnemonic: String): ByteArray {
            val seed = super.mnemonicToSeed(mnemonic)
            derived += mnemonic to seed
            return seed
        }
    }

    private fun token(): String {
        val payloadB64 =
            java.util.Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString("""{"exp":9999999999}""".toByteArray())
        return "header.$payloadB64.signature"
    }

    private fun credentials(mnemonic: String) =
        InternxtCredentials(
            jwt = token(),
            mnemonic = mnemonic,
            rootFolderId = "root-folder-uuid",
            email = "test@example.invalid",
            bridgeUser = "bridge-user",
            bridgeUserId = "bridge-secret",
            bucket = bucket,
        )

    private fun authServiceOf(provider: InternxtProvider): AuthService {
        val authField = InternxtProvider::class.java.getDeclaredField("authService")
        authField.isAccessible = true
        return authField.get(provider) as AuthService
    }

    private fun setCredentials(
        provider: InternxtProvider,
        creds: InternxtCredentials,
    ) {
        val credsField = AuthService::class.java.getDeclaredField("credentials")
        credsField.isAccessible = true
        credsField.set(authServiceOf(provider), creds)
    }

    /** The bytes the provider currently holds — the array a replacement or close/logout zeroes. */
    private fun heldSeed(provider: InternxtProvider): ByteArray {
        val cachedField = InternxtProvider::class.java.getDeclaredField("cachedSeed")
        cachedField.isAccessible = true
        val holder = cachedField.get(provider) ?: throw AssertionError("no seed is held")
        val seedField = holder.javaClass.getDeclaredField("seed")
        seedField.isAccessible = true
        return seedField.get(holder) as ByteArray
    }

    /** The seed exactly as the transfer paths receive it: `seedFor` is the single method they call. */
    private fun seedFor(
        provider: InternxtProvider,
        mnemonic: String,
    ): ByteArray {
        val method = InternxtProvider::class.java.getDeclaredMethod("seedFor", String::class.java)
        method.isAccessible = true
        return method.invoke(provider, mnemonic) as ByteArray
    }

    private fun newProvider(): InternxtProvider {
        val provider = InternxtProvider(InternxtConfig(tokenPath = tmp))
        setCredentials(provider, credentials(mnemonic))
        val cryptoField = InternxtProvider::class.java.getDeclaredField("crypto")
        cryptoField.isAccessible = true
        cryptoField.set(provider, counting)
        val apiField = InternxtProvider::class.java.getDeclaredField("api")
        apiField.isAccessible = true
        val api = apiField.get(provider) as InternxtApiService
        val httpField = InternxtApiService::class.java.getDeclaredField("httpClient")
        httpField.isAccessible = true
        (httpField.get(api) as? HttpClient)?.close()
        httpField.set(api, HttpClient(MockEngine { request -> handle(request) }))
        return provider
    }

    private fun MockRequestHandleScope.handle(request: HttpRequestData): HttpResponseData {
        val json = headersOf(HttpHeaders.ContentType, "application/json")
        return when {
            request.url.encodedPath == "/drive/files/$fileUuid/meta" && request.method == HttpMethod.Get ->
                respond(
                    """{"uuid":"$fileUuid","bucket":"$bucket","fileId":"$fileId","size":"0","status":"EXISTS"}""",
                    HttpStatusCode.OK,
                    json,
                )
            request.url.encodedPath == "/buckets/$bucket/files/$fileId/info" && request.method == HttpMethod.Get ->
                respond(
                    """{"bucket":"$bucket","id":"$fileId","index":"$index64","shards":[""" +
                        """{"index":0,"hash":"h","url":"$shardUrl"}]}""",
                    HttpStatusCode.OK,
                    json,
                )
            request.url.host == "shard-host.invalid" && request.method == HttpMethod.Get ->
                respond(
                    "0123456789A".toByteArray(),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/octet-stream"),
                )
            request.url.host == "shard-host.invalid" && request.method == HttpMethod.Put ->
                respond("", HttpStatusCode.OK)
            request.url.encodedPath == "/v2/buckets/$bucket/files/start" ->
                respond("""{"uploads":[{"index":0,"uuid":"shard-1","url":"$shardUrl"}]}""", HttpStatusCode.OK, json)
            request.url.encodedPath == "/v2/buckets/$bucket/files/finish" ->
                respond(
                    """{"id":"bucket-entry-1","index":"${"aa".repeat(32)}","bucket":"$bucket","name":"enc"}""",
                    HttpStatusCode.OK,
                    json,
                )
            request.url.encodedPath == "/drive/files" && request.method == HttpMethod.Post ->
                respond(
                    """{"uuid":"new-uuid","plainName":"x","type":"txt","size":"1","bucket":"$bucket","status":"EXISTS"}""",
                    HttpStatusCode.OK,
                    json,
                )
            else ->
                respond(
                    """{"error":"unexpected ${request.method.value} ${request.url}"}""",
                    HttpStatusCode.NotFound,
                    json,
                )
        }
    }

    private fun localFile(name: String): Path =
        tmp.resolve(name).also { Files.write(it, ByteArray(100) { 0x41 }) }

    @Test
    fun `repeated transfers on the same credentials derive the seed once`() =
        runTest {
            val provider = newProvider()
            try {
                provider.downloadById(fileUuid, "/one.bin", tmp.resolve("one.bin"))
                provider.downloadById(fileUuid, "/two.bin", tmp.resolve("two.bin"))
                provider.upload(localFile("letter.txt"), "/letter.txt", existingRemoteId = null, onProgress = null)

                assertEquals(1, counting.calls, "two downloads and one upload must share one PBKDF2 derivation")
                assertEquals(mnemonic, counting.derived.single().first)
            } finally {
                provider.close()
            }
        }

    @Test
    fun `concurrent transfers share the one derivation`() =
        runTest {
            val provider = newProvider()
            try {
                coroutineScope {
                    (1..3)
                        .map { i -> async { provider.downloadById(fileUuid, "/c$i.bin", tmp.resolve("c$i.bin")) } }
                        .awaitAll()
                }
                assertEquals(1, counting.calls, "concurrent downloads on a cold cache must derive once")
            } finally {
                provider.close()
            }
        }

    @Test
    fun `a credentials change re-derives the seed and zeroes the previous bytes`() =
        runTest {
            val provider = newProvider()
            try {
                provider.downloadById(fileUuid, "/one.bin", tmp.resolve("one.bin"))
                val replaced = heldSeed(provider)
                assertTrue(replaced.any { it != 0.toByte() }, "sanity: a real seed was derived")

                setCredentials(provider, credentials(otherMnemonic))
                provider.downloadById(fileUuid, "/two.bin", tmp.resolve("two.bin"))

                assertEquals(2, counting.calls, "the new mnemonic must derive a fresh seed")
                assertEquals(otherMnemonic, counting.derived[1].first)
                assertTrue(replaced.all { it == 0.toByte() }, "the replaced held seed must be zeroed, not reused")
                assertTrue(
                    heldSeed(provider).contentEquals(reference.mnemonicToSeed(otherMnemonic)),
                    "the newly held seed must be the BIP39 seed of the new mnemonic",
                )
            } finally {
                provider.close()
            }
        }

    // #730 review: handing out the held array let a concurrent replacement/close zero the bytes
    // a transfer was about to derive from — a download failure, or on upload a file encrypted
    // under a key the mnemonic can never reproduce. Every caller now gets a copy.
    @Test
    fun `a returned seed is a copy - mutating it cannot corrupt the cache`() =
        runTest {
            val provider = newProvider()
            try {
                provider.downloadById(fileUuid, "/one.bin", tmp.resolve("one.bin"))
                val held = heldSeed(provider)
                val expected = reference.mnemonicToSeed(mnemonic)

                val returned = seedFor(provider, mnemonic)
                assertTrue(returned !== held, "the cache must hand out a copy, not the held array")
                returned.fill(0)

                assertTrue(held.contentEquals(expected), "zeroing the returned copy must not touch the held seed")

                provider.downloadById(fileUuid, "/two.bin", tmp.resolve("two.bin"))
                assertEquals(1, counting.calls, "a mutated copy must not force a re-derivation")
                assertTrue(
                    held.contentEquals(expected),
                    "the held seed is still the derived one after the second transfer",
                )
            } finally {
                provider.close()
            }
        }

    @Test
    fun `close zeroes the held seed`() =
        runTest {
            val provider = newProvider()
            provider.downloadById(fileUuid, "/one.bin", tmp.resolve("one.bin"))
            val held = heldSeed(provider)

            provider.close()

            assertTrue(held.all { it == 0.toByte() }, "close() must zero the held seed")
        }

    @Test
    fun `logout zeroes the held seed`() =
        runTest {
            val provider = newProvider()
            provider.downloadById(fileUuid, "/one.bin", tmp.resolve("one.bin"))
            val held = heldSeed(provider)

            provider.logout()

            assertTrue(held.all { it == 0.toByte() }, "logout() must zero the held seed")
        }
}
