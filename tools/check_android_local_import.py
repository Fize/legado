#!/usr/bin/env python3
"""Verify actual local-book import at system font scales 1.0, 1.3 and 2.0.

Requires built app/test debug APKs and a fresh output directory. Each device test
restores font scale, rotation and the three preferences it changes, and removes
only its own input, copied books and preview files. Uses Python 3.9+.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
if not __debug__:
    raise SystemExit("Run with Python assertions enabled")
sys.path.insert(0, str(ROOT / "tools"))
from capture_android_compatibility import capture

p = argparse.ArgumentParser(description=__doc__)
p.add_argument("--serial", required=True)
p.add_argument("--adb", required=True)
p.add_argument("--output", required=True, type=Path)
p.add_argument("--scales", default="1.0,1.3,2.0", help="Comma-separated subset of 1.0,1.3,2.0")
a = p.parse_args()
scales = a.scales.split(",")
if not scales or len(set(scales)) != len(scales) or any(s not in ["1.0", "1.3", "2.0"] for s in scales):
    p.error("--scales must contain unique supported scales")
out = a.output.resolve()
if out.exists() and (not out.is_dir() or any(out.iterdir())):
    p.error("--output must be new or empty")
out.mkdir(parents=True, exist_ok=True)
base = [a.adb, "-s", a.serial]
def run(*args):
    return subprocess.check_output(base + list(args), stderr=subprocess.STDOUT, timeout=180)
def digest(data):
    return hashlib.sha256(data).hexdigest()
report = {"scope": "Actual shared local-book dialog, selection/recreation/cancel/import with real system font/orientation", "scales": [], "installed_apks": []}
for package, apk in [("com.xiaobaitu.reader.debug", ROOT / "app/build/outputs/apk/app/debug/app-app-arm64-v8a-debug.apk"),
                     ("com.xiaobaitu.reader.debug.test", ROOT / "app/build/outputs/apk/androidTest/app/debug/app-app-debug-androidTest.apk")]:
    assert b"Success" in run("install", "-r", "-t", str(apk))
    paths = [s.removeprefix("package:") for s in run("shell", "pm", "path", package).decode().splitlines() if s.endswith("/base.apk")]
    assert len(paths) == 1 and re.fullmatch(r"/data/app/[A-Za-z0-9/_=+~.-]+/base\.apk", paths[0])
    actual = run("shell", "sha256sum", paths[0]).decode().split()[0]
    assert actual == digest(apk.read_bytes())
    report["installed_apks"].append({"package": package, "sha256": actual})
report["source"] = capture(a.adb, a.serial)
try:
    for scale in scales:
        item = {"scale": scale, "status": "running", "traces": []}; report["scales"].append(item)
        data = run("shell", "am", "instrument", "-w", "-r", "-e", "class", "io.legado.app.ui.association.LocalBookImportAdaptiveUiTest", "-e", "compatFontScale", scale,
                   "com.xiaobaitu.reader.debug.test/androidx.test.runner.AndroidJUnitRunner")
        log = out / f"font-{scale}.log"; log.write_bytes(data)
        item["log"] = {"path": str(log), "sha256": digest(data)}
        try:
            for orientation in ["portrait", "landscape"]:
                name = f"local-import-{scale}-{orientation}"
                raw = run("exec-out", "run-as", "com.xiaobaitu.reader.debug", "cat", f"cache/{name}.json")
                rows = json.loads(raw); path = out / f"{name}.json"; path.write_bytes(raw)
                record = {"path": str(path), "sha256": digest(raw), "rows": rows, "screenshots": []}; item["traces"].append(record)
                # Keep all diagnostic images even when a UI assertion failed.
                for stage in ["initial", "recreated", "selected-one", "last"]:
                    try:
                        pixels = run("exec-out", "run-as", "com.xiaobaitu.reader.debug", "cat", f"cache/{name}-{stage}.png")
                    except subprocess.CalledProcessError:
                        continue
                    if not pixels.startswith(b"\x89PNG"):
                        # exec-out can return status 0 for a missing remote file.
                        # Missing diagnostics are retained as such; a passed run
                        # still requires all four real PNGs below.
                        record.setdefault("missing_screenshots", []).append(stage)
                        continue
                    image = out / f"{name}-{stage}.png"; image.write_bytes(pixels)
                    record["screenshots"].append({"path": str(image), "sha256": digest(pixels)})
                assert rows[-1]["settings_restored"] is True
                layouts = [r for r in rows if "stage" in r]
                assert len(layouts) == 3 and [r["selected"] for r in layouts] == [12, 11, 1]
                assert all(r["confirm_lines"] == 1 and r["confirm_ink_fits"] is True and abs(r["font_scale"] - float(scale)) < .01 for r in layouts)
                result = next(r for r in rows if "actual_import" in r)
                assert result["actual_import"] == (orientation == "landscape") and result["originals_preserved"]
                if orientation == "portrait":
                    assert any(r.get("outside_dismissed_without_import") is True for r in rows)
                assert len(record["screenshots"]) == 4
            text = data.decode(); codes = re.findall(r"INSTRUMENTATION_STATUS_CODE: (-?\d+)", text)
            assert "OK (2 tests)" in text and codes.count("0") == 2 and set(codes) == {"0", "1"}
            item["status"] = "passed"
            print(f"PASS {a.serial} font={scale}: actual import/cancel and selection restore; settings restored", flush=True)
        except Exception as error:
            item["status"] = "failed"; item["error"] = str(error)
            raise
finally:
    report["status"] = "passed" if len(report["scales"]) == len(scales) and all(s["status"] == "passed" for s in report["scales"]) else "failed"
    (out / "evidence.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
