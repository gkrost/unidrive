package org.krost.unidrive.cli

import org.krost.unidrive.HashAlgorithm
import org.krost.unidrive.ProviderRegistry
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class CloudProviderContractTest {
    @Test
    fun `onedrive declares QuickXor hash algorithm`() {
        val tmp = Files.createTempDirectory("onedrive-contract")
        try {
            val provider =
                ProviderRegistry.get("onedrive")!!.create(
                    properties = mapOf("client_id" to "test-client"),
                    tokenPath = tmp,
                )
            assertEquals(HashAlgorithm.QuickXor, provider.hashAlgorithm())
        } finally {
            tmp.toFile().deleteRecursively()
        }
    }
}
