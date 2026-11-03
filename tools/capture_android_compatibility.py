#!/usr/bin/env python3
"""Save source, APK, existing test reports and optional device metadata for a run.

Run after the checks, against unchanged source. Metadata does not itself prove
that tests passed or that old reports correspond to the recorded source.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent


def command(args):
    return subprocess.check_output(args, cwd=ROOT, stderr=subprocess.STDOUT, timeout=20).decode().strip()


def digest(path):
    hasher = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            hasher.update(block)
    return hasher.hexdigest()


def capture(adb=None, serial=None):
    names = subprocess.check_output(["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"], cwd=ROOT)
    source = hashlib.sha256()
    for name in sorted(set(names.split(b"\0")) - {b""}):
        path = ROOT / name.decode()
        if path.is_file():
            source.update(name + b"\0" + digest(path).encode() + b"\0")
    result = {
        "captured_utc": datetime.now(timezone.utc).isoformat(),
        "commit": command(["git", "rev-parse", "HEAD"]),
        "worktree_status": command(["git", "status", "--porcelain"]),
        "tracked_diff_sha256": hashlib.sha256(subprocess.check_output(["git", "diff", "HEAD", "--binary"], cwd=ROOT)).hexdigest(),
        "source_sha256": source.hexdigest(),
        "apks": [], "test_reports": [],
    }
    for apk in sorted((ROOT / "app/build/outputs/apk").rglob("*.apk")):
        result["apks"].append({"path": str(apk.relative_to(ROOT)), "sha256": digest(apk)})
    for directory in ["app/build/test-results/testAppDebugUnitTest", "app/build/outputs/androidTest-results"]:
        for report in sorted((ROOT / directory).rglob("TEST-*.xml")):
            root = ET.parse(report).getroot()
            result["test_reports"].append({"path": str(report.relative_to(ROOT)), "sha256": digest(report),
                "modified_utc": datetime.fromtimestamp(report.stat().st_mtime, timezone.utc).isoformat(),
                **{key: root.get(key) for key in ["tests", "failures", "errors", "skipped", "timestamp"]}})
    if adb:
        base = [adb] + (["-s", serial] if serial else [])
        result["device"] = {}
        for name, args in {
            "identity": ["shell", "getprop", "ro.product.model"],
            "api": ["shell", "getprop", "ro.build.version.sdk"],
            "abi": ["shell", "getprop", "ro.product.cpu.abi"],
            "supported_abis": ["shell", "getprop", "ro.product.cpu.abilist"],
            "supported_32bit_abis": ["shell", "getprop", "ro.product.cpu.abilist32"],
            "supported_64bit_abis": ["shell", "getprop", "ro.product.cpu.abilist64"],
            "page_size": ["shell", "getconf", "PAGE_SIZE"],
            "display_size": ["shell", "wm", "size"],
            "display_density": ["shell", "wm", "density"],
            "font_scale": ["shell", "settings", "get", "system", "font_scale"],
        }.items():
            try:
                result["device"][name] = command(base + args)
            except (subprocess.SubprocessError, OSError) as error:
                result["device"][name] = {"error": str(error)}
                if name == "page_size":
                    # Android 8 system images may not include getconf. The kernel
                    # reports the actual base page size for its own process maps.
                    try:
                        maps = command(base + ["shell", "cat", "/proc/self/smaps"])
                        sizes = {int(size) * 1024 for size in re.findall(
                            r"^KernelPageSize:\s+(\d+)\s+kB\s*$", maps, re.MULTILINE)}
                        if len(sizes) == 1:
                            result["device"][name] = str(sizes.pop())
                            result["device"]["page_size_source"] = "/proc/self/smaps KernelPageSize (getconf unavailable)"
                    except (subprocess.SubprocessError, OSError, ValueError):
                        pass  # Keep the original diagnostic instead of guessing.
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--adb", help="Optional adb executable; uses only read commands")
    parser.add_argument("--serial")
    args = parser.parse_args()
    result = capture(args.adb, args.serial)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"Saved {args.output}; source {result['source_sha256']}; {len(result['test_reports'])} existing reports")


if __name__ == "__main__":
    main()
