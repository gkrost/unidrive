rootProject.name = "unidrive"

// Composite monorepo root. The Linux-MVP scope (docs/adr/linux-only.md,
// docs/adr/multi-platform.md) keeps a single included build (`core/`); the
// previously-imported `ui/` and `shell-win/` tiers were removed.
includeBuild("core")
