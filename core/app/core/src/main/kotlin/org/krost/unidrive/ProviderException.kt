package org.krost.unidrive

/**
 * Base type for any provider-originated failure that the sync engine
 * may want to log, classify, or report to the user.
 *
 * ## UD-203: server request-id correlation
 *
 * The optional [requestId] field carries the provider's server-side
 * request identifier when the failure originated from an HTTP
 * response that included one. Provider-specific factories populate it
 * from the canonical header for that provider:
 *
 * | Provider | Header(s) read |
 * |----------|----------------|
 * | OneDrive (Microsoft Graph) | `request-id`, fallback `client-request-id` |
 * | Internxt                   | `x-request-id` |
 * | S3 / S3-compatible         | `x-amz-request-id` (primary); `x-amz-id-2` is captured separately by the S3 layer when needed |
 * | HiDrive                    | TBD — populate when verified |
 * | WebDAV                     | none standard — leave null |
 * | SFTP                       | n/a (not HTTP) |
 *
 * Logging callers can grep for `requestId=` on every `ProviderException`
 * subclass without knowing which provider produced it. `null` means
 * either the provider doesn't expose a request-id, or the failure was
 * raised before the response headers were observed.
 */
open class ProviderException(
    message: String,
    cause: Throwable? = null,
    val requestId: String? = null,
) : Exception(message, cause)

open class AuthenticationException(
    message: String,
    cause: Throwable? = null,
    requestId: String? = null,
) : ProviderException(message, cause, requestId)

/**
 * UD-203: helper for logging call sites. Returns ` requestId=<id>` (with
 * leading space) if the throwable is a [ProviderException] subclass that
 * carries a non-null id, otherwise the empty string. The leading space
 * makes the suffix safe to concatenate into an existing log message
 * without separator gymnastics:
 *
 * ```
 * log.warn("Action failed for {}: {}{}", path, e.message, requestIdSuffix(e))
 * ```
 *
 * Designed for grep — operators see `requestId=<id>` on the error line
 * and can plug the id directly into a support ticket regardless of
 * which provider raised the exception.
 */
fun requestIdSuffix(t: Throwable?): String {
    val id = (t as? ProviderException)?.requestId ?: return ""
    return " requestId=$id"
}
