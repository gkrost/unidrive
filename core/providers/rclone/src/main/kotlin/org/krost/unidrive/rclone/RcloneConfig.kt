package org.krost.unidrive.rclone

import java.nio.file.Path

data class RcloneConfig(
    val remote: String,
    val path: String = "",
    val rcloneBinary: String = "rclone",
    val rcloneConfigPath: String? = null,
    val tokenPath: Path,
)
