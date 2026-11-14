#!/usr/bin/env python3
"""Sahl release policy: independent semantic versions for shipped app changes."""
import argparse
import json
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[1]
CONFIG = json.loads((ROOT / "tools/version-policy.json").read_text())

def version(source, strict=True):
    match = re.search(r"""versionName\s*(?:=\s*)?['"]([^'"]+)['"]""", source)
    if not match:
        raise ValueError("Missing versionName")
    value = match[1]
    pattern = r"(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)" if strict else r"(\d+)\.(\d+)(?:\.(\d+))?"
    found = re.fullmatch(pattern, value) if strict else re.match(pattern, value)
    if not found:
        raise ValueError(f"Expected major.minor.patch versionName, found {value!r}")
    return value, tuple(int(x or 0) for x in found.groups())

def code(source):
    match = re.search(r"(?m)^\s*versionCode\s+(\d+)\s*$", source)
    if not match:
        raise ValueError("Expected a literal positive versionCode")
    value = int(match[1])
    if value <= 0:
        raise ValueError("versionCode must be positive")
    return value

def shipped(path):
    if any(part in path.split("/") for part in ("test", "androidTest", "testFixtures")):
        return False
    if Path(path).name in ("README.md", "AGENTS.md"):
        return False
    return path in CONFIG["files"] or any(path.startswith(p) for p in CONFIG["prefixes"])

def check(paths, old, current):
    errors = []
    try:
        _, now = version(current)
        if CONFIG["manual_code"]:
            current_code = code(current)
        if any(shipped(p) for p in paths):
            _, previous = version(old, strict=False)
            if now <= previous:
                errors.append("Shipped app inputs changed: increase versionName using major.minor.patch.")
            if CONFIG["manual_code"] and current_code <= code(old):
                errors.append("Shipped app inputs changed: increase Android versionCode.")
    except ValueError as error:
        errors.append(str(error))
    return errors

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", help="Compare shipped inputs with this commit/ref.")
    parser.add_argument("--print-version", action="store_true")
    args = parser.parse_args()
    current = (ROOT / "app/build.gradle").read_text()
    name, _ = version(current)
    if args.print_version:
        print(name)
        return
    def git(*parts):
        return subprocess.check_output(["git", *parts], cwd=ROOT, text=True).strip()
    errors = check([], current, current)
    if args.base:
        if set(args.base) == {"0"}:
            # The first push has no previous commit to compare.
            errors = check([], current, current)
        else:
            base = git("rev-parse", "--verify", args.base + "^{commit}")
            paths = git("diff", "--name-only", "--no-renames", base, "HEAD", "--").splitlines()
            old = git("show", base + ":app/build.gradle")
            errors = check(paths, old, current)
    if errors:
        raise SystemExit("\n".join(errors))
    print(f"PASS release version {name}")

if __name__ == "__main__":
    main()
