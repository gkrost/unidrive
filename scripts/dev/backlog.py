#!/usr/bin/env python3
"""backlog.py — mutate docs/backlog/{BACKLOG,CLOSED}.md safely.

Subcommands:
  next-id CATEGORY             # print next free UD-xxx in category range
  show UD-xxx                  # print the frontmatter block + body
  file --id UD-xxx             # append a new ticket to BACKLOG.md
  close UD-xxx                 # move block from BACKLOG.md to CLOSED.md
  audit-commits [--since REF]  # walk commits, warn when scope ≠ touched paths

Category ranges (from docs/AGENT-SYNC.md):
  architecture  001..099
  security      100..199
  core          200..299
  providers     300..399
  shellwin      400..499
  ui            500..599
  protocol      600..699
  tooling       700..799
  tests         800..899
  experimental  900..999

Why this exists: backlog mutations are 4–6 manual tool calls each. This
condenses them to one command. See session handover 2026-04-19 §"What
blocks me and the subagents most".
"""
from __future__ import annotations

import argparse
import re
import sys
from datetime import date
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
BACKLOG = REPO / "docs" / "backlog" / "BACKLOG.md"
CLOSED = REPO / "docs" / "backlog" / "CLOSED.md"

CATEGORY_RANGES = {
    "architecture": (1, 99),
    "security": (100, 199),
    "core": (200, 299),
    "providers": (300, 399),
    "shellwin": (400, 499),
    "shell-win": (400, 499),
    "ui": (500, 599),
    "protocol": (600, 699),
    "tooling": (700, 799),
    "tests": (800, 899),
    "experimental": (900, 999),
}


def all_ids() -> set[str]:
    out: set[str] = set()
    for path in (BACKLOG, CLOSED):
        text = path.read_text(encoding="utf-8")
        for m in re.finditer(r"^id: (UD-\d{3}[a-z]?)", text, re.MULTILINE):
            out.add(m.group(1))
    return out


def next_free_id(category: str) -> str:
    if category not in CATEGORY_RANGES:
        raise SystemExit(
            f"unknown category: {category}. valid: {', '.join(sorted(set(CATEGORY_RANGES)))}"
        )
    lo, hi = CATEGORY_RANGES[category]
    used = {int(x.split("-")[1][:3]) for x in all_ids()}
    for n in range(lo, hi + 1):
        if n not in used:
            return f"UD-{n:03d}"
    raise SystemExit(f"no free IDs left in {category} range ({lo}..{hi})")


def find_block(text: str, uid: str) -> tuple[int, int]:
    """Return (start, end) byte offsets of the block for uid, or raise."""
    pat = re.compile(r"---\nid: " + re.escape(uid) + r"\n")
    m = pat.search(text)
    if not m:
        raise SystemExit(f"{uid} not found in the current file")
    start = m.start()
    # Walk past the closing `---` of the frontmatter, then find next `\n---\n`.
    i = m.end()
    while i < len(text):
        nl = text.find("\n", i)
        if nl == -1:
            raise SystemExit(f"{uid}: no closing frontmatter separator")
        line = text[i:nl]
        if line == "---":
            i = nl + 1
            break
        i = nl + 1
    nxt = text.find("\n---\n", i)
    end = nxt + 1 if nxt != -1 else len(text)
    return start, end


def show(uid: str) -> None:
    for path in (BACKLOG, CLOSED):
        text = path.read_text(encoding="utf-8")
        if re.search(r"^id: " + re.escape(uid) + r"\b", text, re.MULTILINE):
            start, end = find_block(text, uid)
            print(f"# {uid} — found in {path.name}")
            print(text[start:end].rstrip())
            return
    raise SystemExit(f"{uid} not found")


def file_ticket_impl(
    uid: str,
    title: str,
    category: str,
    priority: str,
    effort: str,
    body: str,
    code_refs: list[str] | None = None,
) -> Path:
    """Append a new ticket to BACKLOG.md. Returns the backlog path written.

    Pure business logic, independent of argparse. Raises SystemExit on
    validation failure to stay consistent with the argparse call sites.
    """
    if uid in all_ids():
        raise SystemExit(f"{uid} already exists")
    if priority not in ("low", "medium", "high", "critical"):
        raise SystemExit(f"invalid priority: {priority}")
    if effort not in ("XS", "S", "M", "L", "XL"):
        raise SystemExit(f"invalid effort: {effort}")
    if not body:
        raise SystemExit("need body")
    refs_block = "\n".join(f"  - {r}" for r in (code_refs or []))
    block = (
        "\n---\n"
        f"id: {uid}\n"
        f"title: {title}\n"
        f"category: {category}\n"
        f"priority: {priority}\n"
        f"effort: {effort}\n"
        f"status: open\n"
    )
    if refs_block:
        block += f"code_refs:\n{refs_block}\n"
    block += f"opened: {date.today().isoformat()}\n---\n"
    block += body.rstrip() + "\n"

    current = BACKLOG.read_text(encoding="utf-8")
    BACKLOG.write_text(current.rstrip() + block, encoding="utf-8")
    return BACKLOG


def _parse_code_refs(block: str) -> list[str]:
    """Extract `code_refs:` list entries from a ticket frontmatter block."""
    refs: list[str] = []
    in_refs = False
    for line in block.split("\n"):
        if line.rstrip() == "---" and in_refs:
            break
        if line.startswith("code_refs:"):
            in_refs = True
            continue
        if in_refs:
            stripped = line.lstrip()
            if line and not line.startswith(" ") and not line.startswith("-"):
                in_refs = False
                continue
            if stripped.startswith("- "):
                refs.append(stripped[2:].strip().split(":")[0])
    return refs


def _close_sanity_check(uid: str, block: str, commit: str | None) -> None:
    """R4 from UD-728 — warn (never block) when close parameters look off."""
    import subprocess

    if not commit:
        return
    try:
        subject = subprocess.check_output(
            ["git", "log", "-1", "--pretty=%s", commit],
            encoding="utf-8", stderr=subprocess.DEVNULL,
        ).strip()
    except subprocess.CalledProcessError:
        print(f"  warning: commit {commit} not reachable from current HEAD", file=sys.stderr)
        return
    if uid not in subject:
        print(
            f"  warning: commit {commit} subject does not mention {uid}: {subject!r}",
            file=sys.stderr,
        )
    refs = _parse_code_refs(block)
    if not refs:
        return
    touched = subprocess.check_output(
        ["git", "show", "--name-only", "--pretty=format:", commit],
        encoding="utf-8", stderr=subprocess.DEVNULL,
    ).splitlines()
    touched = [t for t in touched if t]
    overlap = False
    for ref in refs:
        ref_norm = ref.rstrip("/")
        for t in touched:
            if t == ref_norm or t.startswith(ref_norm.rstrip("*") + "/") or \
               (ref_norm.endswith("/") and t.startswith(ref_norm)) or \
               t.startswith(ref_norm):
                overlap = True
                break
        if overlap:
            break
    if not overlap:
        print(
            f"  warning: commit {commit} touched paths do not overlap "
            f"{uid} code_refs ({', '.join(refs)})",
            file=sys.stderr,
        )


def file_ticket(args: argparse.Namespace) -> None:
    body_path = Path(args.body_file) if args.body_file else None
    body = body_path.read_text(encoding="utf-8").rstrip() if body_path else args.body
    if not body:
        raise SystemExit("need --body or --body-file")
    file_ticket_impl(
        uid=args.id,
        title=args.title,
        category=args.category,
        priority=args.priority,
        effort=args.effort,
        body=body,
        code_refs=args.code_refs or [],
    )
    print(f"filed {args.id} in BACKLOG.md")


def close_ticket_impl(uid: str, commit: str | None, note: str) -> str:
    """Move a block from BACKLOG.md → CLOSED.md. Returns the resolved_by string."""
    if not note:
        raise SystemExit("need note")
    resolved_by = f"commit {commit}. {note}" if commit else note

    backlog_text = BACKLOG.read_text(encoding="utf-8")
    start, end = find_block(backlog_text, uid)
    block = backlog_text[start:end]
    _close_sanity_check(uid, block, commit)
    backlog_text = backlog_text[:start] + backlog_text[end:]
    BACKLOG.write_text(backlog_text, encoding="utf-8")

    # Transform status: open → status: closed + closed:/resolved_by:
    today = date.today().isoformat()
    out_lines: list[str] = []
    injected = False
    for line in block.split("\n"):
        if line.startswith("status: ") and not injected:
            out_lines.append("status: closed")
            out_lines.append(f"closed: {today}")
            out_lines.append(f"resolved_by: {resolved_by}")
            injected = True
        elif line.startswith("status: "):
            continue  # drop any stray status line below
        else:
            out_lines.append(line)
    if not injected:
        raise SystemExit(f"{uid}: removed block had no status: line")

    closed = CLOSED.read_text(encoding="utf-8")
    if not closed.endswith("\n"):
        closed += "\n"
    closed = closed.rstrip() + "\n\n" + "\n".join(out_lines).rstrip() + "\n"
    CLOSED.write_text(closed, encoding="utf-8")
    return resolved_by


def close_ticket(args: argparse.Namespace) -> None:
    note_path = Path(args.note_file) if args.note_file else None
    note_text = note_path.read_text(encoding="utf-8").rstrip() if note_path else args.note
    if not note_text:
        raise SystemExit("need --note or --note-file")
    resolved_by = close_ticket_impl(args.id, args.commit, note_text)
    print(f"closed {args.id} → CLOSED.md (resolved_by = {resolved_by[:60]}…)")


def main() -> None:
    # The repo holds UTF-8 everywhere (→, ≥, ×, …). On Windows cp1252 consoles
    # the default stdout encoding raises UnicodeEncodeError — force utf-8.
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except (AttributeError, Exception):
        pass
    p = argparse.ArgumentParser(description=__doc__)
    sub = p.add_subparsers(dest="cmd", required=True)

    p_next = sub.add_parser("next-id", help="print next free UD-xxx in category")
    p_next.add_argument("category", help="e.g. core, providers, tooling")

    p_show = sub.add_parser("show", help="print block for an id")
    p_show.add_argument("id")

    p_file = sub.add_parser("file", help="append a new ticket to BACKLOG.md")
    p_file.add_argument("--id", required=True)
    p_file.add_argument("--title", required=True)
    p_file.add_argument("--category", required=True)
    p_file.add_argument("--priority", required=True, choices=["low", "medium", "high", "critical"])
    p_file.add_argument("--effort", required=True, choices=["XS", "S", "M", "L", "XL"])
    p_file.add_argument("--code-refs", nargs="*", default=[])
    p_file.add_argument("--body", help="inline prose body")
    p_file.add_argument("--body-file", help="path to a markdown file with the body")

    p_close = sub.add_parser("close", help="move block from BACKLOG.md to CLOSED.md")
    p_close.add_argument("id")
    p_close.add_argument("--commit", help="short sha (7 chars) that resolved this")
    p_close.add_argument("--note", help="one-paragraph resolved_by note (inline)")
    p_close.add_argument("--note-file", help="path to a markdown file with the note")

    p_audit = sub.add_parser(
        "audit-commits",
        help="walk commits and warn when commit scope diverges from touched paths",
    )
    p_audit.add_argument("--since", default="main",
                         help="git revision to walk from, default: main")

    args = p.parse_args()
    if args.cmd == "next-id":
        print(next_free_id(args.category))
    elif args.cmd == "show":
        show(args.id)
    elif args.cmd == "file":
        file_ticket(args)
    elif args.cmd == "close":
        close_ticket(args)
    elif args.cmd == "audit-commits":
        audit_commits(args.since)


_CONV_COMMIT = re.compile(r"^([a-z]+)\(([^)]+)\):\s")


def audit_commits(since: str) -> None:
    """R2 from UD-728 — walk commits and flag scope vs touched-paths mismatches.

    Warns (never blocks) so the output can be piped into a session report
    without failing CI. Exit code is 0 on any commit walk that completed.
    """
    import subprocess

    try:
        revs = subprocess.check_output(
            ["git", "log", f"{since}..HEAD", "--pretty=%H"],
            encoding="utf-8", stderr=subprocess.PIPE,
        ).split()
    except subprocess.CalledProcessError as e:
        raise SystemExit(f"git log failed: {e.stderr.strip()}")
    if not revs:
        print(f"no commits between {since} and HEAD")
        return

    findings = 0
    for sha in revs:
        subject = subprocess.check_output(
            ["git", "log", "-1", "--pretty=%s", sha], encoding="utf-8",
        ).strip()
        m = _CONV_COMMIT.match(subject)
        if not m:
            continue
        ctype, cscope = m.group(1), m.group(2)
        touched = [
            t for t in subprocess.check_output(
                ["git", "show", "--name-only", "--pretty=format:", sha],
                encoding="utf-8",
            ).splitlines() if t
        ]
        mismatch = _audit_one(ctype, cscope, touched)
        if mismatch:
            print(f"{sha[:7]} {subject}")
            for line in mismatch:
                print(f"   · {line}")
            findings += 1

    print()
    print(f"audit-commits: {len(revs)} commits walked, {findings} with findings")


def _audit_one(ctype: str, scope: str, touched: list[str]) -> list[str]:
    scoped = f"{ctype}({scope})"
    issues: list[str] = []

    def outside(allow_prefixes: list[str]) -> list[str]:
        bad = []
        for t in touched:
            if not any(t.startswith(p) or _glob_match(t, p) for p in allow_prefixes):
                bad.append(t)
        return bad

    if scoped == "chore(ktlint)":
        bad = outside(["core/app/*/config/ktlint/baseline.xml",
                       "core/providers/*/config/ktlint/baseline.xml"])
        if bad:
            issues.append(f"chore(ktlint) touched non-baseline paths: {bad}")
    elif scoped == "docs(backlog)":
        bad = outside(["docs/backlog/"])
        if bad:
            issues.append(f"docs(backlog) touched non-backlog paths: {bad}")
    elif scoped == "docs(handover)":
        bad = outside(["handover.md"])
        if bad:
            issues.append(f"docs(handover) touched non-handover paths: {bad}")
    return issues


def _glob_match(path: str, pattern: str) -> bool:
    import fnmatch
    return fnmatch.fnmatch(path, pattern)


if __name__ == "__main__":
    main()
