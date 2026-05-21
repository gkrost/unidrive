rootProject.name = "unidrive"

// Composite monorepo root. Linux-MVP scope per ADR-0012 keeps a single
// included build (`core/`); the previously-imported `ui/` tier was
// removed in ADR-0013 and `shell-win/` in ADR-0011.
includeBuild("core")
