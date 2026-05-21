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
 * The triplet's values come from [config] so they can be overridden at
 * deploy time (env vars `INTERNXT_CLIENT_NAME` / `INTERNXT_CLIENT_VERSION`
 * / `INTERNXT_DESKTOP_HEADER`, or direct construction of [InternxtConfig]
 * with overrides) without rebuilding the jar. Defaults preserve the
 * historical wire shape — see [InternxtConfig] companion constants.
 *
 * Callers that also need authorization (Bearer JWT for the main API,
 * legacy basic auth for the bridge) apply their own `Authorization`
 * header alongside this helper — see `InternxtApiService.applyAuth`
 * for the canonical Bearer pattern.
 */
internal fun HttpRequestBuilder.applyInternxtHeaders(config: InternxtConfig) {
    header("internxt-client", config.clientName)
    header("internxt-version", config.clientVersion)
    header("x-internxt-desktop-header", config.desktopHeader)
    accept(ContentType.Application.Json)
}
