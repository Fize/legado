#!/usr/bin/env python3
"""Install current debug APKs and verify real search IME compatibility on a test device.

Runs portrait/landscape at system font scales 1.0, 1.3 and 2.0. The device
instrumentation restores the original settings and search filter after each test.
A fresh output directory keeps prior run evidence intact. Requires Python 3.9+.
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
p.add_argument("--adb", required=True, help="Path to the Android SDK adb executable")
p.add_argument("--output", required=True, type=Path, help="New or empty evidence directory")
a = p.parse_args()
adb = a.adb
base = [adb, "-s", a.serial]
out = a.output.resolve()
if out.exists() and (not out.is_dir() or any(out.iterdir())):
    p.error("--output must be a new or empty directory")
out.mkdir(parents=True, exist_ok=True)
digest = lambda data: hashlib.sha256(data).hexdigest()
def run(*command):
    return subprocess.check_output(base + list(command), stderr=subprocess.STDOUT, timeout=180)
report = {"scope": "Real IME, system font scale and portrait/landscape search filter confirmation", "scales": [], "installed_apks": []}
for package, apk in [("com.xiaobaitu.reader.debug", ROOT / "app/build/outputs/apk/app/debug/app-app-arm64-v8a-debug.apk"),
                     ("com.xiaobaitu.reader.debug.test", ROOT / "app/build/outputs/apk/androidTest/app/debug/app-app-debug-androidTest.apk")]:
    assert b"Success" in run("install", "-r", "-t", str(apk))
    paths = [s.removeprefix("package:") for s in run("shell", "pm", "path", package).decode().splitlines() if s.endswith("/base.apk")]
    assert len(paths) == 1 and re.fullmatch(r"/data/app/[A-Za-z0-9/_=+~.-]+/base\.apk", paths[0])
    actual = run("shell", "sha256sum", paths[0]).decode().split()[0]
    assert actual == digest(apk.read_bytes())
    report["installed_apks"].append({"package": package, "sha256": actual})
report["source"] = capture(adb, a.serial)
try:
    for scale in ["1.0", "1.3", "2.0"]:
        item = {"scale": scale, "status": "running", "traces": []}
        report["scales"].append(item)
        data = run("shell", "am", "instrument", "-w", "-r", "-e", "class", "io.legado.app.ui.book.search.SearchImeUiTest",
                   "-e", "compatFontScale", scale, "com.xiaobaitu.reader.debug.test/androidx.test.runner.AndroidJUnitRunner")
        log = out / f"font-{scale}.log"; log.write_bytes(data)
        item["log"] = {"path": str(log), "sha256": digest(data)}
        try:
            for orientation in ["landscape", "portrait"]:
                name = f"search-ime-{scale}-{orientation}"
                raw = run("exec-out", "run-as", "com.xiaobaitu.reader.debug", "cat", f"cache/{name}.json")
                path = out / f"{name}.json"; path.write_bytes(raw)
                rows = json.loads(raw)
                item["traces"].append({"path": str(path), "sha256": digest(raw), "rows": rows})
                assert rows[-1]["settings_restored"] is True
                assert len(rows) == 2 and abs(rows[0]["font_scale"] - float(scale)) < .01
                assert rows[0]["orientation"] == orientation and rows[0]["ime_bottom_px"] > 0
                assert "editor" in rows[0]
                assert rows[0]["editor"]["max_channel_difference"] >= 32 and rows[0]["editor"]["cursor_stationary"] is True
                assert rows[0]["editor"]["changed_height_px"] >= rows[0]["editor"]["minimum_ink_height_px"]
                assert rows[0]["query_editor"]["cursor_stationary"] is True
                assert rows[0]["query_editor"]["max_channel_difference"] >= 32
                assert rows[0]["query_editor"]["changed_height_px"] >= rows[0]["query_editor"]["minimum_ink_height_px"]
                assert rows[0]["outside_dismissed_without_saving"] is True
                assert rows[0]["confirm_bottom_px"] <= rows[0]["keyboard_top_px"]
                pixels = run("exec-out", "run-as", "com.xiaobaitu.reader.debug", "cat", f"cache/{name}.png")
                assert pixels.startswith(b"\x89PNG")
                screenshot = out / f"{name}.png"; screenshot.write_bytes(pixels)
                item["traces"][-1]["screenshot"] = {"path": str(screenshot), "sha256": digest(pixels)}
                item["traces"][-1]["input_frames"] = []
                for suffix in ["query-edit-before", "query-edit-after", "edit-before", "edit-after"]:
                    pixels = run("exec-out", "run-as", "com.xiaobaitu.reader.debug", "cat", f"cache/{name}-{suffix}.png")
                    assert pixels.startswith(b"\x89PNG")
                    path = out / f"{name}-{suffix}.png"; path.write_bytes(pixels)
                    item["traces"][-1]["input_frames"].append({"path": str(path), "sha256": digest(pixels)})
            text = data.decode()
            codes = re.findall(r"INSTRUMENTATION_STATUS_CODE: (-?\d+)", text)
            assert "OK (2 tests)" in text and "FAILURES!!!" not in text and codes.count("0") == 2 and set(codes) == {"0", "1"}
            item["status"] = "passed"
            print(f"PASS {a.serial} font={scale}: portrait/landscape real IME; settings restored", flush=True)
        except Exception as error:
            item["status"] = "failed"; item["error"] = str(error)
            raise
finally:
    report["status"] = "passed" if len(report["scales"]) == 3 and all(s["status"] == "passed" for s in report["scales"]) else "failed"
    (out / "evidence.json").write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n")
