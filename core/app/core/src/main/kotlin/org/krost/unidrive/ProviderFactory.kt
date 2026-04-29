package org.krost.unidrive

import java.nio.file.Path

/**
 * SPI interface for provider discovery via [java.util.ServiceLoader].
 *
 * Each provider module implements this interface and declares it in
 * `META-INF/services/org.krost.unidrive.ProviderFactory`.
 * The CLI and other consumers discover providers at runtime by scanning the classpath.
 */
interface ProviderFactory {
    /** Provider type identifier (e.g. "sftp", "onedrive"). */
    val id: String

    /** Display metadata for `provider list` / `provider info` commands. */
    val metadata: ProviderMetadata

    /**
     * Create a [CloudProvider] from pre-resolved configuration properties.
     *
     * @param properties provider-agnostic key-value map. Keys are the TOML field names
     *   from `config.toml` (e.g. `host`, `port`, `user`, `bucket`, `client_id`).
     *   The caller resolves config-file-vs-env-var precedence before passing.
     * @param tokenPath directory where this profile stores tokens/credentials on disk.
     * @throws ConfigurationException if required properties are missing or invalid.
     */
    fun create(
        properties: Map<String, String?>,
        tokenPath: Path,
    ): CloudProvider

    /**
     * Check whether this provider has enough configuration to be considered authenticated.
     *
     * @param properties same map as [create].
     * @param profileDir directory where this profile stores tokens/credentials on disk.
     */
    fun isAuthenticated(
        properties: Map<String, String?>,
        profileDir: Path,
    ): Boolean

    /**
     * Offline credential health check — no network calls.
     *
     * Inspects token files, env vars, config completeness to produce a diagnostic.
     * Default implementation delegates to [isAuthenticated].
     */
    fun checkCredentialHealth(
        properties: Map<String, String?>,
        profileDir: Path,
    ): CredentialHealth =
        if (isAuthenticated(properties, profileDir)) {
            CredentialHealth.Ok
        } else {
            CredentialHealth.Missing("Not configured or missing credentials")
        }

    /**
     * Human-readable one-liner describing the connection target for `--verbose` output.
     *
     * Implementations should include enough detail to identify *which* account/server
     * is being used (e.g. bucket name, host:port, remote name) while masking secrets.
     * Default returns `"$id provider"`.
     */
    fun describeConnection(
        properties: Map<String, String?>,
        profileDir: Path,
    ): String = "$id provider"
}

/** Offline credential health diagnostic (no network calls). */
sealed class CredentialHealth {
    object Ok : CredentialHealth()

    data class Warning(
        val message: String,
    ) : CredentialHealth()

    data class Missing(
        val message: String,
    ) : CredentialHealth()

    data class ExpiresIn(
        val hours: Long,
        val message: String,
    ) : CredentialHealth()
}
