package org.krost.unidrive.http

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable

/**
 * UD-234 / UD-336: collapse non-JSON HTTP error bodies (HTML error pages,
 * captive-portal redirects, vendor-branded 503 templates) to a first-line
 * preview plus a char-count tail. JSON bodies pass through unchanged so
 * structured-field callers (UD-227 `retryAfterSeconds`, etc.) keep working.
 *
 * One SharePoint 503 body is ~60 lines of inline CSS + branding; without
 * truncation the noise drowns the diagnostic hint and bloats `unidrive.log`
 * by tens of MiB across a long-running watch session.
 *
 * Originally OneDrive-private at `GraphApiService.truncateErrorBody`;
 * lifted to `:app:core` under UD-336 (UD-334 Part A) so HiDrive / Internxt
 * / S3 / Rclone-stderr / WebDAV can share one implementation.
 */
public fun truncateErrorBody(body: String): String {
    val trimmed = body.trimStart()
    if (trimmed.startsWith("{") || trimmed.startsWith("[")) return body
    val firstLine =
        body
            .lineSequence()
            .firstOrNull()
            ?.trim()
            .orEmpty()
            .take(200)
    val totalLen = body.length
    return if (totalLen <= 200) body else "$firstLine … [$totalLen chars, non-JSON body truncated]"
}

/**
 * UD-293 / UD-336: bounded read off the raw response channel for diagnostic
 * purposes (HTML body sniff in error paths). `bodyAsText()` materialises
 * the entire response into a String — a CDN that misreports a 2.3 GB binary
 * as `Content-Type: text/html` then OOMs the diagnostic path with
 * `Can't create an array of size 2_233_659_189`. The bounded read caps the
 * allocation at [maxBytes] regardless of what the server claims.
 *
 * The result is run through [truncateErrorBody] so non-JSON bodies don't
 * dominate the log layout. Callers that already know the body is JSON and
 * want raw bytes should use `response.bodyAsText()` directly with a
 * provider-side size guard.
 *
 * On any exception (channel already consumed, network died), returns
 * `<body unavailable>` rather than throwing — diagnostic paths shouldn't
 * crash the wrapping flow.
 *
 * Originally OneDrive-private at `GraphApiService.readBoundedErrorBody`;
 * lifted to `:app:core` under UD-336 (UD-334 Part A).
 */
public suspend fun readBoundedErrorBody(
    response: HttpResponse,
    maxBytes: Int = 4096,
): String =
    try {
        val channel = response.bodyAsChannel()
        val buf = ByteArray(maxBytes)
        val read = channel.readAvailable(buf, 0, maxBytes).coerceAtLeast(0)
        truncateErrorBody(String(buf, 0, read, Charsets.UTF_8))
    } catch (_: Exception) {
        "<body unavailable>"
    }
