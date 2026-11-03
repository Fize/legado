#!/usr/bin/env python3
"""Install debug test APKs and verify export recovery across real Android SIGKILLs.

Uses an opt-in instrumentation probe and private, UUID-scoped fixtures. This
checks the local file journal and a real Room publication transaction; it does
not establish SAF-provider or foreground-service lifecycle coverage.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import uuid
from urllib.parse import quote

from capture_android_compatibility import ROOT, capture

CLASS = "io.legado.app.service.BookExportProcessDeathProbe"
CASES = {
    "prepared-restore": ("prepared", "restore", True, None, None),
    "prepared-keep": ("prepared", "keep", True, None, None),
    "backed-up-restore": ("backed_up", "restore", True, None, None),
    "backed-up-keep": ("backed_up", "keep", True, None, None),
    "installed-restore": ("installed", "restore", True, None, None),
    "installed-keep": ("installed", "keep", True, None, None),
    "published-restore": ("published", "restore", True, None, None),
    "published-keep": ("published", "keep", True, None, None),
    "new-file-restore": ("installed", "restore", False, None, None),
    "new-file-keep": ("installed", "keep", False, None, None),
    "restore-dies-after-staging": ("installed", "restore", True, "generated_staged", None),
    "restore-dies-after-original": ("installed", "restore", True, "original_restored", None),
    "keep-dies-after-installing": ("backed_up", "keep", True, "generated_installed", None),
    "changed-target": ("backed_up", "keep", True, None, "target"),
    "changed-backup": ("backed_up", "restore", True, None, "backup"),
}
SAF_CASES = {
    "revoked-grant-restore": ("installed", "restore", True, None, None),
    "revoked-grant-keep": ("installed", "keep", True, None, None),
}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def main():
    if not __debug__:
        raise SystemExit("Run this verification harness with Python assertions enabled (without -O)")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--package", default="com.xiaobaitu.reader.debug")
    parser.add_argument("--app-apk", type=Path)
    parser.add_argument("--test-apk", type=Path, default=ROOT / "app/build/outputs/apk/androidTest/app/debug/app-app-debug-androidTest.apk")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--scenario", action="append", choices=list(CASES) + list(SAF_CASES))
    parser.add_argument("--storage", choices=["local", "saf"], default="local",
                        help="saf opens the real system picker and takes a temporary persisted grant")
    args = parser.parse_args()
    if args.storage == "local" and any(case in SAF_CASES for case in args.scenario or []):
        parser.error("Grant revocation scenarios require --storage saf")
    cases = dict(CASES, **SAF_CASES) if args.storage == "saf" else CASES
    selected = args.scenario or list(cases)
    if not re.fullmatch(r"[A-Za-z0-9_.]+\.debug", args.package):
        parser.error("This process-killing harness only targets a debug application")
    base = [args.adb, "-s", args.serial]
    output = args.output or ROOT / "app/build/reports" / ("export-process-death-saf" if args.storage == "saf" else "export-process-death") / args.serial
    output.mkdir(parents=True, exist_ok=True)

    def run(command, timeout=90):
        return subprocess.run(base + command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout, check=False)

    abi_result = run(["shell", "getprop", "ro.product.cpu.abi"])
    abi = abi_result.stdout.decode().strip()
    if abi not in {"arm64-v8a", "armeabi-v7a", "x86_64"}:
        raise RuntimeError(f"Unsupported test ABI: {abi}")
    apk = args.app_apk or ROOT / f"app/build/outputs/apk/app/debug/app-app-{abi}-debug.apk"
    for path in [apk, args.test_apk]:
        if not path.is_file():
            raise FileNotFoundError(path)
        installed = run(["install", "-r", "-t", str(path)])
        if installed.returncode != 0 or b"Success" not in installed.stdout:
            raise RuntimeError(installed.stdout.decode(errors="replace"))
    if run(["shell", "run-as", args.package, "true"]).returncode != 0:
        raise RuntimeError("Target package must permit run-as (debuggable)")

    def installed_identity(package, local_apk):
        result = run(["shell", "pm", "path", package])
        paths = [line.removeprefix("package:") for line in result.stdout.decode().splitlines()
                 if line.startswith("package:") and line.endswith("/base.apk")]
        if len(paths) != 1 or not re.fullmatch(r"/data/app/[A-Za-z0-9/_=+~.-]+/base\.apk", paths[0]):
            raise RuntimeError("Cannot identify the installed test package APK")
        remote = run(["shell", "sha256sum", paths[0]])
        expected = digest(local_apk.read_bytes())
        fields = remote.stdout.decode().split()
        if remote.returncode != 0 or not fields or fields[0] != expected:
            raise RuntimeError(f"Installed APK does not match the selected artifact: {package}")
        return {"package": package, "path": paths[0], "sha256": expected}

    installed_apks = [installed_identity(args.package, apk), installed_identity(args.package + ".test", args.test_apk)]

    grant_id = str(uuid.uuid4()) if args.storage == "saf" else None
    saf_path = f"/sdcard/Documents/legado-export-process-{grant_id}"
    tree = "content://com.android.externalstorage.documents/tree/" + quote(f"primary:Documents/legado-export-process-{grant_id}", safe="")
    storage_options = {"exportTree": tree} if grant_id else {}
    report = {"scope": f"{args.storage} export journal and Room across SIGKILL", "status": "running", "source": capture(args.adb, args.serial),
              "debug_apk_sha256": digest(apk.read_bytes()), "test_apk_sha256": digest(args.test_apk.read_bytes()), "installed_apks": installed_apks, "scenarios": []}

    def probe(method, case_id, options, log_name):
        command = ["shell", "am", "instrument", "-w", "-r", "-e", "class", f"{CLASS}#{method}",
                   "-e", "exportProcessDeath", "yes", "-e", "exportCaseId", case_id]
        for key, value in dict(storage_options, **options).items():
            command.extend(["-e", key, value])
        command.append(f"{args.package}.test/androidx.test.runner.AndroidJUnitRunner")
        result = run(command)
        log = output / log_name
        log.write_bytes(result.stdout)
        return result.stdout.decode(errors="replace"), {"path": str(log), "sha256": digest(result.stdout)}

    def read_file(case_id, name):
        command = ["exec-out", "run-as", args.package, "cat", f"files/compat-export-process/{case_id}/{name}"]
        if grant_id and name.startswith("output/"):
            command = ["exec-out", "cat", f"{saf_path}/{case_id}/{name.removeprefix('output/')}"]
        result = run(command)
        if result.returncode != 0:
            raise RuntimeError(result.stdout.decode(errors="replace"))
        return result.stdout

    def death_marker(case_id, log, stage, phase, old_pids):
        if not re.search(r"process (?:crashed|crash)", log, re.IGNORECASE):
            raise AssertionError("Expected an actual crashed instrumentation process, not a Java exception")
        marker = json.loads(read_file(case_id, "marker.json"))
        assert marker["stage"] == stage and marker["phase"] == phase, marker
        assert marker["pid"] not in old_pids, marker
        if stage == "publication":
            assert marker["transaction_open"] is (phase in {"backed_up", "installed"}), marker
        alive = run(["shell", "pidof", args.package]).stdout.decode().split()
        assert str(marker["pid"]) not in alive, "The killed process is still alive"
        old_pids.add(marker["pid"])
        return marker

    try:
        if grant_id:
            created = run(["shell", "mkdir", saf_path])
            if created.returncode != 0:
                raise RuntimeError(created.stdout.decode(errors="replace"))
            log, entry = probe("prepareSafGrant", grant_id, {}, "saf-grant.log")
            report["saf_grant_log"] = entry
            assert "OK (1 test)" in log and "FAILURES!!!" not in log, log[-3000:]
            assert json.loads(read_file(grant_id, "grant.json")) == {"tree": tree, "persisted": True}
            report["saf_tree"] = tree
        for label in selected:
            phase, action, original, second_kill, external = cases[label]
            case_id = str(uuid.uuid4())
            item = {"scenario": label, "case_id": case_id, "deaths": [], "logs": [], "status": "running"}
            report["scenarios"].append(item)
            options = {"exportPhase": phase, "exportAction": action, "exportOriginal": "yes" if original else "no"}
            old_pids = set()
            try:
                log, entry = probe("publishAndDieAtCheckpoint", case_id, options, f"{label}-publication.log")
                item["logs"].append(entry)
                item["deaths"].append(death_marker(case_id, log, "publication", phase, old_pids))
                initial = {"unrelated.txt": f"UNRELATED:{case_id}"}
                if phase in {"prepared", "backed_up"}:
                    initial[".staged.book.txt"] = f"GENERATED:{case_id}"
                if phase in {"installed", "published"}:
                    initial["book.txt"] = f"GENERATED:{case_id}"
                if original:
                    initial["book.txt" if phase == "prepared" else ".backup.book.txt"] = f"ORIGINAL:{case_id}"
                initial_hashes = {name: digest(value.encode()) for name, value in initial.items()}
                assert item["deaths"][0]["outputs"] == initial_hashes, item["deaths"][0]
                if label in SAF_CASES:
                    log, entry = probe("revokeSafGrantAndRejectRecovery", case_id, options, f"{label}-revocation.log")
                    item["logs"].append(entry)
                    assert "OK (1 test)" in log and "FAILURES!!!" not in log, log[-3000:]
                    revoked = json.loads(read_file(case_id, "revocation.json"))
                    assert revoked["persisted"] is False and revoked["read_denied"] is True
                    assert revoked["recovery_failed"] is True and revoked["records"] == 1
                    assert revoked["before"] == initial_hashes
                    for name, value in initial.items():
                        assert read_file(case_id, f"output/{name}") == value.encode(), name
                    item["revocation"] = revoked
                    log, entry = probe("reacquireSafGrant", case_id, options, f"{label}-regrant.log")
                    item["logs"].append(entry)
                    assert "OK (1 test)" in log and "FAILURES!!!" not in log, log[-3000:]
                    assert json.loads(read_file(case_id, "grant.json")) == {"tree": tree, "persisted": True}
                if second_kill:
                    second_options = dict(options, exportRecoveryPoint=second_kill)
                    log, entry = probe("recoverAndDieAgain", case_id, second_options, f"{label}-recovery-death.log")
                    item["logs"].append(entry)
                    item["deaths"].append(death_marker(case_id, log, "recovery", second_kill, old_pids))
                if external:
                    options["exportExternal"] = external
                log, entry = probe("recoverAndVerify", case_id, options, f"{label}-verification.log")
                item["logs"].append(entry)
                assert "OK (1 test)" in log and "FAILURES!!!" not in log, log[-3000:]
                result = json.loads(read_file(case_id, "result.json"))
                assert result["pid"] not in old_pids, result
                assert result["room_row_present"] is (phase == "published"), result
                assert result["room_row_absent"] is (phase != "published"), result
                expected = {"unrelated.txt": f"UNRELATED:{case_id}"}
                if external:
                    expected[".staged.book.txt"] = f"GENERATED:{case_id}"
                    expected[".backup.book.txt"] = f"{'EXTERNAL_BACKUP' if external == 'backup' else 'ORIGINAL'}:{case_id}"
                    if external == "target":
                        expected["book.txt"] = f"EXTERNAL:{case_id}"
                    assert result["external_failure_expected"] is True and result["records"] == 1
                else:
                    assert result["records"] == 0
                    if action == "keep" or original:
                        expected["book.txt"] = f"{'ORIGINAL' if action == 'restore' else 'GENERATED'}:{case_id}"
                expected_hashes = {name: digest(value.encode()) for name, value in expected.items()}
                assert result["outputs"] == expected_hashes, result
                for name, value in expected.items():
                    actual = read_file(case_id, f"output/{name}")
                    assert actual == value.encode(), name
                item["result"] = result
                item["status"] = "verified"
            except Exception as error:
                item["status"] = "failed"
                item["error"] = str(error)
                raise
            finally:
                try:
                    log, entry = probe("cleanupFixture", case_id, {}, f"{label}-cleanup.log")
                    item["logs"].append(entry)
                    item["cleanup_passed"] = "OK (1 test)" in log and "FAILURES!!!" not in log
                except Exception as cleanup_error:
                    item["cleanup_passed"] = False
                    item["cleanup_error"] = str(cleanup_error)
                if not item["cleanup_passed"]:
                    item["status"] = "failed"
                    raise AssertionError("Fixture cleanup failed")
                if item["status"] == "verified":
                    item["status"] = "passed"
            print(f"PASS {label}: {len(old_pids)} actual process deaths; new process verified files and Room commit/rollback", flush=True)

    finally:
        if grant_id:
            try:
                log, entry = probe("cleanupSafGrant", grant_id, {}, "saf-grant-cleanup.log")
                report["saf_grant_cleanup_log"] = entry
                report["saf_grant_cleanup_passed"] = "OK (1 test)" in log and "FAILURES!!!" not in log
                removed = run(["shell", "rm", "-r", saf_path])
                report["saf_directory_cleanup_passed"] = removed.returncode == 0
            except Exception as error:
                report["saf_grant_cleanup_passed"] = False
                report["saf_cleanup_error"] = str(error)
        report["passed"] = sum(item["status"] == "passed" for item in report["scenarios"])
        report["failed"] = sum(item["status"] != "passed" for item in report["scenarios"])
        report["actual_process_deaths"] = sum(len(item["deaths"]) for item in report["scenarios"])
        report["status"] = "passed" if (report["passed"] == len(selected) and report["failed"] == 0
            and (not grant_id or report.get("saf_grant_cleanup_passed") and report.get("saf_directory_cleanup_passed"))) else "failed"
        (output / "evidence.json").write_text(json.dumps(report, indent=2) + "\n")
    if grant_id:
        assert report.get("saf_grant_cleanup_passed") and report.get("saf_directory_cleanup_passed"), "SAF fixture cleanup failed"
    print(f"Verified {report['passed']} scenarios, {report['actual_process_deaths']} actual process deaths. Evidence: {output / 'evidence.json'}")


if __name__ == "__main__":
    main()
