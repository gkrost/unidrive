package org.krost.unidrive

/** Shared HTTP client timeout defaults used across all Ktor-based providers. */
object HttpDefaults {
    const val CONNECT_TIMEOUT_MS: Long = 30_000
    const val SOCKET_TIMEOUT_MS: Long = 60_000
    const val REQUEST_TIMEOUT_MS: Long = 600_000
}
