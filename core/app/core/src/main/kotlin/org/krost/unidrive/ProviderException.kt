package org.krost.unidrive

open class ProviderException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

class AuthenticationException(
    message: String,
    cause: Throwable? = null,
) : ProviderException(message, cause)
