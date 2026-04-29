package org.krost.unidrive.s3

import org.krost.unidrive.ProviderException

class S3Exception(
    message: String,
    val statusCode: Int = 0,
) : ProviderException(message)
