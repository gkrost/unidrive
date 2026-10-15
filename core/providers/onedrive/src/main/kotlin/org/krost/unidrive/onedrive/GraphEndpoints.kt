package org.krost.unidrive.onedrive

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import org.krost.unidrive.HttpDefaults
import org.krost.unidrive.http.EndpointClassifier
import org.krost.unidrive.http.RequestId
import org.krost.unidrive.http.RequestMetricsTap

/**
 * The words of the Graph API this client calls; any other path segment is an identifier and becomes `{id}` in an endpoint
 * class (see [EndpointClassifier]). Items addressed by name (`root:/Folder/file:/content`) become `{path}`. Pre-signed
 * upload and download URLs are on other hosts and are `external`.
 */
internal object GraphEndpoints {
    val classifier =
        EndpointClassifier(
            apiHosts = setOf(java.net.URI(OneDriveConfig.GRAPH_BASE_URL).host),
            literals =
                setOf(
                    OneDriveConfig.GRAPH_VERSION, "me", "drive", "drives", "root", "items", "children", "content", "delta",
                    "createUploadSession", "createLink", "permissions", "sharedWithMe", "subscriptions",
                ),
        )
}

/** The Graph HTTP client: timeouts, the `User-Agent` [userAgent] on every request, the request id, and the attempt metrics reported through [tap]. */
internal fun graphHttpClient(
    tap: RequestMetricsTap,
    userAgent: String,
    engine: HttpClientEngine? = null,
    profile: String? = null,
): HttpClient {
    val configure: HttpClientConfig<*>.() -> Unit = {
        install(HttpTimeout) {
            connectTimeoutMillis = HttpDefaults.CONNECT_TIMEOUT_MS
            socketTimeoutMillis = HttpDefaults.SOCKET_TIMEOUT_MS
            requestTimeoutMillis = HttpDefaults.REQUEST_TIMEOUT_MS
        }
        install(UserAgent) { agent = userAgent }
        // #644: the token path's leaf is the profile name; Graph answers request-id / client-request-id.
        install(RequestId) {
            metrics = tap
            provider = "onedrive"
            endpoints = GraphEndpoints.classifier
            this.profile = profile
            serverRequestIdHeaders = listOf("request-id", "client-request-id")
        }
    }
    return if (engine != null) HttpClient(engine, configure) else HttpClient(configure)
}
