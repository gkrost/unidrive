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

    /** Display metadata for the provider (id, displayName, tier, etc.). */
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

    /**
     * Schema for the interactive 'profile add' wizard. Each entry
     * describes one prompt the CLI should issue. Empty list (the
     * default) means this provider has no provider-specific prompts —
     * the wizard collects only the universal name + sync_root + type.
     *
     * Order matters: prompts are issued in list order. Implementations
     * with dependent prompts (e.g. "host" before "port") must order
     * accordingly.
     */
    fun credentialPrompts(): List<PromptSpec> = emptyList()

    /**
     * Checks the non-secret values `profile add` was given before the profile is written, without
     * side effects (nothing is created). Returns the refusal, or null when the values are usable.
     * Default: nothing to check.
     */
    fun preflightAdd(properties: Map<String, String?>): AddRefusal? = null

    /**
     * Mapping of environment-variable name to config-property key for
     * credentials this provider can pick up from the environment.
     *
     * Used by the CLI to warn when an env var is set but the matching
     * config key is also present in `config.toml` (env is ignored in
     * that case). Empty (the default) means this provider does not
     * recognise any environment variables.
     */
    fun envVarMappings(): Map<String, String> = emptyMap()

    /**
     * Whether this provider has an interactive auth flow (typically
     * OAuth) that the CLI `auth` subcommand and the MCP
     * `auth_begin` / `auth_complete` tools should drive.
     *
     * Default false: most providers receive credentials via config
     * and have no interactive begin/complete handshake.
     */
    fun supportsInteractiveAuth(): Boolean = false

    /**
     * Begin an interactive auth flow. Only invoked when
     * [supportsInteractiveAuth] returns true. The throwing default is
     * the sentinel that pairs with the capability gate — UD-014.
     */
    suspend fun beginInteractiveAuth(profileDir: java.nio.file.Path): BeginAuthResult =
        throw UnsupportedOperationException("$id has no interactive auth flow")

    /**
     * Resume an interactive auth flow with the handle from
     * [beginInteractiveAuth]. Returns Success / Pending / Failure.
     */
    suspend fun completeInteractiveAuth(
        profileDir: java.nio.file.Path,
        continuationHandle: String,
        // #866: a one-time secret the flow's completion needs (Dropbox's pasted authorization code). It is
        // an argument of the call, never of the command line: front-ends hand it over out of band.
        secret: String? = null,
    ): CompleteAuthResult =
        throw UnsupportedOperationException("$id has no interactive auth flow")

    /**
     * Abandon an in-flight interactive auth flow. Default no-op.
     * Implementations release per-handle resources (HTTP clients, timers).
     */
    suspend fun cancelInteractiveAuth(continuationHandle: String) { /* no-op */ }
}

/** A stable [token] a front-end switches on and a human [message] (never a secret). */
data class AddRefusal(
    val token: String,
    val message: String,
)

/** Offline credential health diagnostic (no network calls). */
sealed class CredentialHealth {
    object Ok : CredentialHealth()

    data class Warning(
        val message: String,
    ) : CredentialHealth()

    data class Missing(
        val message: String,
    ) : CredentialHealth()

    /**
     * The credential stops working soon. [hours] is the whole hours left (0 = expired already, or under an hour left);
     * [remainingMs], when the provider knows it, is the exact time left and tells the two apart: positive = still valid,
     * zero or negative = expired. Null = only [hours] is known (a 0 then means expired).
     */
    data class ExpiresIn(
        val hours: Long,
        val message: String,
        val remainingMs: Long? = null,
    ) : CredentialHealth()
}
