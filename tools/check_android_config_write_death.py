#!/usr/bin/env python3
"""Opt-in SIGKILL/recovery check for a debug app's isolated configuration fixture."""
import argparse
import hashlib
import json
from pathlib import Path
import shlex
import subprocess
import time
import uuid

PACKAGE = "com.xiaobaitu.reader.debug"
RUNNER = PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"
PROBE = "io.legado.app.help.storage.RestoreConfigurationDeathProbe"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    case_id = str(uuid.uuid4())
    relative = "cache/compat-config-write/" + case_id
    base = [args.adb, "-s", args.serial]

    def adb(*words, check=True):
        value = subprocess.run(base + list(words), stdout=subprocess.PIPE,
                               stderr=subprocess.PIPE, timeout=30)
        if check and value.returncode != 0:
            raise RuntimeError("adb command failed: " + (value.stdout + value.stderr).decode(errors="replace"))
        return value

    def read(name):
        return adb("exec-out", "run-as", PACKAGE, "cat", relative + "/" + name).stdout

    def instrument(method):
        return base + ["shell", "am", "instrument", "-w", "-r", "-e", "class", PROBE + "#" + method,
                       "-e", "configWriteDeath", "yes", "-e", "configWriteId", case_id, RUNNER]

    def installed_hash(package):
        path = adb("shell", "pm", "path", package).stdout.decode().strip().removeprefix("package:")
        return adb("shell", "sha256sum", path).stdout.decode().split()[0]

    result = {"case_id": case_id, "serial": args.serial, "package": PACKAGE,
              "debug_apk_sha256": installed_hash(PACKAGE),
              "test_apk_sha256": installed_hash(PACKAGE + ".test"), "status": "running"}
    process = None
    try:
        with (args.output / "pending-writer.log").open("wb") as log:
            process = subprocess.Popen(instrument("holdPendingWriteForHostKill"), stdout=log,
                                       stderr=subprocess.STDOUT)
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise AssertionError("Probe ended before reaching the pending-write barrier")
                probe = adb("exec-out", "run-as", PACKAGE, "cat", relative + "/pending.json", check=False)
                # exec-out may return zero for a failed remote cat; a newly created
                # marker can also be observed before its first complete write.
                try:
                    pending = json.loads(probe.stdout)
                except (json.JSONDecodeError, UnicodeDecodeError):
                    pending = None
                if isinstance(pending, dict):
                    break
                time.sleep(0.25)
            else:
                raise AssertionError("Live writer never reached its pending-write barrier")
            assert pending["id"] == case_id
            pid = int(pending["pid"])
            assert pid > 1
            cmdline = adb("exec-out", "run-as", PACKAGE, "cat", f"/proc/{pid}/cmdline").stdout
            assert cmdline.split(b"\0")[0].decode() == PACKAGE
            own_uid = int(adb("shell", "run-as", PACKAGE, "id", "-u").stdout)
            assert own_uid >= 10000
            status = adb("exec-out", "run-as", PACKAGE, "cat", f"/proc/{pid}/status").stdout.decode()
            assert f"Uid:\t{own_uid}\t" in status
            assert read("style.json") == ("old:" + case_id).encode()
            assert read("style.json.new") == ("partial:" + case_id).encode()
            assert process.poll() is None
            sender_uid = int(adb("shell", "id", "-u").stdout)
            if sender_uid == 0:
                # Reuse only an already-enabled root test transport. The target app's
                # normal UID and package identity are still checked above.
                adb("shell", "kill", "-9", str(pid))
                sender = "existing_adb_root"
            else:
                owner = subprocess.run(base + ["shell", "run-as", PACKAGE, "sh", "-c",
                                               shlex.quote("kill -9 " + str(pid))],
                                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
                if owner.returncode == 0:
                    sender = "run_as_owner"
                else:
                    adb("shell", "su", "0", "kill", "-9", str(pid))
                    sender = "existing_su"
            result["signal_sender"] = sender
            process.wait(timeout=15)
        active = adb("shell", "pidof", PACKAGE, check=False).stdout.decode().split()
        assert str(pid) not in active
        assert read("style.json") == ("old:" + case_id).encode()
        assert read("style.json.new") == ("partial:" + case_id).encode()
        result.update({"killed_pid": pid, "normal_app_uid": own_uid, "signal": "SIGKILL",
                       "original_retained_after_death": True, "pending_file_retained_after_death": True})
        recovery = subprocess.run(instrument("recoverAfterHostKill"), stdout=subprocess.PIPE,
                                  stderr=subprocess.STDOUT, timeout=45)
        (args.output / "recovery.log").write_bytes(recovery.stdout)
        text = recovery.stdout.decode()
        assert "OK (1 test)" in text and "INSTRUMENTATION_STATUS_CODE: 0" in text
        assert "INSTRUMENTATION_STATUS_CODE: -2" not in text and "INSTRUMENTATION_STATUS_CODE: -3" not in text
        recovered = json.loads(read("recovered.json"))
        assert recovered["id"] == case_id and int(recovered["pid"]) != pid
        final = read("style.json")
        assert final == ("new:" + case_id).encode()
        # test is a shell builtin and is not an executable on some old system images.
        absent = adb("shell", "run-as", PACKAGE, "ls", relative + "/style.json.new", check=False)
        assert absent.returncode != 0 and b"No such file or directory" in absent.stdout + absent.stderr
        result.update({"status": "passed", "recovered_pid": recovered["pid"],
                       "final_file_sha256": hashlib.sha256(final).hexdigest(), "pending_removed": True})
        for name in ["style.json", "pending.json", "recovered.json"]:
            adb("shell", "run-as", PACKAGE, "rm", relative + "/" + name)
        adb("shell", "run-as", PACKAGE, "rmdir", relative)
        result["isolated_fixture_removed"] = True
    except BaseException as error:
        result.update({"status": "failed", "error": str(error)})
        raise
    finally:
        if process is not None and process.poll() is None:
            process.terminate()  # Stop only the adb client; the app's barrier has a bounded timeout.
        (args.output / "evidence.json").write_text(json.dumps(result, indent=2) + "\n")
    print(f"PASS {args.serial}: actual SIGKILL retained original; fresh process committed replacement")


if __name__ == "__main__":
    main()
