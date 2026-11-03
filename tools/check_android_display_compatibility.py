#!/usr/bin/env python3
"""Exercise font scales, maximum display scaling, navigation modes, IME and cutouts."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tools"))
from android_device_checks import add_apk_arguments, resolve_apks
from capture_android_compatibility import capture


NAV_CATEGORY = "com.android.internal.systemui.navbar"
CUTOUT_PREFIX = "com.android.internal.display.cutout.emulation."
SETTINGS_CASES = ("user-agent", "custom-hosts", "user-agent-counterexample")


def active_overlay(listing, prefix):
    rows = [line.removeprefix("[x] ").strip()
            for line in listing.splitlines() if line.startswith("[x] ") and prefix in line]
    if len(rows) > 1:
        raise RuntimeError(f"Ambiguous active overlays for {prefix}: {rows}")
    return rows[0] if rows else None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", required=True, type=Path)
    add_apk_arguments(parser)
    args = parser.parse_args()
    resolve_apks(parser, args)
    if not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("Display/navigation probes are restricted to emulators")
    output = args.output.resolve()
    if output.exists() and (not output.is_dir() or any(output.iterdir())):
        parser.error("--output must be new or empty")
    output.mkdir(parents=True, exist_ok=True)

    base = [args.adb, "-s", args.serial]

    def run(*command, timeout=600):
        return subprocess.check_output(base + list(command), stderr=subprocess.STDOUT, timeout=timeout)

    def shell(command, timeout=60):
        return run("shell", command, timeout=timeout).decode(errors="replace").strip()

    def overlay_listing():
        return shell("cmd overlay list --user current")

    def set_nav(mode):
        if mode not in ("gestural", "threebutton"):
            raise ValueError(mode)
        shell(f"cmd overlay enable-exclusive --user current --category {NAV_CATEGORY}.{mode}")
        deadline = 50
        for _ in range(deadline):
            if f"[x] {NAV_CATEGORY}.{mode}" in overlay_listing():
                return
            subprocess.run(base + ["shell", "sleep", "0.1"], check=True,
                           stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT, timeout=5)
        raise RuntimeError(f"Navigation overlay did not activate: {mode}")

    def set_cutout(overlay):
        for name in ("corner", "double", "hole", "tall", "waterfall"):
            shell(f"cmd overlay disable {CUTOUT_PREFIX}{name}")
        if overlay:
            shell(f"cmd overlay enable {overlay}")
        deadline = 50
        for _ in range(deadline):
            current = active_overlay(overlay_listing(), CUTOUT_PREFIX)
            if current == overlay:
                return
            subprocess.run(base + ["shell", "sleep", "0.1"], check=True,
                           stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT, timeout=5)
        raise RuntimeError(f"Cutout overlay did not activate: {overlay}")

    def child(name, script, extra):
        directory = output / name
        log = output / f"{name}.log"
        command = [sys.executable, "-B", str(ROOT / "tools" / script),
                   "--adb", args.adb, "--serial", args.serial]
        if script == "check_android_search_ime.py":
            command.extend(["--output", str(directory)])
        else:
            command.extend([
                "--output", str(directory),
                "--app-apk", str(args.app_apk),
                "--test-apk", str(args.test_apk),
                *extra,
            ])
        with log.open("wb") as stream:
            subprocess.run(command, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT,
                           check=True, timeout=1200)
        evidence = json.loads((directory / "evidence.json").read_text())
        if evidence.get("status") != "passed":
            raise RuntimeError(f"Child check did not pass: {name}")
        return {"directory": str(directory.relative_to(output)), "log": log.name, "evidence": evidence}

    before = {
        "source": capture(args.adb, args.serial),
        "overlay_listing": overlay_listing(),
        "font_scale": shell("settings get system font_scale"),
        "density": shell("wm density"),
        "size": shell("wm size"),
    }
    original_nav = active_overlay(before["overlay_listing"], NAV_CATEGORY)
    original_cutout = active_overlay(before["overlay_listing"], CUTOUT_PREFIX)
    report = {
        "scope": "System font 100/130/200%, 640dpi maximum display scaling, gesture and "
                 "three-button navigation, real IME and emulated display cutout",
        "serial": args.serial,
        "before": before,
        "installed_apks": [],
        "runs": [],
    }
    try:
        for package, apk in (
            ("com.xiaobaitu.reader.debug", args.app_apk),
            ("com.xiaobaitu.reader.debug.test", args.test_apk),
        ):
            result = run("install", "-r", "-t", str(apk), timeout=180).decode(errors="replace")
            if "Success" not in result:
                raise RuntimeError(f"APK installation failed: {package}")
            path_lines = [line.removeprefix("package:")
                          for line in run("shell", "pm", "path", package).decode().splitlines()
                          if line.endswith("/base.apk")]
            if len(path_lines) != 1 or not re.fullmatch(
                    r"/data/app/[A-Za-z0-9/_=+~.-]+/base\.apk", path_lines[0]):
                raise RuntimeError(f"Unexpected installed APK path: {package}")
            actual = run("shell", "sha256sum", path_lines[0]).decode().split()[0]
            expected = hashlib.sha256(apk.read_bytes()).hexdigest()
            if actual != expected:
                raise RuntimeError(f"Installed APK differs: {package}")
            report["installed_apks"].append({"package": package, "sha256": actual})

        profiles = (
            ("font100-gesture", "1.0", None, "gestural", None),
            ("font130-gesture", "1.3", None, "gestural", None),
            ("font200-density640-gesture-tall-cutout", "2.0", 640, "gestural", "tall"),
            ("font200-density640-threebutton-tall-cutout", "2.0", 640, "threebutton", "tall"),
        )
        for name, font_scale, density, mode, cutout in profiles:
            set_nav(mode)
            set_cutout(CUTOUT_PREFIX + cutout if cutout else None)
            extra = ["--font-scale", font_scale, "--rotation", "0"]
            if density:
                extra += ["--density", str(density)]
            settings = child(name, "check_android_settings_inputs.py", [
                *extra,
            ])
            evidence = settings["evidence"]
            expected = {"font_scale": font_scale, "density": density, "rotation": "0"}
            if evidence.get("requested") != expected:
                raise RuntimeError(f"Wrong settings profile: {name}")
            if len(evidence.get("actual_window_profiles", [])) != 6:
                raise RuntimeError(f"Incomplete dialog profiles: {name}")
            for case in SETTINGS_CASES:
                rows = json.loads((output / name / f"settings-input-{case}.json").read_text())
                if rows[-1].get("settings_restored") is not True:
                    raise RuntimeError(f"Settings cleanup was not recorded: {name}/{case}")
                measured = rows[:-1]
                if len(measured) != 2:
                    raise RuntimeError(f"Unexpected dialog profile count: {name}/{case}")
                if cutout and not all(row["geometry"].get("display_cutout_bounds") for row in measured):
                    raise RuntimeError(f"Display cutout insets were not measured: {name}/{case}")
                if not all(row["ime_visible"] for row in measured if row["stage"] == "editing"):
                    raise RuntimeError(f"Real IME was not visible: {name}/{case}")
            report["runs"].append({
                "name": name,
                "font_scale": font_scale,
                "density": density,
                "navigation_mode": mode,
                "cutout": cutout,
                **settings,
            })

        after_source = capture(args.adb, args.serial)
        if after_source["source_sha256"] != before["source"]["source_sha256"]:
            raise RuntimeError("Source changed while device checks were running")
        report["source_unchanged"] = True
        report["status"] = "passed"
    except Exception as failure:
        report.update(status="failed", error=f"{type(failure).__name__}: {failure}")
        raise
    finally:
        try:
            if original_nav:
                shell(f"cmd overlay enable-exclusive --user current --category {original_nav}")
            for name in ("corner", "double", "hole", "tall", "waterfall"):
                shell(f"cmd overlay disable {CUTOUT_PREFIX}{name}")
            if original_cutout:
                shell(f"cmd overlay enable {original_cutout}")
            report["after"] = {
                "overlay_listing": overlay_listing(),
                "font_scale": shell("settings get system font_scale"),
                "density": shell("wm density"),
                "size": shell("wm size"),
            }
            report["navigation_restored"] = (
                active_overlay(report["after"]["overlay_listing"], NAV_CATEGORY) == original_nav)
            report["cutout_restored"] = (
                active_overlay(report["after"]["overlay_listing"], CUTOUT_PREFIX) == original_cutout)
            report["display_settings_restored"] = (
                report["after"]["font_scale"] == before["font_scale"]
                and report["after"]["density"] == before["density"]
                and report["after"]["size"] == before["size"])
            for package in ("com.xiaobaitu.reader.debug.test", "com.xiaobaitu.reader.debug"):
                shell(f"pm uninstall {package}")
            report["project_packages_removed"] = not shell("pm list packages com.xiaobaitu.reader")
            if report.get("status") == "passed" and not all(report.get(key) is True for key in (
                    "navigation_restored", "cutout_restored", "display_settings_restored",
                    "project_packages_removed")):
                report.update(status="failed", error="Device restoration postcondition failed")
        finally:
            (output / "evidence.json").write_text(
                json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    if report.get("status") != "passed":
        raise RuntimeError("Device restoration postcondition failed")
    print(f"PASS {args.serial}: 3 font scales, 640dpi, gesture/3-button, real IME and tall cutout")


if __name__ == "__main__":
    main()
