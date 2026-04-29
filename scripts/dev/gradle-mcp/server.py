"""server.py — MCP server wrapping targeted Gradle invocations for unidrive.

Ticket: UD-718. Sibling to `scripts/dev/oauth-mcp/server.py`; same Python-MCP
structure (stdio transport, `list_tools`/`call_tool` handlers).

Runs on the user's native side so `./gradlew` (which dispatches to
`gradlew.bat` on Windows) can actually find the JVM toolchain; the MSIX
sandbox hides it. See memory `project_msix_sandbox`.

Tools:

  test(module, test_class=None, test_method=None)
      Selective JUnit run. Maps to `./gradlew :<module>:test` under the right
      composite, optionally with `--tests <class>[.<method>]`. Parses JUnit
      XML for structured { passed, failed, skipped, failure_details }.

  build(modules=["*"], skip_lint=False)
      Incremental build. "*" fans out to every composite; a list of module
      paths builds each under its composite. `skip_lint=True` excludes the
      ktlint tasks for the compile-only inner loop.

  ktlint_sync()
      Thin wrapper around `scripts/dev/ktlint-sync.sh`. Returns which .kt /
      baseline.xml files changed in the working tree after the run. Does NOT
      re-implement the bash script's logic — failure modes stay in one place.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import subprocess
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from mcp.server import Server
from mcp.server.stdio import stdio_server
from mcp.types import TextContent, Tool

from composite_map import (
    CompositeTarget,
    all_composites,
    gradle_task,
    modules_in,
    resolve,
)

log = logging.getLogger("unidrive-gradle")
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s - %(message)s",
)

app = Server("unidrive-gradle")

# Repo root = three dirs up from this file (scripts/dev/gradle-mcp/server.py).
REPO_ROOT = Path(__file__).resolve().parents[3]

# Tail length for captured Gradle stdout/stderr on failure. Long enough to
# show the actual compile error; short enough to not flood the agent context.
LOG_TAIL_LINES = 80

# Stack-trace cap per failing JUnit case. Matches ticket spec.
STACK_TRACE_LINES = 20


# ---------- subprocess helpers ----------------------------------------------


def _gradlew_name() -> str:
    """Pick the wrapper script flavour for the host OS.

    On Windows, Python's subprocess does NOT honour the shebang in `gradlew`;
    we have to invoke `gradlew.bat` explicitly. Elsewhere use the POSIX
    `./gradlew`. Detected via os.name rather than sys.platform to stay
    consistent with the rest of the repo's Python tooling.
    """
    return "gradlew.bat" if os.name == "nt" else "./gradlew"


def _run_gradle(
    composite_dir: Path,
    args: list[str],
    timeout_s: int = 600,
) -> tuple[int, str, str, float]:
    """Invoke the Gradle wrapper in `composite_dir` with `args`.

    Returns (returncode, stdout, stderr, duration_s). Never raises on a
    non-zero Gradle exit — the caller decides what that means.

    `shell=True` on Windows lets us invoke `gradlew.bat` without worrying
    about PATHEXT. On POSIX we stay with shell=False and an explicit argv.
    """
    wrapper = _gradlew_name()
    cmd: list[str] | str
    use_shell: bool
    if os.name == "nt":
        # Quote the wrapper path in case the composite dir contains spaces.
        joined = " ".join([wrapper, *(_quote_win(a) for a in args)])
        cmd = joined
        use_shell = True
    else:
        cmd = [wrapper, *args]
        use_shell = False

    start = time.monotonic()
    proc = subprocess.run(  # noqa: S603 — wrapper path is fixed, args are validated upstream.
        cmd,
        cwd=str(composite_dir),
        shell=use_shell,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=timeout_s,
    )
    duration = time.monotonic() - start
    return proc.returncode, proc.stdout or "", proc.stderr or "", duration


def _quote_win(arg: str) -> str:
    """Minimal cmd.exe quoting — wrap in double quotes iff arg has whitespace.

    Gradle task names, `--tests`, and `-x ...Check` don't contain the
    characters that would need real escaping. Keep this simple; upgrade if a
    caller ever needs embedded quotes.
    """
    if any(c.isspace() for c in arg) or not arg:
        return '"' + arg.replace('"', '\\"') + '"'
    return arg


def _tail(text: str, n: int = LOG_TAIL_LINES) -> str:
    """Last-N-lines of a captured stream, suitable for embedding in JSON."""
    lines = text.splitlines()
    if len(lines) <= n:
        return text
    return "\n".join(lines[-n:])


# ---------- test tool -------------------------------------------------------


@dataclass
class TestCaseFailure:
    test_name: str
    message: str
    stack_trace: str  # already truncated to STACK_TRACE_LINES


def _module_rel_path(module: str) -> str:
    """Turn ':app:cli' into 'app/cli' for filesystem lookups.

    Matches the convention in ktlint-sync.sh.
    """
    mod = module.lstrip(":")
    return mod.replace(":", "/")


def _junit_results_dir(target: CompositeTarget, module: str) -> Path:
    """Locate build/test-results/test/*.xml for a module.

    For a root-only composite (none today, but the original ui/ was one)
    the XML would live directly under build/test-results/test/.
    """
    composite_dir = REPO_ROOT / target.composite
    if target.gradle_path:
        rel = _module_rel_path(target.gradle_path)
        return composite_dir / rel / "build" / "test-results" / "test"
    return composite_dir / "build" / "test-results" / "test"


def _parse_junit_xml(results_dir: Path) -> tuple[int, int, int, list[TestCaseFailure]]:
    """Aggregate across every TEST-*.xml in `results_dir`.

    Returns (passed, failed, skipped, failures). "passed" excludes both
    skipped and failed, matching the convention surfaced by most test UIs.

    Gracefully returns zeros + empty list if the dir doesn't exist (e.g. the
    test task itself failed to compile and never produced XML). The caller is
    responsible for surfacing the gradle stderr in that case.
    """
    if not results_dir.exists():
        return 0, 0, 0, []

    total = 0
    failed = 0
    skipped = 0
    errors = 0
    failures: list[TestCaseFailure] = []

    for xml_path in sorted(results_dir.glob("TEST-*.xml")):
        try:
            root = ET.parse(xml_path).getroot()
        except ET.ParseError as e:
            log.warning("skipping malformed junit xml %s: %s", xml_path, e)
            continue
        # Root is <testsuite>. Attributes carry the totals.
        total += int(root.attrib.get("tests", "0"))
        failed += int(root.attrib.get("failures", "0"))
        errors += int(root.attrib.get("errors", "0"))
        skipped += int(root.attrib.get("skipped", "0"))

        for case in root.iter("testcase"):
            test_name = f"{case.attrib.get('classname', '')}.{case.attrib.get('name', '')}"
            for bad in list(case.iter("failure")) + list(case.iter("error")):
                msg = bad.attrib.get("message", "")
                trace_raw = (bad.text or "").strip()
                trace_lines = trace_raw.splitlines()[:STACK_TRACE_LINES]
                failures.append(
                    TestCaseFailure(
                        test_name=test_name,
                        message=msg,
                        stack_trace="\n".join(trace_lines),
                    ),
                )

    # JUnit splits compile/runtime failures across two attributes; the agent
    # only cares about "something's broken". Merge.
    total_failed = failed + errors
    passed = total - total_failed - skipped
    if passed < 0:
        # Defensive: malformed XML giving mismatched counts. Clamp.
        passed = 0
    return passed, total_failed, skipped, failures


def _tool_test(args: dict[str, Any]) -> dict[str, Any]:
    module = args["module"]
    test_class = args.get("test_class")
    test_method = args.get("test_method")

    target = resolve(module)
    composite_dir = REPO_ROOT / target.composite

    gradle_args = [gradle_task(target, "test"), "--no-daemon"]
    if test_class:
        filter_ = test_class
        if test_method:
            filter_ = f"{test_class}.{test_method}"
        gradle_args.extend(["--tests", filter_])

    log.info("gradle test composite=%s args=%s", target.composite, gradle_args)
    rc, stdout, stderr, duration = _run_gradle(composite_dir, gradle_args)

    results_dir = _junit_results_dir(target, module)
    passed, failed, skipped, failures = _parse_junit_xml(results_dir)

    result: dict[str, Any] = {
        "module": module,
        "composite": target.composite,
        "passed": passed,
        "failed": failed,
        "skipped": skipped,
        "duration_s": round(duration, 2),
        "gradle_exit_code": rc,
    }
    if failures:
        result["failure_details"] = [
            {
                "test_name": f.test_name,
                "message": f.message,
                "stack_trace": f.stack_trace,
            }
            for f in failures
        ]
    # If gradle failed but XML shows nothing (compile failure, config error),
    # surface the stderr tail so the agent isn't left guessing.
    if rc != 0 and not failures:
        result["stderr_tail"] = _tail(stderr or stdout)
    return result


# ---------- build tool ------------------------------------------------------


_LINT_EXCLUDES = [
    "-x", "ktlintCheck",
    "-x", "ktlintMainSourceSetCheck",
    "-x", "ktlintTestSourceSetCheck",
]


def _tool_build(args: dict[str, Any]) -> dict[str, Any]:
    raw_modules = args.get("modules") or ["*"]
    skip_lint = bool(args.get("skip_lint", False))

    # Expand "*" to "build every composite at its root level". Otherwise each
    # entry is a module path routed to its composite.
    plans: list[tuple[str, Path, list[str], str]] = []
    # entry tuple: (label, cwd, gradle_args, key_for_result_bucket)
    if raw_modules == ["*"] or "*" in raw_modules:
        for comp in all_composites():
            gradle_args = ["build", "--no-daemon"]
            if skip_lint:
                gradle_args.extend(_LINT_EXCLUDES)
            plans.append((comp, REPO_ROOT / comp, gradle_args, comp))
    else:
        for module in raw_modules:
            target = resolve(module)
            task = gradle_task(target, "build")
            gradle_args = [task, "--no-daemon"]
            if skip_lint:
                gradle_args.extend(_LINT_EXCLUDES)
            plans.append((module, REPO_ROOT / target.composite, gradle_args, module))

    built: list[str] = []
    failed: list[dict[str, Any]] = []
    total_start = time.monotonic()

    for label, cwd, gradle_args, key in plans:
        log.info("gradle build label=%s args=%s", label, gradle_args)
        rc, stdout, stderr, dur = _run_gradle(cwd, gradle_args)
        if rc == 0:
            built.append(key)
        else:
            failed.append({
                "target": key,
                "gradle_exit_code": rc,
                "duration_s": round(dur, 2),
                "log_tail": _tail(stderr or stdout),
            })

    return {
        "built": built,
        "failed": failed,
        "duration_s": round(time.monotonic() - total_start, 2),
        "skip_lint": skip_lint,
    }


# ---------- ktlint_sync tool ------------------------------------------------


def _git_status_short() -> list[tuple[str, str]]:
    """Parse `git status --short` into (status_code, path) pairs.

    Uses -z so paths with spaces/unicode don't need unquoting. `git` is
    available by definition (the repo is a git worktree).
    """
    proc = subprocess.run(  # noqa: S603 — fixed argv.
        ["git", "status", "--short", "-z"],
        cwd=str(REPO_ROOT),
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    if proc.returncode != 0:
        return []
    # -z separates records with NUL. Each record is "XY path" — sometimes
    # followed by " -> new" for renames, but that case also ends at NUL.
    out: list[tuple[str, str]] = []
    for rec in proc.stdout.split("\0"):
        if not rec or len(rec) < 4:
            continue
        # Record format: 2 chars of status, 1 space, then path.
        status = rec[:2]
        path = rec[3:]
        out.append((status, path))
    return out


def _tool_ktlint_sync() -> dict[str, Any]:
    # Snapshot the pre-run dirty set so we can diff.
    before = {p for _, p in _git_status_short()}

    script_path = REPO_ROOT / "scripts" / "dev" / "ktlint-sync.sh"
    if not script_path.exists():
        return {"error": f"ktlint-sync.sh not found at {script_path}"}

    # Bash invocation is portable across the repo's two supported dev hosts:
    # Windows git-bash (MINGW) + Linux. On Windows, `bash.exe` shipped with
    # Git for Windows is on PATH for any shell that has `git` — which is a
    # precondition for the repo to build at all.
    start = time.monotonic()
    proc = subprocess.run(  # noqa: S603 — fixed argv.
        ["bash", str(script_path)],
        cwd=str(REPO_ROOT),
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=1200,
    )
    duration = time.monotonic() - start

    after = _git_status_short()
    after_paths = {p for _, p in after}

    # Anything newly-dirty, or pre-existing-dirty that the script touched
    # (we can't cheaply distinguish; treat the post-run dirty set as the
    # change-set, minus files that were already dirty pre-run AND do not
    # look like ktlint's output).
    newly_touched = after_paths - before

    formatted_files: list[str] = []
    baseline_regen: list[str] = []
    for p in sorted(newly_touched):
        # Normalise separators for consistent JSON on Windows.
        pn = p.replace("\\", "/")
        if pn.endswith("baseline.xml"):
            baseline_regen.append(pn)
        elif pn.endswith(".kt") or pn.endswith(".kts"):
            formatted_files.append(pn)

    return {
        "formatted_files": formatted_files,
        "baseline_regen": baseline_regen,
        "stdout_tail": _tail(proc.stdout),
        "stderr_tail": _tail(proc.stderr),
        "script_exit_code": proc.returncode,
        "duration_s": round(duration, 2),
    }


# ---------- MCP surface -----------------------------------------------------


@app.list_tools()
async def list_tools() -> list[Tool]:
    return [
        Tool(
            name="test",
            description=(
                "Run Gradle JUnit tests for one module, optionally filtered "
                "to a single test class or method. Returns structured "
                "{passed, failed, skipped, failure_details, duration_s} "
                "parsed from the JUnit XML under the module's build dir. "
                "Always runs with --no-daemon so the agent sees cold-start "
                "timings consistently."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "module": {
                        "type": "string",
                        "description": (
                            "Gradle module path like ':app:cli' or "
                            "':providers:onedrive'. Leading colon optional."
                        ),
                    },
                    "test_class": {
                        "type": "string",
                        "description": "Fully-qualified class name for --tests filter.",
                    },
                    "test_method": {
                        "type": "string",
                        "description": "Method name (appended as class.method to --tests).",
                    },
                },
                "required": ["module"],
                "additionalProperties": False,
            },
        ),
        Tool(
            name="build",
            description=(
                "Run `./gradlew build` across one or more targets. Pass "
                "modules=['*'] (default) to build every composite, or a "
                "list of module paths to build each in its composite. "
                "skip_lint=true excludes the ktlintCheck tasks for a "
                "compile-only inner loop."
            ),
            inputSchema={
                "type": "object",
                "properties": {
                    "modules": {
                        "type": "array",
                        "items": {"type": "string"},
                        "default": ["*"],
                        "description": (
                            "Module paths (e.g. ':app:cli') or ['*'] for all composites."
                        ),
                    },
                    "skip_lint": {
                        "type": "boolean",
                        "default": False,
                    },
                },
                "additionalProperties": False,
            },
        ),
        Tool(
            name="ktlint_sync",
            description=(
                "Run scripts/dev/ktlint-sync.sh (format + baseline regen "
                "across every composite). Returns which .kt files were "
                "auto-formatted and which baselines were regenerated, based "
                "on git-status diff before/after the run."
            ),
            inputSchema={
                "type": "object",
                "properties": {},
                "additionalProperties": False,
            },
        ),
    ]


@app.call_tool()
async def call_tool(name: str, arguments: dict[str, Any]) -> list[TextContent]:
    try:
        if name == "test":
            result = _tool_test(arguments)
        elif name == "build":
            result = _tool_build(arguments)
        elif name == "ktlint_sync":
            result = _tool_ktlint_sync()
        else:
            raise ValueError(f"unknown tool: {name}")
        return [TextContent(type="text", text=json.dumps(result, indent=2))]
    except KeyError as e:
        # resolve() raises KeyError with an unknown-module message — preserve it.
        msg = e.args[0] if e.args else str(e)
        log.warning("lookup error in %s: %s", name, msg)
        return [TextContent(type="text", text=json.dumps({"error": msg}))]
    except subprocess.TimeoutExpired as e:
        log.warning("timeout in %s: %s", name, e)
        return [TextContent(type="text", text=json.dumps({"error": f"timeout: {e}"}))]
    except Exception as e:
        log.exception("unexpected error in %s", name)
        return [TextContent(type="text", text=json.dumps({"error": f"{type(e).__name__}: {e}"}))]


async def main() -> None:
    async with stdio_server() as (read, write):
        await app.run(read, write, app.create_initialization_options())


if __name__ == "__main__":
    # Expose the known-modules list on `--list-modules` for a fast sanity
    # check without importing the MCP stack. Useful from a venv smoke test.
    import sys

    if len(sys.argv) > 1 and sys.argv[1] == "--list-modules":
        for comp in all_composites():
            print(f"[{comp}]")
            for m in modules_in(comp):
                print(f"  {m}")
        sys.exit(0)
    asyncio.run(main())
