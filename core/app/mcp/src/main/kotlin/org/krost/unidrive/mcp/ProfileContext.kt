package org.krost.unidrive.mcp

import org.krost.unidrive.CloudProvider
import org.krost.unidrive.CredentialHealth
import org.krost.unidrive.ProviderRegistry
import org.krost.unidrive.sync.*
import java.net.ConnectException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path

class ProfileContext(
    val profileName: String,
    val profileInfo: ProfileInfo,
    val config: SyncConfig,
    val configDir: Path,
    val profileDir: Path,
    val rawConfig: RawSyncConfig,
    private val providerProperties: Map<String, String?>,
) {
    fun openDb(): StateDatabase {
        val db = StateDatabase(profileDir.resolve("state.db"))
        db.initialize()
        return db
    }

    fun isDaemonRunning(): Boolean {
        val socketPath = IpcServer.defaultSocketPath(profileName)
        if (!Files.exists(socketPath)) return false
        return try {
            SocketChannel.open(StandardProtocolFamily.UNIX).use { ch ->
                ch.connect(UnixDomainSocketAddress.of(socketPath))
            }
            true
        } catch (_: ConnectException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    fun createProvider(): CloudProvider {
        val factory =
            ProviderRegistry.get(profileInfo.type)
                ?: throw IllegalStateException("Unknown provider type: ${profileInfo.type}")
        return factory.create(providerProperties, profileDir)
    }

    fun createProviderForProfile(name: String): CloudProvider {
        val info = SyncConfig.resolveProfile(name, rawConfig)
        val properties = rawProviderToProperties(info.rawProvider)
        val factory =
            ProviderRegistry.get(info.type)
                ?: throw IllegalStateException("Unknown provider type: ${info.type}")
        return factory.create(properties, configDir.resolve(name))
    }

    val eventBuffer: EventBuffer by lazy { EventBuffer(profileName).also { it.start() } }

    fun acquireLock(): ProcessLock? {
        Files.createDirectories(profileDir)
        val lock = ProcessLock(profileDir.resolve(".lock"))
        return if (lock.tryLock()) lock else null
    }

    fun checkCredentialHealth(): CredentialHealth {
        val factory =
            ProviderRegistry.get(profileInfo.type)
                ?: return CredentialHealth.Missing("Unknown provider type: ${profileInfo.type}")
        return factory.checkCredentialHealth(providerProperties, profileDir)
    }

    companion object {
        fun rawProviderToProperties(rp: RawProvider?): Map<String, String?> =
            mapOf(
                "bucket" to rp?.bucket,
                "region" to rp?.region,
                "endpoint" to rp?.endpoint,
                "access_key_id" to rp?.access_key_id,
                "secret_access_key" to rp?.secret_access_key,
                "host" to rp?.host,
                "port" to rp?.port?.toString(),
                "user" to rp?.user,
                "remote_path" to rp?.remote_path,
                "root_path" to (rp?.sync_root ?: rp?.root_path),
                "identity" to rp?.identity,
                "password" to rp?.password,
                "client_id" to rp?.client_id,
                "client_secret" to rp?.client_secret,
                "url" to rp?.url,
                "rclone_remote" to rp?.rclone_remote,
                "rclone_path" to rp?.rclone_path,
                "rclone_binary" to rp?.rclone_binary,
                "rclone_config" to rp?.rclone_config,
                "authority_url" to rp?.authority_url,
                "trust_all_certs" to rp?.trust_all_certs?.toString(),
            )

        fun create(
            profileName: String,
            configDir: Path,
        ): ProfileContext {
            val configFile = configDir.resolve("config.toml")
            val rawConfig =
                if (Files.exists(configFile)) {
                    SyncConfig.parseRaw(Files.readString(configFile))
                } else {
                    SyncConfig.parseRaw("[general]\n")
                }

            val profileInfo = SyncConfig.resolveProfile(profileName, rawConfig)
            val config = SyncConfig.load(configFile, profileName)
            val profileDir = configDir.resolve(profileName)

            val properties = rawProviderToProperties(profileInfo.rawProvider)

            return ProfileContext(
                profileName = profileName,
                profileInfo = profileInfo,
                config = config,
                configDir = configDir,
                profileDir = profileDir,
                rawConfig = rawConfig,
                providerProperties = properties,
            )
        }
    }
}
