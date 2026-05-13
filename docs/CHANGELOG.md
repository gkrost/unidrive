# Changelog

All notable changes to this project will be documented in this file.
Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Intake of `benchmark` module from unidrive-closed (UD-701 cloud
  provider speed ranking). New CLI surface: `unidrive benchmark ...`
  (top-level subcommand on `Main`; was `unidrive provider benchmark`
  in the private repo before the public `provider` subcommand was
  removed in commit b07d864).
- Intake of `cli-full` shadow-JAR aggregator from unidrive-closed
  (transitively bundles `:app:cli` + `:app:benchmark`).
- Intake of `e2e-360` Dockerized integration harness from
  unidrive-closed (UD-800 CloudForge follow-up).
- `docs/CLOUD_PROVIDERS_DATABASE.md` (cloud provider catalog).
- `docs/dev/BENCHMARK_HANDOVER.md` (benchmark operational handover).
- UD-401 (Enhanced provider table Phase 2).
- This `CHANGELOG.md` file.
