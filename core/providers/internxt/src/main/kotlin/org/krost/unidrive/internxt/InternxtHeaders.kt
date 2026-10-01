package org.krost.unidrive.internxt

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.http.ContentType

/**
 * Shared Internxt header block. Every authenticated Internxt call sets
 * `internxt-client` and `internxt-version` plus `Accept: application/json`.
 *
 * `x-internxt-desktop-header` is deliberately NOT sent by default. In Internxt's
 * official SDK it is an optional token that a host application may supply and
 * that is forwarded only when set; unidrive has no such token, and the value we
 * used to send was a made-up placeholder that exists in no Internxt client. It
 * is sent only when `INTERNXT_DESKTOP_HEADER` (or [InternxtConfig.desktopHeader])
 * is set to a non-empty value, kept for testing against the live API.
 *
 * The client name and version come from [config] so they can be overridden at
 * deploy time (env vars `INTERNXT_CLIENT_NAME` / `INTERNXT_CLIENT_VERSION`).
 *
 * Callers that also need authorization (Bearer JWT for the main API,
 * legacy basic auth for the bridge) apply their own `Authorization`
 * header alongside this helper — see `InternxtApiService.applyAuth`
 * for the canonical Bearer pattern.
 */
internal fun HttpRequestBuilder.applyInternxtHeaders(config: InternxtConfig) {
    header("internxt-client", config.clientName)
    header("internxt-version", config.clientVersion)
    config.desktopHeader?.takeIf { it.isNotBlank() }?.let { header("x-internxt-desktop-header", it) }
    accept(ContentType.Application.Json)
}
