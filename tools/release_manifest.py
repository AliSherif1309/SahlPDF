"""Write out/version.json for the in-app updater from the built APKs' real manifests.

Format (also read by Sahl PDF 1.x so those installs can update in place):
{"code": <universal code>, "sahlpdf": "<versionName>", "notes": "...", "apks": {"<file>": {"code": N}}}
"""
import argparse
import json
import re
import subprocess
from pathlib import Path

PACKAGE = "com.aly.sahlpdf"


def badging(aapt: str, apk: Path) -> tuple[str, int, str]:
    out = subprocess.check_output([aapt, "dump", "badging", str(apk)], text=True)
    m = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", out, re.MULTILINE)
    if not m:
        raise SystemExit(f"{apk.name}: no package line")
    return m.group(1), int(m.group(2)), m.group(3)


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--directory", required=True)
    p.add_argument("--aapt", required=True)
    p.add_argument("--notes", default="")
    a = p.parse_args()
    out = Path(a.directory)
    apks, versions = {}, set()
    for apk in sorted(out.glob("*.apk")):
        pkg, code, version = badging(a.aapt, apk)
        if pkg != PACKAGE:
            raise SystemExit(f"{apk.name}: unexpected package {pkg}")
        apks[apk.name] = {"code": code}
        versions.add(version)
    uni = next((k for k in apks if k == "SahlPDF.apk" or k.startswith("SahlPDF-v")), None)
    if not uni:
        raise SystemExit("universal SahlPDF APK missing")
    if len(versions) != 1:
        raise SystemExit(f"APKs disagree on version: {versions}")
    meta = {"code": apks[uni]["code"], "sahlpdf": versions.pop(), "notes": a.notes, "apks": apks}
    (out / "version.json").write_text(json.dumps(meta), encoding="utf-8")
    print(json.dumps(meta, indent=2))


if __name__ == "__main__":
    main()
