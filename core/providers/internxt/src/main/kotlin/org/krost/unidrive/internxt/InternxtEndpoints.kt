package org.krost.unidrive.internxt

import org.krost.unidrive.http.EndpointClassifier

/**
 * The words of the Internxt drive gateway and bridge this client calls; any other path segment is an identifier (a folder
 * or file uuid, a bucket, a file id) and becomes `{id}` in an endpoint class (see [EndpointClassifier]). Shard uploads and
 * downloads go to pre-signed URLs on other hosts and are `external`.
 */
internal object InternxtEndpoints {
    val classifier =
        EndpointClassifier(
            apiHosts = setOf(java.net.URI(InternxtConfig.API_BASE_URL).host, "api.internxt.com"),
            literals =
                setOf(
                    "drive", "folders", "files", "content", "v2", "meta", "sync", "storage", "trash", "add", "users", "usage", "limit",
                    "limits", "auth", "login", "access", "refresh", "buckets", "info", "start", "finish",
                ),
        )
}
