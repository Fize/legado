#!/usr/bin/env python3
"""Verify resize, Activity recreation and a real SIGKILL on one reader anchor.

The host kills the exact instrumentation/app PID after the device test has
completed the resize and recreation phases. A second instrumentation process
reopens the same owned book from the production bookshelf entry.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parent.parent
PACKAGE = "com.xiaobaitu.reader.debug"
RUNNER = PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"
TEST_CLASS = "io.legado.app.ui.lifecycle.LifecycleContinuityUiTest"


def digest(data):
    return hashlib.sha256(data).hexdigest()


def main():
    if not __debug__:
        raise SystemExit("Run without Python -O")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--mode", choices=["resize", "recreate"], required=True)
    parser.add_argument(
        "--app-apk",
        type=Path,
        default=ROOT / "app/build/outputs/apk/app/debug/app-app-arm64-v8a-debug.apk",
    )
    parser.add_argument(
        "--test-apk",
        type=Path,
        default=ROOT / "app/build/outputs/apk/androidTest/app/debug/app-app-debug-androidTest.apk",
    )
    parser.add_argument("--install", action="store_true")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("Only emulators are permitted")
    if args.output.exists() and any(args.output.iterdir()):
        parser.error("Output must be new or empty")
    args.output.mkdir(parents=True, exist_ok=True)
    case_id = str(uuid.uuid4())
    fixture = f"files/compat-lifecycle-continuity/{case_id}"
    base = [args.adb, "-s", args.serial]
    report = {
        "status": "running",
        "case_id": case_id,
        "serial": args.serial,
        "mode": args.mode,
        "package": PACKAGE,
        "scope": (
            "One production bookshelf -> mature text reader anchor across actual "
            "freeform task resize, explicit Activity recreation and a host SIGKILL; "
            "API33 uses the recreate mode because freeform resize is unavailable"
        ),
    }

    def adb(*words, check=True, timeout=40):
        result = subprocess.run(
            base + list(words),
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout,
        )
        if check and result.returncode:
            raise RuntimeError(
                "adb command failed: "
                + (result.stdout + result.stderr).decode(errors="replace")
            )
        return result

    def read(name):
        return adb("exec-out", "run-as", PACKAGE, "cat", fixture + "/" + name).stdout

    def read_app_path(path):
        return adb("exec-out", "run-as", PACKAGE, "cat", path).stdout

    def instrument(method):
        return base + [
            "shell",
            "am",
            "instrument",
            "-w",
            "-r",
            "-e",
            "class",
            TEST_CLASS + "#" + method,
            "-e",
            "lifecycleContinuity",
            "yes",
            "-e",
            "lifecycleContinuityId",
            case_id,
            "-e",
            "lifecycleContinuityMode",
            args.mode,
            RUNNER,
        ]

    def installed_identity(package, local_apk):
        paths = [
            line.removeprefix("package:")
            for line in adb("shell", "pm", "path", package).stdout.decode().splitlines()
            if line.startswith("package:") and line.endswith("/base.apk")
        ]
        if len(paths) != 1:
            raise AssertionError("Cannot identify " + package)
        expected = digest(local_apk.read_bytes())
        actual = adb("shell", "sha256sum", paths[0]).stdout.decode().split()[0]
        if actual != expected:
            raise AssertionError(package + " installed APK does not match the selected artifact")
        return {"package": package, "path": paths[0], "sha256": actual}

    def run_test(method, log_name, timeout):
        result = subprocess.run(
            instrument(method),
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            timeout=timeout,
        )
        (args.output / log_name).write_bytes(result.stdout)
        text = result.stdout.decode(errors="replace")
        return result.returncode, text

    process = None
    marker = None
    killed_pid = None
    cleanup_needed = False
    try:
        device_api = int(adb("shell", "getprop", "ro.build.version.sdk").stdout)
        freeform = adb(
            "shell",
            "pm",
            "list",
            "features",
            check=False,
        ).stdout.decode().splitlines()
        has_freeform = "feature:android.software.freeform_window_management" in freeform
        if args.mode == "resize":
            assert has_freeform, "Resize mode requires a freeform-capable emulator"
        report["device"] = {"api": device_api, "freeform": has_freeform}
        if args.install:
            assert args.app_apk.is_file(), args.app_apk
            assert args.test_apk.is_file(), args.test_apk
            for apk in (args.app_apk, args.test_apk):
                installed = adb("install", "-r", "-t", str(apk), timeout=120)
                assert b"Success" in installed.stdout, installed.stdout
        report["debug_apk"] = installed_identity(PACKAGE, args.app_apk)
        report["test_apk"] = installed_identity(PACKAGE + ".test", args.test_apk)
        cleanup_needed = True

        with (args.output / "prepare.log").open("wb") as log:
            method = (
                "prepareActualResizeRecreationAndHoldForHostKill"
                if args.mode == "resize"
                else "prepareAvailableRecreationAndHoldForHostKill"
            )
            process = subprocess.Popen(
                instrument(method),
                stdout=log,
                stderr=subprocess.STDOUT,
            )
            deadline = time.monotonic() + 60
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise AssertionError(
                        "Preparation ended before the host-kill barrier; see prepare.log"
                    )
                try:
                    candidate = json.loads(read("ready.json"))
                except (ValueError, UnicodeDecodeError, RuntimeError):
                    candidate = None
                if (
                    isinstance(candidate, dict)
                    and candidate.get("id") == case_id
                    and candidate.get("status") == "ready_for_host_kill"
                ):
                    marker = candidate
                    break
                time.sleep(0.2)
            assert marker is not None, "Preparation never produced ready.json"
            pid = int(marker["pid"])
            killed_pid = pid
            assert pid > 1
            cmdline = read_app_path(f"/proc/{pid}/cmdline").split(b"\0")[0].decode()
            assert cmdline in {PACKAGE, PACKAGE + ".test", PACKAGE + ":test"}, repr(cmdline)
            uid = int(adb("shell", "run-as", PACKAGE, "id", "-u").stdout)
            assert uid >= 10000
            status = read_app_path(f"/proc/{pid}/status").decode()
            assert re.search(r"^Uid:\s+" + str(uid) + r"\s", status, re.M)
            current_pids = adb("shell", "pidof", PACKAGE, check=False).stdout.decode().split()
            assert str(pid) in current_pids
            assert process.poll() is None

            sender_uid = int(adb("shell", "id", "-u").stdout)
            if sender_uid == 0:
                adb("shell", "kill", "-9", str(pid))
                sender = "existing_adb_root"
            else:
                owner_signal = adb(
                    "shell",
                    "run-as",
                    PACKAGE,
                    "sh",
                    "-c",
                    shlex.quote("kill -9 " + str(pid)),
                    check=False,
                )
                if owner_signal.returncode == 0:
                    sender = "run_as_owner"
                else:
                    su_signal = adb(
                        "shell",
                        "su",
                        "0",
                        "kill",
                        "-9",
                        str(pid),
                        check=False,
                    )
                    if su_signal.returncode != 0:
                        raise RuntimeError(
                            "Neither owner nor existing su could SIGKILL the verified app PID: "
                            + (owner_signal.stdout + owner_signal.stderr).decode(errors="replace")
                            + (su_signal.stdout + su_signal.stderr).decode(errors="replace")
                        )
                    sender = "existing_su_root"
            process.wait(timeout=20)
        alive = adb("shell", "pidof", PACKAGE, check=False).stdout.decode().split()
        assert str(pid) not in alive, "SIGKILL target is still alive"
        report.update(
            {
                "killed_pid": pid,
                "killed_process_cmdline": cmdline,
                "normal_app_uid": uid,
                "signal": "SIGKILL",
                "signal_sender": sender,
                "old_pid_absent": True,
                "prepare": marker,
            }
        )

        returncode, verify_log = run_test(
            "verifyRecoveryAfterRealProcessDeath",
            "verify.log",
            timeout=90,
        )
        assert returncode == 0, verify_log[-6000:]
        assert "OK (1 test)" in verify_log, verify_log[-6000:]
        assert "FAILURES!!!" not in verify_log
        assert "INSTRUMENTATION_STATUS_CODE: -2" not in verify_log
        recovered = json.loads(read("recovered.json"))
        assert recovered["id"] == case_id
        assert recovered["old_pid"] == pid
        assert recovered["new_pid"] != pid
        assert recovered["status"] == "passed"
        assert recovered["anchor_page_restored"] is True
        assert recovered["chapter_index"] == marker["anchor_chapter_index"]
        assert recovered["chapter_url"] == marker["anchor_chapter_url"]
        assert recovered["entry"] == "production bookshelf continue-reading"
        if args.mode == "resize":
            assert marker["actual_resize"] is True
            assert marker["activity_identity_before_resize"] == marker["activity_identity_after_resize"]
            assert marker["window_before"] != marker["window_after"]
        else:
            assert marker["actual_resize"] is False
        assert marker["activity_identity_before_recreate"] != marker["activity_identity_after_recreate"]
        report.update(
            {
                "recovered": recovered,
                "activity_not_recreated_by_resize": (
                    marker["activity_identity_before_resize"]
                    == marker["activity_identity_after_resize"]
                ),
                "activity_recreated_explicitly": (
                    marker["activity_identity_before_recreate"]
                    != marker["activity_identity_after_recreate"]
                ),
                "fresh_process": recovered["new_pid"] != pid,
                "same_anchor_after_death": True,
            }
        )
        report["status"] = "passed"
    except BaseException as error:
        report.update({"status": "failed", "error": str(error)})
        raise
    finally:
        if process is not None and process.poll() is None:
            process.terminate()
        if cleanup_needed:
            cleanup = subprocess.run(
                instrument("cleanupFixtureAfterFailure"),
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                timeout=60,
            )
            (args.output / "cleanup.log").write_bytes(cleanup.stdout)
            cleanup_text = cleanup.stdout.decode(errors="replace")
            report["cleanup_passed"] = (
                cleanup.returncode == 0 and "OK (1 test)" in cleanup_text
                and "FAILURES!!!" not in cleanup_text
            )
        if report.get("status") == "passed" and not report.get("cleanup_passed"):
            report["status"] = "failed"
            report["error"] = "Fixture cleanup failed"
        (args.output / "evidence.json").write_text(
            json.dumps(report, indent=2) + "\n"
        )
    print(
        f"PASS {args.serial} {args.mode}: real resize/recreation and SIGKILL "
        f"retained the same production reader anchor"
    )


if __name__ == "__main__":
    main()
