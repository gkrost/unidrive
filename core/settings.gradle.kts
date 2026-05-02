rootProject.name = "unidrive"

include(
    "app:core", "app:sync", "app:xtra", "app:cli", "app:mcp",
    "providers:internxt", "providers:localfs",
    "providers:onedrive", "providers:rclone", "providers:s3",
    "providers:sftp", "providers:webdav",
)
