package org.krost.unidrive.dropbox

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.krost.unidrive.UnidriveJson
import java.nio.file.Files
import java.nio.file.Path

/** One request the provider sent: the endpoint (`files/upload`), its JSON arguments (body or header) and its bytes. */
class Call(
    val endpoint: String,
    val args: JsonObject?,
    val rawArgHeader: String?,
    val body: ByteArray,
    val headers: Map<String, String>,
)

/**
 * A scripted Dropbox: [route] answers each endpoint (status, JSON body, extra headers); unscripted endpoints answer
 * 404. Every request is recorded in [calls]. `users/get_current_account` answers a personal account unless scripted.
 */
class FakeDropbox {
    val calls = mutableListOf<Call>()
    private val routes = mutableMapOf<String, MutableList<(Call) -> Answer>>()

    class Answer(
        val status: Int = 200,
        val body: String = "{}",
        val headers: Map<String, String> = emptyMap(),
        val bytes: ByteArray? = null,
    )

    /** Answers [endpoint] with [answer]; several scripted answers for one endpoint are used in turn, the last one stays. */
    fun on(
        endpoint: String,
        answer: (Call) -> Answer,
    ) {
        routes.getOrPut(endpoint) { mutableListOf() } += answer
    }

    fun json(
        endpoint: String,
        body: String,
        status: Int = 200,
    ) = on(endpoint) { Answer(status, body) }

    fun callsTo(endpoint: String): List<Call> = synchronized(calls) { calls.filter { it.endpoint == endpoint } }

    val engine =
        MockEngine { request ->
            val endpoint = request.url.encodedPath.removePrefix("/2/").removePrefix("/")
            val bytes = request.body.toByteArray()
            val argHeader = request.headers[DropboxApi.API_ARG]
            val argText = argHeader ?: bytes.toString(Charsets.UTF_8).takeIf { endpoint !in CONTENT_ENDPOINTS && it.startsWith("{") }
            val call =
                Call(
                    endpoint,
                    argText?.let { UnidriveJson.parseToJsonElement(it).jsonObject },
                    argHeader,
                    bytes,
                    request.headers.entries().associate { it.key to it.value.first() },
                )
            synchronized(calls) { calls += call }
            answer(call)
        }

    private fun MockRequestHandleScope.answer(call: Call): HttpResponseData {
        val scripted = routes[call.endpoint]
        val answer =
            when {
                scripted != null -> (if (scripted.size > 1) scripted.removeAt(0) else scripted.first())(call)
                call.endpoint == "users/get_current_account" ->
                    Answer(body = """{"account_id":"dbid:a","root_info":{".tag":"user","root_namespace_id":"1","home_namespace_id":"1"}}""")
                else -> Answer(404, """{"error_summary":"unscripted/${call.endpoint}"}""")
            }
        val headers =
            buildList {
                add(HttpHeaders.ContentType to listOf(if (answer.bytes != null) "application/octet-stream" else "application/json"))
                answer.headers.forEach { (k, v) -> add(k to listOf(v)) }
            }
        return respond(
            answer.bytes ?: answer.body.toByteArray(),
            HttpStatusCode.fromValue(answer.status),
            headersOf(*headers.toTypedArray()),
        )
    }

    companion object {
        private val CONTENT_ENDPOINTS =
            setOf("files/upload", "files/upload_session/start", "files/upload_session/append_v2", "files/upload_session/finish", "files/download")

        /** A provider on [fake] whose profile folder [dir] holds a fresh token (refresh token "r1"). */
        fun provider(
            fake: FakeDropbox,
            dir: Path,
            configure: (DropboxConfig) -> DropboxConfig = { it },
            expiresAt: Long = System.currentTimeMillis() + 3_600_000,
        ): DropboxProvider {
            Files.writeString(
                dir.resolve(DropboxAuth.TOKEN_FILE),
                UnidriveJson.encodeToString(DropboxToken.serializer(), DropboxToken("a".repeat(40), expiresAt, "r1")),
            )
            val provider = DropboxProvider(configure(DropboxConfig(appKey = "key", tokenPath = dir)), fake.engine)
            kotlinx.coroutines.runBlocking { provider.authenticate() } // loads the token; no browser
            return provider
        }

        fun file(
            path: String,
            id: String,
            hash: String = "0".repeat(64),
            rev: String = "rev1",
            size: Long = 5,
        ) = """{".tag":"file","name":"${path.substringAfterLast('/')}","path_lower":"${path.lowercase()}","path_display":"$path",""" +
            """"id":"$id","rev":"$rev","size":$size,"client_modified":"2026-01-02T03:04:05Z","server_modified":"2026-01-02T03:04:06Z","content_hash":"$hash"}"""

        fun folder(
            path: String,
            id: String,
        ) = """{".tag":"folder","name":"${path.substringAfterLast('/')}","path_lower":"${path.lowercase()}","path_display":"$path","id":"$id"}"""

        fun deleted(path: String) =
            """{".tag":"deleted","name":"${path.substringAfterLast('/')}","path_lower":"${path.lowercase()}","path_display":"$path"}"""
    }
}
