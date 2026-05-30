rootProject.name = "unidrive"

include(
    "app:core", "app:sync", "app:sync-tracking", "app:hydration", "app:cli", "app:config",
    "providers:internxt", "providers:onedrive", "providers:localfs",
)
