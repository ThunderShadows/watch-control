#!/usr/bin/env python3
"""Print the CHANGELOG section whose heading matches a version.

Headings look like `## 0.5.0` or `## 0.4`. The match is the heading line
and everything after it until the next `## ` heading.
"""

from __future__ import annotations

import pathlib
import sys


def extract(changelog: str, version: str) -> str:
    version = version.removeprefix("v").strip()
    lines = changelog.splitlines()
    start = None
    for index, line in enumerate(lines):
        heading = line.strip()
        if heading == f"## {version}" or heading.startswith(f"## {version} "):
            start = index
            break
    if start is None:
        raise SystemExit(f"No changelog section for {version}")
    end = len(lines)
    for index in range(start + 1, len(lines)):
        if lines[index].startswith("## "):
            end = index
            break
    body = "\n".join(lines[start:end]).strip()
    if not body:
        raise SystemExit(f"Changelog section for {version} is empty")
    return body + "\n"


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: extract_changelog.py VERSION")
    path = pathlib.Path("CHANGELOG.md")
    if not path.is_file():
        raise SystemExit("CHANGELOG.md not found")
    sys.stdout.write(extract(path.read_text(encoding="utf-8"), sys.argv[1]))


if __name__ == "__main__":
    main()
