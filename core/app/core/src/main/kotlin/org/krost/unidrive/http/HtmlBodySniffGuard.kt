package org.krost.unidrive.http

import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.io.IOException

/**
 * UD-340 (UD-333 / UD-231 / UD-293 lineage): assert the response body
 * is NOT HTML before writing it to disk.
 *
 * **Why.** CDN edge nodes occasionally return HTTP 200 with
 * `Content-Type: text/html` instead of the actual file content —
 * captive-portal pages, throttle redirects, expired-URL login pages.
 * If unidrive's download path streams those bytes straight to disk,
 * the user ends up with a small HTML file in place of their multi-GB
 * video / archive / image.
 *
 * For Internxt specifically the failure is **silent file corruption**:
 * the AES-CTR cipher XORs the HTML against its keystream, producing
 * high-entropy garbage that passes the UD-226 NUL-stub sweep. The
 * user has no signal until they try to open the file.
 *
 * **History.** OneDrive added the guard under UD-231 → UD-293; HiDrive
 * + Internxt copied it under UD-333. Three identical 8-line blocks.
 * UD-340 lifts to `:app:core/http` so future providers' download
 * paths inherit the guard automatically — and a missed call shows up
 * in code review as a missing import, not a copy-paste omission.
 *
 * Use at the top of every streaming-download `execute { response -> }`
 * block, before any bytes are written:
 * ```kotlin
 * statement.execute { response ->
 *     assertNotHtml(response, contextMsg = "Download $remotePath")
 *     val channel = response.bodyAsChannel()
 *     // ... ring-buffer write loop
 * }
 * ```
 *
 * Throws [IOException] with a bounded snippet of the HTML body so the
 * operator can spot a captive portal vs throttle vs expired URL from
 * the log line. Bounded read (via [readBoundedErrorBody]) prevents the
 * UD-293 OOM where a CDN attached a multi-GB body to a fake
 * `text/html` response.
 *
 * @param response the active HTTP response (already inside an
 *   `execute { ... }` or `prepareGet { }.execute { ... }` block)
 * @param contextMsg optional caller context — typically the remote
 *   path or operation name — included in the exception message for
 *   easier log correlation
 * @throws IOException when `response.contentType()` matches
 *   `text/html`. The message contains the status code, the full
 *   content-type, the optional context, and a 200-char snippet of
 *   the HTML body.
 */
public suspend fun assertNotHtml(
    response: HttpResponse,
    contextMsg: String? = null,
) {
    val ct = response.contentType()
    if (ct != null && ct.match(ContentType.Text.Html)) {
        val snippet = readBoundedErrorBody(response, maxBytes = 4096).take(200)
        val ctxLabel = contextMsg?.let { " [$it]" }.orEmpty()
        throw IOException(
            "Download returned HTML instead of file bytes (status=${response.status.value}, " +
                "Content-Type=$ct)$ctxLabel: $snippet",
        )
    }
}
