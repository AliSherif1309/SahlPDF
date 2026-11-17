"""Reject APKs whose C++ runtime differs from the PDF reader's selected runtime."""
from pathlib import Path
import hashlib
import zipfile


def digest(data):
    return hashlib.sha256(data).digest()


def hashes(root, abi):
    return {
        digest(path.read_bytes())
        for path in Path(root).rglob("libc++_shared.so")
        if path.parent.name == abi
    }


def main():
    checked = 0
    for apk in sorted(Path("out").glob("*.apk")):
        with zipfile.ZipFile(apk) as archive:
            entries = [name for name in archive.namelist() if name.startswith("lib/") and name.endswith("/libc++_shared.so")]
            if not entries:
                raise RuntimeError(f"{apk}: missing C++ runtime")
            for name in entries:
                abi = name.split("/")[1]
                reader = hashes("PdfiumAndroid/build/intermediates/library_jni", abi)
                merged = hashes("app/build/intermediates/merged_native_libs", abi)
                if not reader or not merged or not merged.issubset(reader):
                    raise RuntimeError(f"{abi}: packaging did not select the reader runtime")
                final = hashes("app/build/intermediates/stripped_native_libs", abi) | merged
                if digest(archive.read(name)) not in final:
                    raise RuntimeError(f"{apk}: unexpected packaged runtime for {abi}")
                checked += 1
    if checked == 0:
        raise RuntimeError("No APK runtimes were checked")
    print(f"Verified {checked} APK/ABI runtime selections against PdfiumAndroid")


if __name__ == "__main__":
    main()
