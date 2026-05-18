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
        System.getenv(name)
            ?: throw IllegalStateException("Missing required environment variable: $name\n\n$usageBlock")
}
