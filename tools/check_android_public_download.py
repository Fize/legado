#!/usr/bin/env python3
"""Run real public-file downloads, restoring any temporary legacy storage permission."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

from android_device_checks import add_apk_arguments, resolve_apks
from capture_android_compatibility import capture
from check_android_device_ci import validate_target
from check_android_restore_safety import validate_instrumentation

PACKAGE = 'com.xiaobaitu.reader.debug'
PERMISSION = 'android.permission.WRITE_EXTERNAL_STORAGE'


def storage_permission(text):
    matches = re.findall(r'^\s*android\.permission\.WRITE_EXTERNAL_STORAGE: granted=(true|false)(?:,.*)?$', text, re.M)
    if not matches:
        # Android 8 omits never-created runtime permission states. Accept that form
        # only for a complete, installed single-user package dump with the request
        # and its runtime-permissions section present; truncated/request-only output fails.
        users = re.findall(r'^\s*User (\d+):.*\binstalled=true\b.*$', text, re.M)
        if (users == ['0'] and len(re.findall(r'^\s*runtime permissions:\s*$', text, re.M)) == 1
                and len(re.findall(r'^\s*android\.permission\.WRITE_EXTERNAL_STORAGE\s*$', text, re.M)) == 1):
            return False
    if len(matches) != 1:
        raise ValueError('Expected exactly one legacy runtime storage permission state')
    return matches[0] == 'true'


def default_network(text):
    matches = re.findall(r'^Active default network: (\d+|none)\s*$', text, re.M)
    if len(matches) != 1 or matches[0] == 'none':
        raise ValueError('A real connected default network is required; do not force the download job')
    return int(matches[0])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--expected-api', required=True, type=int)
    parser.add_argument('--expected-abi', required=True, choices=['arm64-v8a', 'armeabi-v7a', 'x86_64'])
    parser.add_argument('--output', required=True, type=Path)
    add_apk_arguments(parser)
    args = parser.parse_args()
    resolve_apks(parser, args)
    if not re.fullmatch(r'emulator-\d+', args.serial):
        parser.error('Only explicitly selected emulators are supported')
    out = args.output.resolve()
    if out.exists() and any(out.iterdir()):
        parser.error('Output must be new or empty')
    out.mkdir(parents=True, exist_ok=True)
    base = [args.adb, '-s', args.serial]

    def run(*words):
        return subprocess.check_output(base + list(words), stderr=subprocess.STDOUT, timeout=240)

    def permission():
        return storage_permission(run('shell', 'dumpsys', 'package', PACKAGE).decode())

    report = {'status': 'running', 'tests': 9, 'serial': args.serial,
              'expected_api': args.expected_api, 'expected_abi': args.expected_abi,
              'scope': 'Real DownloadManager files, recovery guards and automatic open; no user files removed',
              'installed_apks': [], 'checks_passed': False, 'legacy_storage_permission_restored': False}
    before_permission = None
    grant_attempted = False
    instrumentation_started = False
    instrumentation_finished = False
    try:
        api = int(run('shell', 'getprop', 'ro.build.version.sdk').decode().strip())
        abis = run('shell', 'getprop', 'ro.product.cpu.abilist').decode().strip().split(',')
        validate_target(args.app_apk, api, abis, args.expected_api, args.expected_abi)
        for package, apk in [(PACKAGE, args.app_apk), (PACKAGE + '.test', args.test_apk)]:
            if b'Success' not in run('install', '-r', '-t', str(apk)):
                raise RuntimeError('APK installation failed')
            paths = [row[8:] for row in run('shell', 'pm', 'path', package).decode().splitlines()
                     if row.endswith('/base.apk')]
            if len(paths) != 1 or not re.fullmatch(r'/data/app/[A-Za-z0-9/_=+~.-]+/base\.apk', paths[0]):
                raise ValueError('Unexpected installed APK path')
            actual = run('shell', 'sha256sum', paths[0]).decode().split()[0]
            if actual != hashlib.sha256(apk.read_bytes()).hexdigest():
                raise ValueError('Installed APK does not match the selected artifact')
            report['installed_apks'].append({'package': package, 'sha256': actual})
        connectivity = run('shell', 'dumpsys', 'connectivity').decode()
        (out / 'connectivity-before.txt').write_text(connectivity)
        report['default_network'] = default_network(connectivity)
        report['source'] = capture(args.adb, args.serial)
        if api <= 28:
            before_permission = permission()
            report['legacy_storage_permission_before'] = before_permission
            if not before_permission:
                grant_attempted = True
                run('shell', 'pm', 'grant', PACKAGE, PERMISSION)
                if not permission():
                    raise RuntimeError('Legacy storage permission was not granted')
        instrumentation_started = True
        data = run('shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                   'io.legado.app.help.storage.RestorePublicDownloadTest',
                   PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner')
        instrumentation_finished = True
        (out / 'instrumentation.log').write_bytes(data)
        validate_instrumentation(data.decode(), 9)
        after = capture(args.adb, args.serial)
        if (after['source_sha256'] != report['source']['source_sha256'] or
                after['apks'] != report['source']['apks']):
            raise RuntimeError('Source or APK identity changed during public download checks')
        report['checks_passed'] = True
    except Exception as failure:
        report.update(status='failed', error=f'{type(failure).__name__}: {failure}')
        if isinstance(failure, subprocess.CalledProcessError):
            (out / 'controller-failure.txt').write_bytes(failure.output or b'')
        raise
    finally:
        try:
            # Revoke only after instrumentation has exited: Android can kill its process.
            if grant_attempted and instrumentation_started and not instrumentation_finished:
                active = run('shell', 'dumpsys', 'activity', 'instrumentation').decode()
                (out / 'instrumentation-after-controller-error.txt').write_text(active)
                if PACKAGE + '.test' in active:
                    raise RuntimeError('Instrumentation remains active; do not revoke its permission before fixture cleanup')
            if grant_attempted and permission() != before_permission:
                run('shell', 'pm', 'revoke', PACKAGE, PERMISSION)
            if before_permission is not None and permission() != before_permission:
                raise RuntimeError('Legacy storage permission did not restore')
            report['legacy_storage_permission_restored'] = True
            if report['checks_passed']:
                report['status'] = 'passed'
        except Exception as failure:
            report.update(status='failed', cleanup_error=f'{type(failure).__name__}: {failure}')
            raise
        finally:
            (out / 'evidence.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(f'PASS API {args.expected_api}: 9 real public-download checks; legacy permission restored')


if __name__ == '__main__':
    main()
