#!/usr/bin/env python3
"""Kill a fixture-owned real restore around DB commit; require automatic startup file recovery."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import shlex
import subprocess
import time
import uuid

PACKAGE = 'com.xiaobaitu.reader.debug'
PROBE = 'io.legado.app.help.storage.RestoreFileDeathProbe'
RUNNER = PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner'
ROOT = Path(__file__).resolve().parent.parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--checkpoint', choices=['before', 'after'], required=True)
    parser.add_argument('--settings-only', action='store_true')
    a = parser.parse_args()
    if not re.fullmatch(r'emulator-\d+', a.serial):
        parser.error('Only emulators are permitted')
    if a.output.exists() and any(a.output.iterdir()):
        parser.error('Output must be new or empty')
    a.output.mkdir(parents=True, exist_ok=True)
    case = str(uuid.uuid4())
    relative = 'cache/compat-file-restore/' + case
    base = [a.adb, '-s', a.serial]

    def adb(*words, check=True):
        result = subprocess.run(base + list(words), stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
        if check and result.returncode:
            raise RuntimeError((result.stdout + result.stderr).decode(errors='replace'))
        return result.stdout

    def read(path):
        return adb('exec-out', 'run-as', PACKAGE, 'cat', path, check=False)

    def instrument(method):
        return base + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', PROBE + '#' + method,
                       '-e', 'fileRestoreDeath', 'yes', '-e', 'fileRestoreId', case,
                       '-e', 'fileRestoreCheckpoint', a.checkpoint, '-e', 'fileRestoreSettingsOnly', 'yes' if a.settings_only else 'no', RUNNER]

    def terminate(pid):
        # The PID is only accepted after its command line and owner UID match.
        if int(adb('shell', 'id', '-u')) == 0:
            adb('shell', 'kill', '-9', str(pid))
            return 'existing_adb_root'
        owner = subprocess.run(base + ['shell', 'run-as', PACKAGE, 'sh', '-c',
                                       shlex.quote('kill -9 ' + str(pid))],
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
        if owner.returncode == 0:
            return 'run_as_owner'
        adb('shell', 'su', '0', 'kill', '-9', str(pid))
        return 'existing_su'

    report = {'status': 'running', 'case_id': case, 'serial': a.serial, 'checkpoint': a.checkpoint,
              'scope': 'real restore; preferences, two DataStores, preview samples, lastBackup, and optional media/styles/font',
              'settings_only': a.settings_only,
              'installed_apks': []}
    process = None
    verified_pid = None
    ready = None
    recovered = False
    try:
        for package, apk in [(PACKAGE, ROOT / 'app/build/outputs/apk/app/debug/app-app-arm64-v8a-debug.apk'),
                             (PACKAGE + '.test', ROOT / 'app/build/outputs/apk/androidTest/app/debug/app-app-debug-androidTest.apk')]:
            paths = [s[8:] for s in adb('shell', 'pm', 'path', package).decode().splitlines() if s.endswith('/base.apk')]
            assert len(paths) == 1 and re.fullmatch(r'/data/app/[A-Za-z0-9/_=+~.-]+/base\.apk', paths[0])
            actual = adb('shell', 'sha256sum', paths[0]).decode().split()[0]
            assert actual == hashlib.sha256(apk.read_bytes()).hexdigest(), 'Installed APK differs from candidate'
            report['installed_apks'].append({'package': package, 'sha256': actual})
        with (a.output / 'interrupted.log').open('wb') as log:
            process = subprocess.Popen(instrument('holdRealRestoreAtCheckpoint'), stdout=log, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + 35
            while time.monotonic() < deadline:
                assert process.poll() is None, 'Restore ended before checkpoint'
                try:
                    candidate = json.loads(read(relative + '/ready.json'))
                    if candidate.get('id') == case:
                        ready = candidate
                        break
                except (ValueError, UnicodeDecodeError):
                    pass
                time.sleep(0.1)
            assert ready is not None, 'No ready marker'
            pid, uid = int(ready['pid']), int(ready['uid'])
            assert pid > 1 and uid >= 10000
            assert read(f'/proc/{pid}/cmdline').split(b'\0')[0].decode() == PACKAGE
            assert int(adb('shell', 'run-as', PACKAGE, 'id', '-u')) == uid
            assert re.search(r'^Uid:\s+' + str(uid) + r'\s', read(f'/proc/{pid}/status').decode(), re.M)
            verified_pid = pid
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline:
                assert process.poll() is None, 'Restore ended before committed/file barrier'
                try:
                    active = json.loads(read(relative + '/activated.json'))
                except (ValueError, UnicodeDecodeError):
                    active = {}
                committed = read(relative + '/committed.json')
                if active.get('id') == case and (a.checkpoint == 'before' or committed.startswith(b'{')):
                    assert active['normal_uid'] == uid and active['media_files'] == (0 if a.settings_only else 2) and active['style_files'] == (0 if a.settings_only else 2)
                    assert active['font_installed'] == (not a.settings_only)
                    assert active['settings_observed']
                    if a.checkpoint == 'after':
                        assert json.loads(committed)['id'] == case
                    break
                time.sleep(0.05)
            else:
                raise AssertionError('File activation/commit barrier not reached')
            assert read('files/restore-settings-v1.bin').startswith(b'\x00\x00\x00\x01'), 'Missing durable settings journal'
            if not a.settings_only:
                assert read('files/restore-files-v1.bin').startswith(b'\x00\x00\x00\x01'), 'Missing durable file journal'
            report.update({'normal_app_uid': uid, 'killed_pid': pid, 'activated_files_observed': not a.settings_only, 'activated_settings_observed': True,
                           'commit_barrier_observed': a.checkpoint == 'after', 'signal': 'SIGKILL',
                           'signal_sender': terminate(pid)})
            process.wait(timeout=15)
        assert str(pid) not in adb('shell', 'pidof', PACKAGE, check=False).decode().split()
        result = subprocess.run(instrument('verifyAutomaticStartupRecovery'), stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=45)
        (a.output / 'recovery.log').write_bytes(result.stdout)
        text = result.stdout.decode()
        assert 'OK (1 test)' in text and 'INSTRUMENTATION_STATUS_CODE: 0' in text
        assert not re.search(r'INSTRUMENTATION_STATUS_CODE: -[1234]', text)
        state = json.loads(read(relative + '/recovered.json'))
        assert state['id'] == case and state['pid'] != pid and state['automatic_startup']
        assert state['bookChapter'] == (29 if a.checkpoint == 'after' else 7)
        assert (state['lastBackup'] > 1234 if a.checkpoint == 'after' else state['lastBackup'] == 1234)
        assert state['preferences_checked'] and state['data_stores'] == 2 and state['preview_samples'] == 1
        assert state['font_checked'] == (not a.settings_only)
        assert state['font_hash'] == state['font_expected_hash']
        recovered = True
        # All fixture files are under this fresh UUID directory; application test removed live rows/assets.
        adb('shell', 'run-as', PACKAGE, 'rm', '-r', relative)
        report.update({'status': 'passed', 'recovery': state, 'fixture_files_removed': True})
    finally:
        if process is not None and process.poll() is None:
            if verified_pid is not None:
                terminate(verified_pid)
            else:
                adb('shell', 'am', 'force-stop', PACKAGE)
            process.wait(timeout=15)
        if ready is not None and not recovered:
            for name in ('ready.json', 'baseline.json', 'barrier-diag.json'):
                try:
                    content = read(relative + '/' + name)
                    if content:
                        (a.output / ('failed-' + name)).write_bytes(content)
                except (OSError, subprocess.SubprocessError):
                    pass
            try:
                result = subprocess.run(instrument('verifyAutomaticStartupRecovery'), stdout=subprocess.PIPE,
                                        stderr=subprocess.STDOUT, timeout=45)
                (a.output / 'failure-teardown.log').write_bytes(result.stdout)
            except subprocess.SubprocessError as error:
                report['teardown_error'] = str(error)
        if not recovered:
            report['status'] = 'failed'
        (a.output / 'evidence.json').write_text(json.dumps(report, indent=2) + '\n')
    print(f'PASS {a.serial}: SIGKILL {a.checkpoint} commit; automatic startup retained correct persistent state')


if __name__ == '__main__':
    main()
