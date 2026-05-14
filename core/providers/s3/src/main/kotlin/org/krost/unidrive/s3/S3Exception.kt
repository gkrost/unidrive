package org.krost.unidrive.s3

import org.krost.unidrive.ProviderException

/**
 * S3-shaped provider exception.
 *
 * UD-203: AWS returns two correlation headers on every response, both
 * worth keeping when something goes wrong:
 *  - `x-amz-request-id` — the primary, surfaced to AWS support tickets.
 *    Carried on the [ProviderException.requestId] base field so the
 *    cross-provider log grep (`requestId=`) finds it without S3-specific
 *    code paths.
 *  - `x-amz-id-2` — the extended (datacenter) trace id, useful for
 *    AWS-internal escalations only. Carried separately on
 *    [extendedRequestId] so an S3-aware operator can pull both without
 *    diluting the cross-provider field.
 */
class S3Exception(
    message: String,
    val statusCode: Int = 0,
    requestId: String? = null,
    val extendedRequestId: String? = null,
) : ProviderException(message, requestId = requestId)
