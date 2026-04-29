package org.krost.unidrive.webdav

import org.krost.unidrive.CloudProvider
import org.krost.unidrive.ConfigurationException
import org.krost.unidrive.CredentialHealth
import org.krost.unidrive.ProviderFactory
import org.krost.unidrive.ProviderMetadata
import java.nio.file.Path

class WebDavProviderFactory : ProviderFactory {
    override val id = "webdav"

    override fun describeConnection(
        properties: Map<String, String?>,
        profileDir: Path,
    ): String {
        val parts = mutableListOf<String>()
        properties["url"]?.takeIf { it.isNotBlank() }?.let { parts += it }
        properties["user"]?.takeIf { it.isNotBlank() }?.let { parts += "user=$it" }
        if (!properties["password"].isNullOrBlank()) parts += "password=****"
        return if (parts.isEmpty()) "webdav provider" else "webdav (${parts.joinToString(", ")})"
    }

    override val metadata =
        ProviderMetadata(
            id = "webdav",
            displayName = "WebDAV",
            description = "WebDAV protocol — Nextcloud, ownCloud, Synology, QNAP, Hetzner Storage Box",
            authType = "HTTP Basic (username + password)",
            encryption = "Transport-level (HTTPS)",
            jurisdiction = "Self-hosted (your server's location)",
            gdprCompliant = true,
            cloudActExposure = false,
            signupUrl = null,
            tier = "Self-hosted",
            // UD-263: 4 covers Synology DSM (observed 500 above ~4 parallel
            // uploads) and SharePoint (≤ 2). Nextcloud / ownCloud / mod_dav
            // tolerate higher (5-8); a per-server-family knob would let
            // power users raise this. See docs/providers/webdav-robustness.md
            // §5 for the full server-family matrix.
            maxConcurrentTransfers = 4,
        )

    override fun create(
        properties: Map<String, String?>,
        tokenPath: Path,
    ): CloudProvider {
        val url =
            properties["url"]?.takeIf { it.isNotBlank() }
                ?: throw ConfigurationException("webdav", "Required property 'url' is missing")
        val user =
            properties["user"]?.takeIf { it.isNotBlank() }
                ?: throw ConfigurationException("webdav", "Required property 'user' is missing")
        val password =
            properties["password"]?.takeIf { it.isNotBlank() }
                ?: throw ConfigurationException("webdav", "Required property 'password' is missing")

        val trustAllCerts =
            properties["trust_all_certs"]?.toBooleanStrictOrNull()
                ?: isLanUrl(url)

        // UD-277: per-profile override of the size-adaptive timeout knobs.
        // Both are optional; absent → WebDavConfig defaults (10-min floor,
        // 50 KiB/s minimum throughput). Set min_throughput_kbps = 0 to
        // disable size-adaptive bounding (UD-285 unbounded behaviour).
        val uploadFloorMs =
            properties["upload_floor_timeout_ms"]?.toLongOrNull()
                ?: 600_000L
        val uploadMinKBps =
            properties["upload_min_throughput_kbps"]?.toLongOrNull()
                ?: 50L

        val config =
            WebDavConfig(
                baseUrl = url,
                username = user,
                password = password,
                tokenPath = tokenPath,
                trustAllCerts = trustAllCerts,
                uploadFloorTimeoutMs = uploadFloorMs,
                uploadMinThroughputBytesPerSecond = uploadMinKBps * 1024,
            )
        return WebDavProvider(config)
    }

    override fun isAuthenticated(
        properties: Map<String, String?>,
        profileDir: Path,
    ): Boolean =
        properties["url"]?.isNotBlank() == true &&
            properties["user"]?.isNotBlank() == true &&
            properties["password"]?.isNotBlank() == true

    override fun checkCredentialHealth(
        properties: Map<String, String?>,
        profileDir: Path,
    ): CredentialHealth {
        val missing = mutableListOf<String>()
        if (properties["url"].isNullOrBlank()) missing += "url"
        if (properties["user"].isNullOrBlank()) missing += "user"
        if (properties["password"].isNullOrBlank()) missing += "password"
        if (missing.isNotEmpty()) {
            return CredentialHealth.Missing("Missing: ${missing.joinToString(", ")}")
        }
        return CredentialHealth.Ok
    }

    private fun isLanUrl(url: String): Boolean {
        val host =
            url
                .removePrefix("https://")
                .removePrefix("http://")
                .substringBefore("/")
                .substringBefore(":")
        if (host == "localhost" ||
            host.startsWith("127.") ||
            host.startsWith("10.") ||
            host.startsWith("192.168.") ||
            host.endsWith(".local")
        ) {
            return true
        }
        // 172.16.0.0 – 172.31.255.255
        if (host.startsWith("172.")) {
            val second = host.removePrefix("172.").substringBefore(".").toIntOrNull()
            if (second != null && second in 16..31) return true
        }
        return false
    }
}
