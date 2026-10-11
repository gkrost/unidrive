package org.krost.unidrive.internxt

import org.krost.unidrive.auth.CredentialProtectionException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class InternxtCredentialProtectorsTest {
    @Test
    fun `selects a native protector for each supported host`() {
        assertIs<LinuxSecretServiceCredentialProtector>(InternxtCredentialProtectors.forPlatform("Linux"))
        assertIs<WindowsDpapiCredentialProtector>(InternxtCredentialProtectors.forPlatform("Windows 11"))
        assertIs<MacKeychainCredentialProtector>(InternxtCredentialProtectors.forPlatform("Mac OS X"))
    }

    @Test
    fun `unsupported hosts fail closed`() {
        assertFailsWith<CredentialProtectionException> {
            InternxtCredentialProtectors.forPlatform("Plan 9")
        }
    }
}
