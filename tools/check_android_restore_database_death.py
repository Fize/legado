#!/usr/bin/env python3
"""Kill an opted-in, fixture-only real restore after its own uncommitted rows reach the WAL."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET

PACKAGE = "com.xiaobaitu.reader.debug"
RUNNER = PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"
PROBE = "io.legado.app.help.storage.RestoreDatabaseDeathProbe"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    case_id = str(uuid.uuid4())
    relative = "cache/compat-db-restore/" + case_id
    base = [args.adb, "-s", args.serial]

    def adb(*words, check=True):
        value = subprocess.run(base + list(words), stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
        if check and value.returncode:
            raise RuntimeError((value.stdout + value.stderr).decode(errors="replace"))
        return value

    def read(path):
        return adb("exec-out", "run-as", PACKAGE, "cat", path).stdout

    def installed_hash(package):
        path = adb("shell", "pm", "path", package).stdout.decode().strip().removeprefix("package:")
        return adb("shell", "sha256sum", path).stdout.decode().split()[0]

    def instrument(method):
        return base + ["shell", "am", "instrument", "-w", "-r", "-e", "class", PROBE + "#" + method,
                       "-e", "databaseRestoreDeath", "yes", "-e", "databaseRestoreId", case_id, RUNNER]

    def terminate_app(pid):
        if int(adb("shell", "id", "-u").stdout) == 0:
            adb("shell", "kill", "-9", str(pid))
            return "existing_adb_root"
        owner = subprocess.run(base + ["shell", "run-as", PACKAGE, "sh", "-c",
                                       shlex.quote("kill -9 " + str(pid))],
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
        if owner.returncode == 0:
            return "run_as_owner"
        adb("shell", "su", "0", "kill", "-9", str(pid))
        return "existing_su"

    result = {"case_id": case_id, "serial": args.serial, "package": PACKAGE, "status": "running",
              "debug_apk_sha256": installed_hash(PACKAGE), "test_apk_sha256": installed_hash(PACKAGE + ".test")}
    process = None
    checked_pid = None
    ready = None
    recovered = False
    try:
        with (args.output / "pending-restore.log").open("wb") as log:
            process = subprocess.Popen(instrument("holdRealRestoreBeforeCommit"), stdout=log, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise AssertionError("Real restore probe ended before preparation completed")
                try:
                    candidate = json.loads(read(relative + "/ready.json"))
                except (ValueError, UnicodeDecodeError):
                    candidate = None
                if isinstance(candidate, dict):
                    ready = candidate
                    break
                time.sleep(0.2)
            assert ready is not None and ready["id"] == case_id, "No complete ready marker"
            pid = int(ready["pid"])
            assert pid > 1
            assert read(f"/proc/{pid}/cmdline").split(b"\0")[0].decode() == PACKAGE
            uid = int(adb("shell", "run-as", PACKAGE, "id", "-u").stdout)
            assert uid >= 10000 and re.search(r"^Uid:\s+" + str(uid) + r"\s", read(f"/proc/{pid}/status").decode(), re.M)
            checked_pid = pid
            assert ready["spillPrefix"] == "v_db_" + case_id.replace("-", "") + "_spill_"
            deadline = time.monotonic() + 25
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise AssertionError("Restore completed before the uncommitted WAL barrier")
                size = adb("shell", "run-as", PACKAGE, "wc", "-c", "databases/legado.db-wal", check=False)
                try:
                    length = int(size.stdout.split()[0]) if size.returncode == 0 else 0
                except (ValueError, IndexError):
                    length = 0
                if length > int(ready["walBaseline"]) + 262144:
                    wal = read("databases/legado.db-wal")
                    if ready["spillPrefix"].encode() in wal:
                        preferences = ET.fromstring(read("shared_prefs/local.xml"))
                        last = next((int(n.get("value")) for n in preferences if n.get("name") == "lastBackup"), None)
                        if last == 1234:
                            break
                time.sleep(0.2)
            else:
                raise AssertionError("No test-owned spill rows observed in an unfinished restore")
            assert process.poll() is None
            sender = terminate_app(pid)
            process.wait(timeout=15)
        assert str(pid) not in adb("shell", "pidof", PACKAGE, check=False).stdout.decode().split()
        result.update({"signal": "SIGKILL", "signal_sender": sender, "killed_pid": pid, "normal_app_uid": uid,
                       "wal_bytes_observed": len(wal), "wal_sha256": hashlib.sha256(wal).hexdigest(),
                       "own_spill_prefix_observed": True, "restore_not_marked_successful_before_kill": True})
        recovery = subprocess.run(instrument("verifyRollbackAfterHostKill"), stdout=subprocess.PIPE,
                                  stderr=subprocess.STDOUT, timeout=45)
        (args.output / "recovery.log").write_bytes(recovery.stdout)
        text = recovery.stdout.decode()
        assert "OK (1 test)" in text and "INSTRUMENTATION_STATUS_CODE: 0" in text
        assert "INSTRUMENTATION_STATUS_CODE: -2" not in text and "INSTRUMENTATION_STATUS_CODE: -3" not in text
        state = json.loads(read(relative + "/recovered.json"))
        assert state["id"] == case_id and state["pid"] != pid
        assert state["bookChapter"] == 7 and state["cookie"] == "sid=old"
        assert state["cacheRows"] == 0 and state["lastBackup"] == 1234
        recovered = True
        result.update({"status": "passed", "recovery": state, "fixture_database_rows_removed": True})
        for name in ["input.zip", "ready.json", "recovered.json"]:
            adb("shell", "run-as", PACKAGE, "rm", relative + "/" + name)
        adb("shell", "run-as", PACKAGE, "rmdir", relative)
        result["fixture_files_removed"] = True
    except BaseException as error:
        result.update({"status": "failed", "error": str(error)})
        raise
    finally:
        # Never leave a long-running SQL fixture or its temporary lastBackup value behind.
        if checked_pid is not None and process is not None and process.poll() is None:
            try:
                terminate_app(checked_pid)
                process.wait(timeout=15)
                result["failure_teardown_signal_sent"] = True
            except (subprocess.SubprocessError, RuntimeError):
                process.terminate()
        elif process is not None and process.poll() is None:
            try:
                adb("shell", "am", "force-stop", PACKAGE)
                process.wait(timeout=15)
                result["failure_teardown_force_stop_used"] = True
                candidate = json.loads(read(relative + "/ready.json"))
                if candidate.get("id") == case_id:
                    ready = candidate
            except (subprocess.SubprocessError, RuntimeError, ValueError):
                process.terminate()
        if ready is not None and not recovered:
            try:
                cleanup = subprocess.run(instrument("verifyRollbackAfterHostKill"), stdout=subprocess.PIPE,
                                         stderr=subprocess.STDOUT, timeout=45)
                (args.output / "failure-teardown.log").write_bytes(cleanup.stdout)
                result["failure_teardown_finished"] = True
            except subprocess.SubprocessError as error:
                result["failure_teardown_error"] = str(error)
        (args.output / "evidence.json").write_text(json.dumps(result, indent=2) + "\n")
    print(f"PASS {args.serial}: real restore killed after WAL spill; earlier book/cookie writes rolled back")


if __name__ == "__main__":
    main()
