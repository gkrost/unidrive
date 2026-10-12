package org.krost.unidrive.http

/**
 * Turns a request into an endpoint class such as `GET /drive/folders/content/{id}/files`: the HTTP method and the
 * path with everything that is data replaced. Built so that nothing identifying can get through, not so that
 * identifiers are found and removed:
 *
 * - **Allow-list.** A path segment stays only when it is in [literals], the vocabulary of the API (`drive`, `items`,
 *   `children`, ...); every other segment is `{id}`. A new endpoint therefore shows up as `{id}` until its words are
 *   added, never as a leaked identifier.
 * - **Path addressing.** Graph addresses items by name, `root:/Folder/file.txt:/content`. Everything between the two
 *   colons is `{path}`, whatever it holds.
 * - **No query.** The query string, fragment, user info and port are never looked at.
 * - **Foreign hosts.** A host that is not in [apiHosts] (a pre-signed upload or download URL, a tenant's own host) is
 *   `external`: its path is not even templated.
 */
class EndpointClassifier(
    apiHosts: Set<String>,
    private val literals: Set<String>,
) {
    private val hosts = apiHosts.mapTo(HashSet()) { it.lowercase() }

    fun classify(
        method: String,
        host: String,
        encodedPath: String,
    ): String {
        val verb = method.uppercase().filter { it in 'A'..'Z' }.take(MAX_METHOD_CHARS).ifEmpty { "UNKNOWN" }
        if (host.lowercase() !in hosts) return "$verb external"
        val first = encodedPath.indexOf(':')
        if (first < 0) return "$verb /${segments(encodedPath)}"
        val second = encodedPath.indexOf(':', first + 1)
        val head = segments(encodedPath.substring(0, first))
        val tail = if (second < 0) "" else ":" + segments(encodedPath.substring(second + 1)).let { if (it.isEmpty()) "" else "/$it" }
        return "$verb /$head:{path}$tail"
    }

    private fun segments(path: String): String =
        path
            .split('/')
            .filter { it.isNotEmpty() }
            .take(MAX_SEGMENTS)
            .joinToString("/") { if (it in literals) it else "{id}" }

    companion object {
        private const val MAX_SEGMENTS = 12
        private const val MAX_METHOD_CHARS = 8

        /** Classifies nothing: every request is `METHOD external`. For a client whose provider has not named its endpoints. */
        val OPAQUE = EndpointClassifier(emptySet(), emptySet())
    }
}
