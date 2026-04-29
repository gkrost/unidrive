package org.krost.unidrive.sftp

import org.krost.unidrive.ProviderException

class SftpException(
    message: String,
    val statusCode: Int = 0,
) : ProviderException(message)
