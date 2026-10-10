rootProject.name = "unidrive"

// Composite monorepo root with a single included build (`core/`). The
// platform scope is docs/adr/multi-platform.md; the earlier `ui/` and
// `shell-win/` tiers were removed.
includeBuild("core")
