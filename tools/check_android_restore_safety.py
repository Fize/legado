#!/usr/bin/env python3
"""Run fixture-owned restore regressions on an emulator and bind results to installed APKs."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / 'tools'))
from capture_android_compatibility import capture

CLASSES = ['BackupRestoreConcurrencyTest', 'PreferenceSnapshotTest', 'ReaderConfigurationRestoreTest',
           'RestoredConfigurationWriteTest', 'RestoreDatabaseRollbackTest', 'RestoreFailureSafetyTest',
           'RestorePreferencePolicyTest', 'RestorePreferenceTypeTest', 'RestoreFileSafetyTest', 'RestoreSettingsSafetyTest', 'RestoreRecoveryGateTest', 'RestoreAuxiliaryActivityGateTest', 'RestoreAuxiliaryActivityUiTest', 'RestoreAudioWriteGateTest', 'RestoreReadAloudServiceUiTest', 'RestoreVideoWriteGateTest', 'RestoreVideoLoadGateTest', 'RestoreFloatingVideoServiceUiTest']


def validate_instrumentation(text, expected):
    codes = re.findall(r'INSTRUMENTATION_STATUS_CODE: (-?\d+)', text)
    progress = codes.count('2')
    summaries = re.findall(r'^OK \((\d+) (test|tests)\)$', text, re.M)
    expected_summary = (str(expected), 'test' if expected == 1 else 'tests')
    if (expected < 1 or summaries != [expected_summary] or codes.count('0') != expected
            or codes.count('1') != expected or set(codes) - {'0', '1', '2'}
            or progress != len(re.findall(r'^INSTRUMENTATION_STATUS: rollback_fixture_state=', text, re.M))):
        raise RuntimeError('Restore regression failed, was skipped, or emitted unknown progress; inspect instrumentation.log')
    return progress


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--adb', required=True)
    p.add_argument('--serial', required=True)
    p.add_argument('--output', required=True, type=Path)
    scope = p.add_mutually_exclusive_group()
    scope.add_argument('--types-only', action='store_true')
    scope.add_argument('--files-only', action='store_true')
    scope.add_argument('--settings-only', action='store_true')
    scope.add_argument('--gate-only', action='store_true')
    a = p.parse_args()
    if not re.fullmatch(r'emulator-\d+', a.serial):
        p.error('This fixture runner is restricted to emulators')
    out = a.output.resolve()
    if out.exists() and (not out.is_dir() or any(out.iterdir())):
        p.error('--output must be new or empty')
    out.mkdir(parents=True, exist_ok=True)
    base = [a.adb, '-s', a.serial]
    def run(*args):
        return subprocess.check_output(base + list(args), stderr=subprocess.STDOUT, timeout=300)
    expected = 11 if a.types_only else 7 if a.files_only else 8 if a.settings_only else 47 if a.gate_only else 132
    classes = ['RestorePreferenceTypeTest'] if a.types_only else ['RestoreFileSafetyTest'] if a.files_only else ['RestoreSettingsSafetyTest'] if a.settings_only else ['RestoreRecoveryGateTest', 'RestoreAuxiliaryActivityGateTest', 'RestoreAudioWriteGateTest', 'RestoreReadAloudServiceUiTest', 'RestoreVideoWriteGateTest', 'RestoreVideoLoadGateTest', 'RestoreFloatingVideoServiceUiTest'] if a.gate_only else CLASSES
    report = {'status': 'running', 'scope': 'Actual restore, fixture-owned rows and preserved settings',
              'classes': classes, 'expected_tests': expected, 'installed_apks': []}
    try:
        for package, apk in [('com.xiaobaitu.reader.debug', ROOT / 'app/build/outputs/apk/app/debug/app-app-arm64-v8a-debug.apk'),
                             ('com.xiaobaitu.reader.debug.test', ROOT / 'app/build/outputs/apk/androidTest/app/debug/app-app-debug-androidTest.apk')]:
            if b'Success' not in run('install', '-r', '-t', str(apk)):
                raise RuntimeError('APK installation failed')
            paths = [s[8:] for s in run('shell', 'pm', 'path', package).decode().splitlines() if s.endswith('/base.apk')]
            if len(paths) != 1 or not re.fullmatch(r'/data/app/[A-Za-z0-9/_=+~.-]+/base\.apk', paths[0]):
                raise RuntimeError('Unexpected installed APK path')
            actual = run('shell', 'sha256sum', paths[0]).decode().split()[0]
            if actual != hashlib.sha256(apk.read_bytes()).hexdigest():
                raise RuntimeError('Installed APK does not match local artifact')
            report['installed_apks'].append({'package': package, 'sha256': actual})
        report['source'] = capture(a.adb, a.serial)
        data = run('shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                   ','.join('io.legado.app.help.storage.' + name for name in classes),
                   'com.xiaobaitu.reader.debug.test/androidx.test.runner.AndroidJUnitRunner')
        (out / 'instrumentation.log').write_bytes(data)
        text = data.decode()
        report['database_diagnostic_events'] = validate_instrumentation(text, expected)
        report['status'] = 'passed'
        report['passed_tests'] = expected
        report['log_sha256'] = hashlib.sha256(data).hexdigest()
    except Exception as error:
        report['status'] = 'failed'
        report['error'] = f'{type(error).__name__}: {error}'
        raise
    finally:
        (out / 'evidence.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(f'PASS {a.serial}: {expected} actual restore tests; installed APK identities verified')


if __name__ == '__main__':
    main()
