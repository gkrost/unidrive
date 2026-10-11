package org.krost.unidrive.internxt

import org.krost.unidrive.auth.CredentialProtectionException
import org.krost.unidrive.auth.CredentialProtector
import java.util.Locale

/** Selects a native at-rest credential backend; unsupported systems fail closed. */
object InternxtCredentialProtectors {
    fun forCurrentPlatform(): CredentialProtector = forPlatform(System.getProperty("os.name"))

    internal fun forPlatform(osName: String): CredentialProtector {
        val platform = osName.lowercase(Locale.ROOT)
        return when {
            platform.contains("linux") -> LinuxSecretServiceCredentialProtector()
            platform.contains("windows") -> WindowsDpapiCredentialProtector()
            platform.contains("mac") || platform.contains("darwin") -> MacKeychainCredentialProtector()
            else -> throw CredentialProtectionException("Internxt credential protection is unsupported on this platform")
        }
    }
}
