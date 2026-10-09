#!/usr/bin/env python3
"""Check local documentation links and the shared agent-workflow wiring."""

from pathlib import Path
import re
import sys
from urllib.parse import unquote, urlsplit


ROOT = Path(__file__).resolve().parents[1]
WORKFLOWS = (
    "plan",
    "run",
    "verify",
    "review",
    "debug",
    "ui-review",
    "release-review",
)
LINK = re.compile(r"(?<!!)\[[^\]]+\]\(([^\s)]+)(?:\s+[^)]*)?\)")
ERRORS: list[str] = []


def check_links(path: Path) -> None:
    text = path.read_text(encoding="utf-8")
    in_fence = False
    for number, line in enumerate(text.splitlines(), 1):
        if line.lstrip().startswith(("```", "~~~")):
            in_fence = not in_fence
            continue
        if in_fence:
            continue
        for match in LINK.finditer(line):
            target = match.group(1).strip("<>")
            parsed = urlsplit(target)
            # Existing research notes cite files in the separate TellUmo checkout.
            # CI can verify only paths inside this repository.
            if parsed.scheme or parsed.netloc or target.startswith(("#", "/")):
                continue
            local = unquote(parsed.path)
            if not local:
                continue
            resolved = path.parent / local
            if not resolved.exists():
                ERRORS.append(f"{path.relative_to(ROOT)}:{number}: missing link target {target}")


def check_workflows() -> None:
    claude = (ROOT / "CLAUDE.md").read_text(encoding="utf-8")
    if claude.count("@AGENTS.md") != 1:
        ERRORS.append("CLAUDE.md must import AGENTS.md exactly once")
    expected = {f"adminifender-{suffix}" for suffix in WORKFLOWS}
    for base in (ROOT / ".agents/skills", ROOT / ".claude/skills"):
        actual = {path.name for path in base.iterdir() if path.is_dir() and path.name.startswith("adminifender-")}
        if actual != expected:
            ERRORS.append(f"{base.relative_to(ROOT)}: expected {sorted(expected)}, found {sorted(actual)}")
    for name in sorted(expected):
        canonical = ROOT / ".agents/skills" / name / "SKILL.md"
        policy = ROOT / ".agents/skills" / name / "agents/openai.yaml"
        adapter = ROOT / ".claude/skills" / name / "SKILL.md"
        for path in (canonical, policy, adapter):
            if not path.is_file():
                ERRORS.append(f"missing {path.relative_to(ROOT)}")
        if not all(path.is_file() for path in (canonical, policy, adapter)):
            continue
        if f"name: {name}" not in canonical.read_text(encoding="utf-8"):
            ERRORS.append(f"{canonical.relative_to(ROOT)}: wrong skill name")
        if "allow_implicit_invocation: false" not in policy.read_text(encoding="utf-8"):
            ERRORS.append(f"{policy.relative_to(ROOT)}: must be manual-only")
        body = adapter.read_text(encoding="utf-8")
        if f"name: {name}" not in body or "disable-model-invocation: true" not in body:
            ERRORS.append(f"{adapter.relative_to(ROOT)}: wrong name or invocation policy")
        if f".agents/skills/{name}/SKILL.md" not in body:
            ERRORS.append(f"{adapter.relative_to(ROOT)}: wrong canonical path")


def main() -> int:
    paths = [ROOT / "README.md", ROOT / "AGENTS.md", ROOT / "CLAUDE.md"]
    paths.extend((ROOT / "docs").rglob("*.md"))
    for folder in ("ops", "models", "android", "backend", "contracts"):
        paths.extend(p for p in (ROOT / folder).glob("*.md"))
    for suffix in WORKFLOWS:
        name = f"adminifender-{suffix}"
        paths.append(ROOT / ".agents/skills" / name / "SKILL.md")
        paths.append(ROOT / ".claude/skills" / name / "SKILL.md")
    for path in paths:
        check_links(path)
    check_workflows()
    if ERRORS:
        print("\n".join(ERRORS), file=sys.stderr)
        return 1
    print(f"Checked {len(paths)} Markdown files and {len(WORKFLOWS)} workflow pairs.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
