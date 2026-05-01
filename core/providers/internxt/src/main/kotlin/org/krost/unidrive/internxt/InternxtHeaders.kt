package org.krost.unidrive.internxt

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.http.ContentType

/**
 * UD-350: shared Internxt header block. Every authenticated Internxt
 * call sets the same `internxt-client / internxt-version /
 * x-internxt-desktop-header` triplet plus `Accept: application/json`.
 *
 * Callers that also need authorization (Bearer JWT for the main API,
 * legacy basic auth for the bridge) apply their own `Authorization`
 * header alongside this helper — see `InternxtApiService.applyAuth`
 * for the canonical Bearer pattern.
 */
internal fun HttpRequestBuilder.applyInternxtHeaders() {
    header("internxt-client", InternxtConfig.CLIENT_NAME)
    header("internxt-version", InternxtConfig.CLIENT_VERSION)
    header("x-internxt-desktop-header", InternxtConfig.DESKTOP_HEADER)
    accept(ContentType.Application.Json)
}
