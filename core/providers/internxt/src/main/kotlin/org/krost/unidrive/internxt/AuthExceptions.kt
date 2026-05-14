package org.krost.unidrive.internxt

import org.krost.unidrive.AuthenticationException

/**
 * Email or password rejected by `/auth/login` or `/auth/login/access`.
 * Caller should re-prompt for credentials.
 */
class BadCredentialsException(
    message: String,
    requestId: String? = null,
) : AuthenticationException(message, requestId = requestId)

/**
 * `/auth/login` returned `tfa: true` but no TFA code was supplied.
 * Caller should prompt for the 6-digit TOTP and retry with the same
 * email + password.
 */
class TfaRequiredException(
    message: String = "Two-factor authentication code required",
    requestId: String? = null,
) : AuthenticationException(message, requestId = requestId)

/**
 * `/auth/login/access` rejected the supplied TFA code.
 * Caller should re-prompt for the TFA code only; email + password are fine.
 */
class TfaInvalidException(
    message: String,
    requestId: String? = null,
) : AuthenticationException(message, requestId = requestId)
