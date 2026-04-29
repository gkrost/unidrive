package org.krost.unidrive.webdav

import org.krost.unidrive.ProviderException

class WebDavException(
    message: String,
    val statusCode: Int = 0,
) : ProviderException(message)
