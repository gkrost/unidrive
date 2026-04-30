package org.krost.unidrive

import kotlinx.serialization.json.Json

/**
 * UD-343: shared `Json { ignoreUnknownKeys; isLenient }` configuration.
 *
 * Pre-UD-343 every Ktor-using provider service built its own `Json {}`
 * instance with the same two flags. ProviderFactories that read TOML
 * stashed yet another. Test files instantiated more. Ten+ duplicate
 * declarations in total.
 *
 * The flag combination is the right default for unidrive's wire formats:
 * `ignoreUnknownKeys = true` lets us add new fields server-side without
 * breaking client deserialization, and `isLenient = true` accepts
 * minor format variations like unquoted keys / trailing commas that
 * some upstream APIs emit.
 *
 * Use:
 * ```kotlin
 * val parsed = UnidriveJson.decodeFromString<MyResponse>(body)
 * ```
 *
 * If a provider needs different flags (e.g. strict mode for a security
 * boundary), declare a local `Json { ... }` at the call site with a
 * comment explaining why the shared default isn't enough.
 */
public val UnidriveJson: Json =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
