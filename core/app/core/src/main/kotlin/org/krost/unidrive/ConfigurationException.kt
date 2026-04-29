package org.krost.unidrive

/**
 * Thrown by [ProviderFactory.create] when required configuration properties are missing or invalid.
 *
 * The CLI catches this and prints a user-friendly error message (no stack trace).
 */
class ConfigurationException(
    val providerId: String,
    override val message: String,
) : RuntimeException("[$providerId] $message")
