package org.krost.unidrive.cli.ext

import org.krost.unidrive.CloudProvider
import org.krost.unidrive.sync.SyncConfig
import java.nio.file.Path

/**
 * Marks a type as part of the public extension contract. Extensions MAY
 * depend on annotated types; MUST NOT depend on anything else in the
 * public CLI module.
 */
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.ANNOTATION_CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
)
@Retention(AnnotationRetention.BINARY)
annotation class PublicApi

/**
 * Entry point for out-of-tree CLI extensions. Discovered via
 * java.util.ServiceLoader at CLI startup.
 *
 * Lifecycle: implementations are loaded via ServiceLoader and
 * constructed with their no-arg constructor. Keep constructors empty;
 * do all setup work in [register]. ServiceLoader instances are cached
 * for the JVM lifetime — do not rely on re-instantiation.
 *
 * Stability: additive-only within v1. Method removals or signature
 * changes require a v2 interface in a new package.
 */
@PublicApi
interface CliExtension {
    /** Human-readable id, e.g. "benchmark". Used in logs and conflict errors. */
    val id: String

    /**
     * Called once at CLI startup. Must not throw; implementations should
     * wrap their work in try/catch and log. The loader catches throwables
     * as a safety net but a thrown exception still spams stderr.
     */
    fun register(registrar: CliExtensionRegistrar)
}

/** Handed to an extension during [CliExtension.register]. */
@PublicApi
interface CliExtensionRegistrar {
    /**
     * Attach a picocli-annotated command object under [parentCommandName]
     * (e.g. "provider"). Use "" for top-level.
     *
     * MVP scope: [parentCommandName] must name a direct top-level command
     * (or be empty for the root). Deeper nesting is out of scope for v1.
     *
     * Throws IllegalStateException if [parentCommandName] is unknown or
     * if another extension already registered the same command name
     * under that parent.
     */
    fun addSubcommand(
        parentCommandName: String,
        command: Any,
    )

    /** Read-only services. */
    val services: CliServices
}

/**
 * Narrow, stable, read-only facade over CLI state. Everything an
 * extension could plausibly need — and nothing more. Additions require
 * a BACKLOG item and changelog entry.
 */
@PublicApi
interface CliServices {
    fun resolveProfile(name: String): ProfileView

    fun createProvider(profileName: String): CloudProvider

    fun loadSyncConfig(profileName: String): SyncConfig

    fun configBaseDir(): Path

    fun isProviderAuthenticated(profileName: String): Boolean

    fun listProfileNames(): List<String>

    val unidriveVersion: String

    /** Terminal formatting helper; honours NO_COLOR / non-TTY environments. */
    val formatter: Formatter
}

/** Read-only snapshot of what the CLI knows about a profile. */
@PublicApi
data class ProfileView(
    val name: String,
    val type: String,
    val rawEndpoint: String?,
    val rawHost: String?,
    val rawUrl: String?,
)

/** Terminal text formatting. Implementations are colour-aware. */
@PublicApi
interface Formatter {
    fun bold(s: String): String

    fun dim(s: String): String

    fun underline(s: String): String
}
