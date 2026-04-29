"""composite_map.py — lookup table from Gradle module path to composite dir.

The unidrive repo has a single Gradle composite (`core/`). The
previously-imported `ui/` composite was removed in
[ADR-0013](../../../docs/adr/0013-ui-removal.md) and `shell-win/` was
removed in [ADR-0011](../../../docs/adr/0011-shell-win-removal.md). An
MCP that wants to run `./gradlew :<module>:test` must `cd` into the
right composite first; this module owns that mapping so the server
stays dumb.

The registry below is built from:

- core/settings.gradle.kts:
    include("app:core", "app:sync", "app:xtra", "app:cli", "app:mcp",
            "providers:hidrive", "providers:internxt", "providers:localfs",
            "providers:onedrive", "providers:rclone", "providers:s3",
            "providers:sftp", "providers:webdav")
"""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class CompositeTarget:
    """A resolved module lookup.

    composite: directory name under repo root. Currently only "core".
    gradle_path: the Gradle task-prefix to use, e.g. ":app:cli". For
        task assembly use gradle_path + ":" + task.
    """

    composite: str
    gradle_path: str


# Explicit registry. Keys are the module names as they appear in gradle task
# invocations, WITH the leading colon. We also accept "app:cli" (no colon)
# at lookup time by normalising.
_CORE_MODULES = [
    ":app:core",
    ":app:sync",
    ":app:xtra",
    ":app:cli",
    ":app:mcp",
    ":providers:hidrive",
    ":providers:internxt",
    ":providers:localfs",
    ":providers:onedrive",
    ":providers:rclone",
    ":providers:s3",
    ":providers:sftp",
    ":providers:webdav",
]

_REGISTRY: dict[str, CompositeTarget] = {}
for m in _CORE_MODULES:
    _REGISTRY[m] = CompositeTarget(composite="core", gradle_path=m)


def _normalise(module: str) -> str:
    module = module.strip()
    if not module.startswith(":"):
        module = ":" + module
    return module


def resolve(module: str) -> CompositeTarget:
    """Map a Gradle module path to its composite + task prefix.

    Raises KeyError with a readable message if the module is unknown. Callers
    should surface that message in the MCP error channel — agents pick up on
    "unknown module" much faster than a Gradle "project not found" tail.
    """
    key = _normalise(module)
    hit = _REGISTRY.get(key)
    if hit is None:
        known = ", ".join(sorted(_REGISTRY.keys()))
        raise KeyError(
            f"unknown Gradle module '{module}'. Known modules: {known}",
        )
    return hit


def gradle_task(target: CompositeTarget, task: str) -> str:
    """Build ':<module>:<task>'.

    `task` should NOT start with a colon. Example:
        gradle_task(core/:app:cli, "test")   -> ":app:cli:test"
    """
    task = task.lstrip(":")
    return f"{target.gradle_path}:{task}"


def all_composites() -> list[str]:
    """Return the unique composite directories, for `build("*")` mode."""
    return sorted({t.composite for t in _REGISTRY.values()})


def modules_in(composite: str) -> list[str]:
    """All known module keys whose composite matches."""
    return sorted(k for k, v in _REGISTRY.items() if v.composite == composite)
