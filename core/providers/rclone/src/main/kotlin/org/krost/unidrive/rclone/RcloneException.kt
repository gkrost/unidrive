package org.krost.unidrive.rclone

import org.krost.unidrive.ProviderException

class RcloneException(
    message: String,
    val exitCode: Int = 0,
) : ProviderException(message)
