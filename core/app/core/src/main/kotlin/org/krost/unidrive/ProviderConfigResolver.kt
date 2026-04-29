package org.krost.unidrive

object ProviderConfigResolver {
    fun mergeWithEnv(
        properties: Map<String, String?>,
        envMappings: Map<String, String>,
    ): Map<String, String?> {
        val result = properties.toMutableMap()
        for ((envVar, key) in envMappings) {
            val envValue = System.getenv(envVar)
            if (envValue != null) {
                result[key] = envValue
            }
        }
        return result
    }

    fun requireEnv(
        name: String,
        usageBlock: String,
    ): String =
        System.getenv(name) ?: run {
            System.err.println("Missing required environment variable: $name\n")
            System.err.println(usageBlock)
            System.exit(1)
            throw IllegalStateException("unreachable")
        }
}
