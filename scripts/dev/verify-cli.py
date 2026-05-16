#!/usr/bin/env python3
# verify-cli.py — UD-776
#
# Drives the deployed `unidrive` CLI against an ephemeral sandbox,
# compares actual exit-code / stdout / stderr per case against expected,
# and emits a markdown matrix.
#
# Usage:
#   python scripts/dev/verify-cli.py [--jar PATH] [--keep-sandbox]
#                                    [--filter REGEX] [--out PATH]
#                                    [--workers N]
#
# Run after `./gradlew :app:cli:deploy` so the jar is present.

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field, asdict
from pathlib import Path
from typing import Optional, Callable


# ─── Case + Result ────────────────────────────────────────────────────────────


@dataclass
class Case:
    label: str
    argv: list[str]
    # `None` on any expectation means "don't check".
    expected_exit: Optional[int] = None
    stdout_match: Optional[str] = None        # regex must be found
    stderr_match: Optional[str] = None        # regex must be found
    stdout_not_match: Optional[str] = None    # regex must NOT be found
    stderr_not_match: Optional[str] = None
    skip_reason: Optional[str] = None
    env_extra: dict[str, str] = field(default_factory=dict)
    # Lock-acquiring commands (sync, refresh, apply, auth, vault writes)
    # must run sequentially or they'll collide on the per-profile lockfile.
    serial: bool = False


@dataclass
class Result:
    case: Case
    exit_code: int
    stdout: str
    stderr: str
    elapsed_ms: int
    status: str           # "PASS", "FAIL", "SKIP"
    reason: str           # FAIL: which check failed; SKIP: skip_reason


# ─── Jar discovery ────────────────────────────────────────────────────────────


def find_jar(explicit: Optional[str]) -> Path:
    if explicit:
        p = Path(explicit)
        if not p.is_file():
            sys.exit(f"error: --jar {explicit} not found")
        return p
    candidates: list[Path] = []
    home = Path.home()
    localappdata = os.environ.get("LOCALAPPDATA")
    if localappdata:
        candidates.append(Path(localappdata) / "unidrive")
    candidates.append(home / ".local" / "lib" / "unidrive")
    for d in candidates:
        if not d.is_dir():
            continue
        for jar in sorted(d.glob("unidrive-*.jar")):
            # Skip the MCP shadow jar — different artifact, same dir on Windows.
            if "mcp" in jar.name:
                continue
            return jar
    sys.exit(
        "error: cannot locate unidrive-<v>.jar; run "
        "`./gradlew :app:cli:deploy` first or pass --jar"
    )


# ─── Sandbox ──────────────────────────────────────────────────────────────────


def build_sandbox(root: Path, suffix: str) -> dict[str, str]:
    """Build an isolated config dir + sync roots. Returns profile-name map.
    Profile names are stable (no random suffix) so the committed matrix
    snapshot stays diff-stable across runs; sandbox isolation comes from the
    randomized root dir."""
    config_dir = root / "config"
    config_dir.mkdir(parents=True, exist_ok=True)
    p_a = "vp_a"
    p_b = "vp_b"
    p_bogus = "vp_bogus"
    sa = root / "sync-a"
    sb = root / "sync-b"
    sa.mkdir(parents=True, exist_ok=True)
    sb.mkdir(parents=True, exist_ok=True)
    bogus_sync = root / "this-path-is-fine-actually"
    # The "bogus" profile has a valid type but a sync_root we never create
    # — useful for rows that need a profile that resolves but won't sync.
    toml = (
        "[general]\n"
        f'default_profile = "{p_a}"\n'
        "\n"
        f"[providers.{p_a}]\n"
        'type = "localfs"\n'
        f'sync_root = "{sa.as_posix()}"\n'
        "\n"
        f"[providers.{p_b}]\n"
        'type = "localfs"\n'
        f'sync_root = "{sb.as_posix()}"\n'
        "\n"
        f"[providers.{p_bogus}]\n"
        'type = "localfs"\n'
        f'sync_root = "{bogus_sync.as_posix()}"\n'
    )
    (config_dir / "config.toml").write_text(toml, encoding="utf-8")
    return {
        "config_dir": str(config_dir),
        "a": p_a,
        "b": p_b,
        "bogus": p_bogus,
        "sync_a": str(sa),
        "sync_b": str(sb),
    }


# ─── Case runner ──────────────────────────────────────────────────────────────


def run_case(case: Case, java: str, jar: Path, config_dir: str, sandbox_home: str) -> Result:
    if case.skip_reason:
        return Result(case, -1, "", "", 0, "SKIP", case.skip_reason)
    env = os.environ.copy()
    env["UNIDRIVE_CONFIG_DIR"] = config_dir
    env["HOME"] = sandbox_home               # POSIX home-dir fallback
    env["USERPROFILE"] = sandbox_home        # Windows home-dir fallback
    env.update(case.env_extra)
    full_argv = [java, "-jar", str(jar)] + case.argv
    t0 = time.monotonic()
    try:
        cp = subprocess.run(
            full_argv,
            env=env,
            capture_output=True,
            text=True,
            timeout=30,
            stdin=subprocess.DEVNULL,
        )
    except subprocess.TimeoutExpired as e:
        elapsed = int((time.monotonic() - t0) * 1000)
        return Result(case, -1, e.stdout or "", e.stderr or "", elapsed, "FAIL", "timeout 30s")
    elapsed = int((time.monotonic() - t0) * 1000)
    reasons: list[str] = []
    if case.expected_exit is not None and cp.returncode != case.expected_exit:
        reasons.append(f"exit={cp.returncode} want={case.expected_exit}")
    if case.stdout_match and not re.search(case.stdout_match, cp.stdout, re.MULTILINE):
        reasons.append(f"stdout missing /{case.stdout_match}/")
    if case.stderr_match and not re.search(case.stderr_match, cp.stderr, re.MULTILINE):
        reasons.append(f"stderr missing /{case.stderr_match}/")
    if case.stdout_not_match and re.search(case.stdout_not_match, cp.stdout, re.MULTILINE):
        reasons.append(f"stdout contains forbidden /{case.stdout_not_match}/")
    if case.stderr_not_match and re.search(case.stderr_not_match, cp.stderr, re.MULTILINE):
        reasons.append(f"stderr contains forbidden /{case.stderr_not_match}/")
    status = "PASS" if not reasons else "FAIL"
    return Result(case, cp.returncode, cp.stdout, cp.stderr, elapsed, status, "; ".join(reasons))


# ─── Matrix ───────────────────────────────────────────────────────────────────


def build_matrix(p: dict[str, str]) -> list[Case]:
    pa, pb, pbogus = p["a"], p["b"], p["bogus"]
    cases: list[Case] = []

    # ─── Cross-cutting ────────────────────────────────────────────────────
    cases += [
        Case("root-no-args", [], expected_exit=0, stdout_match=r"Usage:"),
        Case("root-help-long", ["--help"], expected_exit=0, stdout_match=r"Usage:"),
        Case("root-help-short", ["-h"], expected_exit=0, stdout_match=r"Usage:"),
        Case("root-version", ["--version"], expected_exit=0, stdout_match=r"\d+\.\d+\.\d+"),
        Case("root-unknown-long", ["--frobnicate"], expected_exit=2, stderr_match=r"(?i)unknown|invalid"),
        Case("root-unknown-short", ["-Z"], expected_exit=2, stderr_match=r"(?i)unknown|invalid"),
        # Constant path to keep the matrix diff-stable; won't exist on any host.
        Case("root-bogus-config-dir",
             ["-c", "/no/such/dir/verify-cli-bogus-xyzzy", "status"],
             expected_exit=1),
    ]

    # ─── T1 help for every leaf command (23 cmds + groups) ────────────────
    LEAF_CMDS = [
        "auth", "benchmark", "logout", "sync", "refresh", "apply", "sweep",
        "status", "get", "ls", "free", "pin", "unpin", "quota", "log",
        "relocate", "share",
    ]
    for c in LEAF_CMDS:
        cases.append(Case(f"{c}-help", [c, "--help"], expected_exit=0, stdout_match=r"Usage:"))
        cases.append(Case(f"{c}-h", [c, "-h"], expected_exit=0, stdout_match=r"Usage:"))

    # Group commands
    GROUPS = {
        "profile": ["add", "list", "remove"],
        "backup": ["add", "list"],
        "conflicts": ["list", "restore", "clear"],
        "trash": ["list", "restore", "purge"],
        "versions": ["list", "restore", "purge"],
        "vault": ["init", "encrypt", "decrypt", "change-passphrase", "xtra-init", "xtra-status"],
    }
    for group, subs in GROUPS.items():
        cases.append(Case(f"{group}-help", [group, "--help"], expected_exit=0, stdout_match=r"Usage:"))
        for sub in subs:
            cases.append(Case(f"{group}-{sub}-help", [group, sub, "--help"],
                              expected_exit=0, stdout_match=r"Usage:"))

    # ─── T3 happy path on safe inspect commands ──────────────────────────
    cases += [
        Case("status-default-profile", ["status"], expected_exit=0),
        Case("status-pa", ["-p", pa, "status"], expected_exit=0),
        Case("profile-list", ["profile", "list"], expected_exit=0,
             stdout_match=re.escape(pa)),
        Case("backup-list", ["backup", "list"], expected_exit=0),
        Case("log-empty", ["log"], expected_exit=0),
        Case("conflicts-list", ["-p", pa, "conflicts", "list"], expected_exit=0),
        Case("trash-list", ["-p", pa, "trash", "list"], expected_exit=0),
        Case("versions-list", ["-p", pa, "versions", "list"], expected_exit=0),
        Case("vault-xtra-status", ["vault", "xtra-status"], expected_exit=0),
        # ls + quota touch the provider; for localfs they should be cheap.
        Case("ls-pa-root", ["-p", pa, "ls", "/"], expected_exit=0),
        Case("quota-pa", ["-p", pa, "quota"], expected_exit=0),
    ]

    # ─── T4 unknown profile ───────────────────────────────────────────────
    UNKNOWN = "vp-bogus-xyz-not-configured"
    for cmd in ["status", "quota", "ls", "log", "conflicts list"]:
        argv = ["-p", UNKNOWN] + cmd.split()
        cases.append(Case(
            f"{cmd.replace(' ', '-')}-unknown-profile",
            argv,
            expected_exit=1,
            stderr_match=r"(?i)unknown|not found|no.*profile|invalid",
        ))

    # ─── T5/T6 unknown sub-flag ───────────────────────────────────────────
    cases += [
        Case("auth-unknown-flag", ["auth", "--frobnicate"], expected_exit=2,
             stderr_match=r"(?i)unknown|invalid"),
        Case("sync-unknown-flag", ["sync", "--frobnicate"], expected_exit=2,
             stderr_match=r"(?i)unknown|invalid"),
        Case("status-unknown-short", ["status", "-Q"], expected_exit=2),
    ]

    # ─── T7 required-flag missing on subcommands that have required=true ──
    cases += [
        Case("backup-add-no-required", ["backup", "add"], expected_exit=2,
             stderr_match=r"--name|--provider"),
        Case("relocate-no-required", ["relocate"], expected_exit=2,
             stderr_match=r"--from|--to"),
        Case("share-no-path", ["-p", pa, "share"], expected_exit=2),
        Case("get-no-path", ["-p", pa, "get"], expected_exit=2),
        Case("free-no-path", ["-p", pa, "free"], expected_exit=2),
        Case("pin-no-pattern", ["-p", pa, "pin"], expected_exit=2),
        Case("unpin-no-pattern", ["-p", pa, "unpin"], expected_exit=2),
        Case("profile-remove-no-name", ["profile", "remove"], expected_exit=2),
        Case("conflicts-restore-no-path", ["-p", pa, "conflicts", "restore"], expected_exit=2),
        Case("trash-restore-no-path", ["-p", pa, "trash", "restore"], expected_exit=2),
        Case("versions-restore-no-args", ["-p", pa, "versions", "restore"], expected_exit=2),
    ]

    # ─── T8 flag-form variants ────────────────────────────────────────────
    cases += [
        Case("sync-dry-run-space", ["-p", pa, "sync", "--dry-run"], expected_exit=0, serial=True),
        Case("conflicts-list-limit-space",
             ["-p", pa, "conflicts", "list", "-n", "5"], expected_exit=0),
        Case("conflicts-list-limit-equals",
             ["-p", pa, "conflicts", "list", "-n=5"], expected_exit=0),
        Case("conflicts-list-limit-long-equals",
             ["-p", pa, "conflicts", "list", "--limit=5"], expected_exit=0),
    ]

    # ─── T9 -v INHERIT-scope at various positions (UD-271) ────────────────
    cases += [
        Case("v-at-parent", ["-v", "status"], expected_exit=0),
        Case("v-at-child", ["status", "-v"], expected_exit=0),
        Case("verbose-at-parent", ["--verbose", "status"], expected_exit=0),
        Case("v-deep-in-group", ["profile", "list", "-v"], expected_exit=0),
    ]

    # ─── T10 --help overrides bad args ────────────────────────────────────
    cases += [
        Case("help-overrides-bad-flag", ["status", "--help", "--frobnicate"],
             expected_exit=0, stdout_match=r"Usage:"),
        Case("help-overrides-missing-required",
             ["relocate", "--help"], expected_exit=0, stdout_match=r"Usage:"),
    ]

    # ─── Regression: fabricated commands MUST error (PR #39 class) ────────
    # These rows guard against future docs claiming the same non-existent
    # surface. If picocli ever grows one of these, the regression row will
    # start failing — drift caught at matrix-run time.
    cases += [
        Case("regr-no-identity-subcommand",
             ["identity"], expected_exit=2,
             stderr_match=r"(?i)unmatched|unknown|not.*command"),
        Case("regr-no-auth-begin-subcommand",
             ["auth", "begin"], expected_exit=2,
             stderr_match=r"(?i)unmatched|positional|unknown"),
        Case("regr-no-auth-complete-subcommand",
             ["auth", "complete"], expected_exit=2,
             stderr_match=r"(?i)unmatched|positional|unknown"),
        Case("regr-profile-add-rejects-name-flag",
             ["profile", "add", "--name", "foo", "--type", "localfs", "--sync-path", "/tmp"],
             expected_exit=2,
             stderr_match=r"(?i)unknown.*--name|unmatched"),
        Case("regr-no-identity-with-profile",
             ["-p", pa, "identity"], expected_exit=2,
             stderr_match=r"(?i)unmatched|unknown"),
    ]

    # ─── T2 no-args on every leaf command (behavior pinning) ──────────────
    # These commands without args may legitimately succeed (with defaults)
    # or error (required params missing). The matrix pins whichever it is.
    cases += [
        Case("auth-no-args", ["auth"], expected_exit=0, serial=True),
        Case("sync-no-args", ["sync"], expected_exit=0, serial=True),
        Case("refresh-no-args", ["refresh"], expected_exit=0, serial=True),
        Case("apply-no-args", ["apply"], expected_exit=0, serial=True),
        Case("status-no-args", ["status"], expected_exit=0),
        Case("ls-no-args", ["ls"], expected_exit=0),
        Case("quota-no-args", ["quota"], expected_exit=0),
        Case("log-no-args", ["log"], expected_exit=0),
        Case("profile-no-args", ["profile"], expected_exit=0,
             stdout_match=r"Usage|profile"),
        Case("backup-no-args", ["backup"], expected_exit=0,
             stdout_match=r"Usage|backup"),
        Case("conflicts-no-args", ["conflicts"], expected_exit=0,
             stdout_match=r"Usage|conflict"),
        Case("trash-no-args", ["trash"], expected_exit=0,
             stdout_match=r"Usage|trash"),
        Case("versions-no-args", ["versions"], expected_exit=0,
             stdout_match=r"Usage|version"),
        Case("vault-no-args", ["vault"], expected_exit=0,
             stdout_match=r"Usage|vault"),
        # sweep requires --null-bytes; without it should error
        Case("sweep-no-args", ["sweep"], expected_exit=2,
             stderr_match=r"(?i)required|--null-bytes"),
    ]

    # ─── Cloud-only rows: SKIP with explicit reason ───────────────────────
    cases += [
        Case("auth-device-code-onedrive",
             ["auth", "--device-code"],
             skip_reason="requires live OneDrive account + interactive device-code consent"),
        Case("sync-watch-mode",
             ["-p", pa, "sync", "--watch"],
             skip_reason="long-running daemon mode; not safe to exercise from a unit-style matrix"),
        Case("benchmark-real-run",
             ["benchmark", "--iterations", "1"],
             skip_reason="creates+deletes real files at provider; needs explicit operator approval"),
        Case("relocate-real",
             ["relocate", "--from", pa, "--to", pb],
             skip_reason="moves data between profiles; only meaningful with non-empty source"),
        Case("share-onedrive",
             ["-p", pa, "share", "/foo"],
             skip_reason="localfs share emits a file:// URI but real share verbs need cloud providers"),
    ]

    return cases


# ─── Markdown emit ────────────────────────────────────────────────────────────


def emit_markdown(results: list[Result], out: Path, jar: Path) -> tuple[int, int, int]:
    pass_n = sum(1 for r in results if r.status == "PASS")
    fail_n = sum(1 for r in results if r.status == "FAIL")
    skip_n = sum(1 for r in results if r.status == "SKIP")
    total = len(results)
    lines: list[str] = []
    lines.append("# CLI verification matrix")
    lines.append("")
    lines.append(
        "Generated by [`scripts/dev/verify-cli.py`](../../scripts/dev/verify-cli.py) "
        f"(UD-776). Source jar: `{jar.name}`."
    )
    lines.append("")
    lines.append(f"**{pass_n} PASS · {fail_n} FAIL · {skip_n} SKIP** (total {total})")
    lines.append("")
    lines.append(
        "Each row drives one `unidrive ...` invocation against a hermetic sandbox "
        "(`UNIDRIVE_CONFIG_DIR` redirected to a tmp dir with 3 `localfs` profiles). "
        "FAIL rows are documentation/behavior drift to investigate; SKIP rows need "
        "cloud auth or long-running daemons and aren't safe in a unit-style matrix."
    )
    lines.append("")
    lines.append("| # | Label | Command | Expected | Actual | Status |")
    lines.append("|---|-------|---------|----------|--------|:------:|")
    for i, r in enumerate(results, start=1):
        argv = " ".join(r.case.argv) or "_(no args)_"
        # Escape pipes in argv display
        argv_disp = "`unidrive " + argv.replace("|", r"\|") + "`"
        exp_parts: list[str] = []
        if r.case.expected_exit is not None:
            exp_parts.append(f"exit={r.case.expected_exit}")
        if r.case.stdout_match:
            exp_parts.append(f"stdout~`/{r.case.stdout_match}/`")
        if r.case.stderr_match:
            exp_parts.append(f"stderr~`/{r.case.stderr_match}/`")
        if r.case.skip_reason:
            exp_parts.append("_skip_")
        expected = " · ".join(exp_parts) or "—"
        # Escape pipes in expected
        expected = expected.replace("|", r"\|")
        # Per-row timing intentionally omitted to keep the committed snapshot
        # diff-stable across runs. Timings live in the JSON sidecar.
        if r.status == "SKIP":
            actual = f"_(skip: {r.reason})_"
            badge = "⊘"
        elif r.status == "PASS":
            actual = f"exit={r.exit_code}"
            badge = "✅"
        else:
            actual = f"exit={r.exit_code} · {r.reason}".replace("|", r"\|")
            badge = "❌"
        lines.append(f"| {i:03d} | {r.case.label} | {argv_disp} | {expected} | {actual} | {badge} |")
    lines.append("")
    if fail_n > 0:
        lines.append("## FAIL details")
        lines.append("")
        for r in results:
            if r.status != "FAIL":
                continue
            lines.append(f"### {r.case.label}")
            lines.append("")
            lines.append(f"- **Command:** `unidrive {' '.join(r.case.argv)}`")
            lines.append(f"- **Expected:** exit={r.case.expected_exit} stdout~`/{r.case.stdout_match}/` stderr~`/{r.case.stderr_match}/`")
            lines.append(f"- **Actual:** exit={r.exit_code}, reason: {r.reason}")
            if r.stdout.strip():
                lines.append("- **stdout (first 30 lines):**")
                lines.append("  ```")
                for line in r.stdout.splitlines()[:30]:
                    lines.append(f"  {line}")
                lines.append("  ```")
            if r.stderr.strip():
                lines.append("- **stderr (first 30 lines):**")
                lines.append("  ```")
                for line in r.stderr.splitlines()[:30]:
                    lines.append(f"  {line}")
                lines.append("  ```")
            lines.append("")
    out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return pass_n, fail_n, skip_n


# ─── Main ─────────────────────────────────────────────────────────────────────


def main() -> int:
    ap = argparse.ArgumentParser(description="CLI verification matrix runner (UD-776).")
    ap.add_argument("--jar", help="Path to unidrive-<v>.jar (default: auto-discover).")
    ap.add_argument(
        "--out",
        default="docs/dev/cli-verification-matrix.md",
        help="Output markdown path (default: docs/dev/cli-verification-matrix.md).",
    )
    ap.add_argument("--filter", help="Regex to filter case labels (substring match).")
    ap.add_argument(
        "--keep-sandbox",
        action="store_true",
        help="Don't delete the sandbox dir after the run (for triage).",
    )
    ap.add_argument(
        "--workers",
        type=int,
        default=4,
        help="Parallelism for case execution (default: 4).",
    )
    ap.add_argument(
        "--java",
        default=shutil.which("java") or "java",
        help="Path to java (default: PATH lookup).",
    )
    args = ap.parse_args()

    jar = find_jar(args.jar)
    print(f"[verify-cli] jar:    {jar}", file=sys.stderr)
    print(f"[verify-cli] java:   {args.java}", file=sys.stderr)

    suffix = uuid.uuid4().hex[:8]
    sandbox_root = Path(tempfile.mkdtemp(prefix=f"verify-cli-{suffix}-"))
    print(f"[verify-cli] sandbox: {sandbox_root}", file=sys.stderr)
    try:
        ctx = build_sandbox(sandbox_root, suffix)
        cases = build_matrix(ctx)
        if args.filter:
            pat = re.compile(args.filter)
            cases = [c for c in cases if pat.search(c.label)]
            print(f"[verify-cli] filter '{args.filter}' selected {len(cases)} cases",
                  file=sys.stderr)
        print(f"[verify-cli] running {len(cases)} cases with {args.workers} workers …",
              file=sys.stderr)

        parallel_cases = [c for c in cases if not c.serial]
        serial_cases = [c for c in cases if c.serial]
        print(f"[verify-cli] {len(parallel_cases)} parallel + {len(serial_cases)} serial cases",
              file=sys.stderr)

        # Map label → Result so we can preserve original order when emitting.
        label_to_result: dict[str, Result] = {}
        t_total = time.monotonic()
        with ThreadPoolExecutor(max_workers=args.workers) as pool:
            futures = {
                pool.submit(run_case, c, args.java, jar, ctx["config_dir"], str(sandbox_root)): c
                for c in parallel_cases
            }
            for fut in futures:
                r = fut.result()
                label_to_result[r.case.label] = r
                badge = {"PASS": "✓", "FAIL": "✗", "SKIP": "⊘"}[r.status]
                print(f"  {badge} {r.case.label}", file=sys.stderr)
        for c in serial_cases:
            r = run_case(c, args.java, jar, ctx["config_dir"], str(sandbox_root))
            label_to_result[r.case.label] = r
            badge = {"PASS": "✓", "FAIL": "✗", "SKIP": "⊘"}[r.status]
            print(f"  {badge} {r.case.label} (serial)", file=sys.stderr)
        # Preserve original case order in the matrix output.
        results = [label_to_result[c.label] for c in cases]
        elapsed = time.monotonic() - t_total

        out = Path(args.out)
        out.parent.mkdir(parents=True, exist_ok=True)
        pass_n, fail_n, skip_n = emit_markdown(results, out, jar)
        json_out = out.with_suffix(".json")
        json_out.write_text(
            json.dumps(
                {
                    "generated_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
                    "jar": str(jar),
                    "pass": pass_n,
                    "fail": fail_n,
                    "skip": skip_n,
                    "total": len(results),
                    "elapsed_seconds": round(elapsed, 1),
                    "cases": [
                        {
                            "label": r.case.label,
                            "argv": r.case.argv,
                            "status": r.status,
                            "exit_code": r.exit_code,
                            "reason": r.reason,
                            "elapsed_ms": r.elapsed_ms,
                        }
                        for r in results
                    ],
                },
                indent=2,
            ),
            encoding="utf-8",
        )

        print(
            f"\n[verify-cli] {pass_n} PASS · {fail_n} FAIL · {skip_n} SKIP "
            f"(total {len(results)}, {elapsed:.1f}s wall)",
            file=sys.stderr,
        )
        print(f"[verify-cli] matrix → {out}", file=sys.stderr)
        print(f"[verify-cli] json   → {json_out}", file=sys.stderr)
        return 0 if fail_n == 0 else 1
    finally:
        if args.keep_sandbox:
            print(f"[verify-cli] sandbox kept: {sandbox_root}", file=sys.stderr)
        else:
            shutil.rmtree(sandbox_root, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
