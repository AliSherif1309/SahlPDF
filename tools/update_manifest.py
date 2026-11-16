"""Build updater metadata from the actual APK manifests, including ABI version offsets."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess


def apk_info(badging, expected_version):
    match = re.search(
        r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'",
        badging, re.MULTILINE,
    )
    if not match:
        raise ValueError("Missing APK package/version metadata")
    package, code, version = match.groups()
    if package != "com.aly.sahlpdf" or version != expected_version or int(code) <= 0:
        raise ValueError("Unexpected APK identity or version")
    return {"code": int(code)}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--directory", required=True)
    parser.add_argument("--aapt", required=True)
    args = parser.parse_args()
    output = Path(args.directory)
    version = os.environ["VERSION"]
    apks = {}
    for apk in sorted(output.glob("*.apk")):
        badging = subprocess.check_output(
            [args.aapt, "dump", "badging", str(apk)], text=True,
        )
        apks[apk.name] = apk_info(badging, version)
    if "SahlPDF.apk" not in apks:
        raise ValueError("Universal release APK is required")
    metadata = {
        "code": 1000 + int(os.environ["GITHUB_RUN_NUMBER"]),
        "sahlpdf": version,
        "engine": os.environ.get("ENGINE", ""),
        "notes": subprocess.check_output(
            ["git", "log", "-1", "--format=%s"], text=True,
        ).strip(),
        "apks": apks,
    }
    (output / "version.json").write_text(json.dumps(metadata), encoding="utf-8")


if __name__ == "__main__":
    main()
