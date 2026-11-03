#!/usr/bin/env python3
"""Validate real export publication, restore retirement and retained engine/journal contracts."""
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
        return subprocess.check_output(base + list(words), stderr=subprocess.STDOUT, timeout=300)

    report = {'status': 'running', 'tests': 96, 'restore_tests': 14, 'bridge_tests': 4, 'runner_tests': 5,
              'txt_sidecar_tests': 9, 'filedoc_context_tests': 3,
              'saf_runner_tests': 13, 'saf_directory_journal_tests': 5, 'staging_ownership_tests': 7,
              'unbound_staging_intent_tests': 5, 'foreground_service_tests': 3,
              'engine_tests': 13, 'journal_tests': 15, 'serial': args.serial, 'expected_api': args.expected_api,
              'expected_abi': args.expected_abi, 'installed_apks': [],
              'scope': 'Local-file and owned SAF provider export including blocked proxy reads/writes, Room restore, cover HTTP and PUT; per-file publication only, accepted remote writes cannot be rolled back'}
    try:
        api = int(run('shell', 'getprop', 'ro.build.version.sdk').decode().strip())
        abis = run('shell', 'getprop', 'ro.product.cpu.abilist').decode().strip().split(',')
        validate_target(args.app_apk, api, abis, args.expected_api, args.expected_abi)
        for package, apk in [('com.xiaobaitu.reader.debug', args.app_apk),
                             ('com.xiaobaitu.reader.debug.test', args.test_apk)]:
            if b'Success' not in run('install', '-r', '-t', str(apk)):
                raise RuntimeError('APK installation failed')
            paths = [row[8:] for row in run('shell', 'pm', 'path', package).decode().splitlines()
                     if row.endswith('/base.apk')]
            if len(paths) != 1 or not re.fullmatch(r'/data/app/[A-Za-z0-9/_=+~.-]+/base\.apk', paths[0]):
                raise ValueError('Unexpected installed APK path')
            actual = run('shell', 'sha256sum', paths[0]).decode().split()[0]
            if actual != hashlib.sha256(apk.read_bytes()).hexdigest():
                raise ValueError('Installed APK differs from selected artifact')
            report['installed_apks'].append({'package': package, 'sha256': actual})
        report['source'] = capture(args.adb, args.serial)
        data = run('shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                   'io.legado.app.help.storage.RestoreBookExportTest,io.legado.app.help.storage.ExportRestoreBoundaryTest,io.legado.app.help.storage.TxtExportSidecarsTest,io.legado.app.help.storage.FileDocWriteContextTest,io.legado.app.help.storage.SafTxtExportTest,io.legado.app.help.storage.SafExportJournalDirectoryTest,io.legado.app.help.storage.ExportStagingIntentTest,io.legado.app.help.storage.ExportForegroundServiceTest,io.legado.app.service.ExportStagingFilesTest,io.legado.app.service.BookExportTaskRunnerTest,io.legado.app.service.BookExportEngineTest,io.legado.app.service.BookExportJournalTest',
                   'com.xiaobaitu.reader.debug.test/androidx.test.runner.AndroidJUnitRunner')
        (out / 'instrumentation.log').write_bytes(data)
        validate_instrumentation(data.decode(), 96)
        if run('shell', 'run-as', 'com.xiaobaitu.reader.debug', 'ls', 'no_backup/book-export-staging-v1').strip():
            raise RuntimeError('Export staging ownership records remain after tests')
        report['staging_records_empty'] = True
        if run('shell', 'run-as', 'com.xiaobaitu.reader.debug.test', 'ls', 'cache/saf-sidecar-fixtures').strip():
            raise RuntimeError('Owned SAF provider fixture directories remain after tests')
        report['owned_provider_fixture_dirs_empty'] = True
        rejected = run('shell', 'content', 'call', '--uri',
                       'content://com.xiaobaitu.reader.debug.test.sidecar.control',
                       '--method', 'owned_snapshot', '--arg', '00000000-0000-0000-0000-000000000000')
        (out / 'provider-control-shell-denied.log').write_bytes(rejected)
        if b'Only the owned debug target may control fixtures' not in rejected:
            raise RuntimeError('Provider control must reject the shell UID')
        report['provider_control_rejects_shell'] = True
        after = capture(args.adb, args.serial)
        if (after['source_sha256'] != report['source']['source_sha256'] or
                after['apks'] != report['source']['apks']):
            raise RuntimeError('Source or APK identity changed during export checks')
        report['status'] = 'passed'
    except Exception as failure:
        report.update(status='failed', error=f'{type(failure).__name__}: {failure}')
        if isinstance(failure, subprocess.CalledProcessError):
            (out / 'controller-failure.txt').write_bytes(failure.output or b'')
        raise
    finally:
        (out / 'evidence.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(f'PASS API {args.expected_api}: 96 export publication, restore and journal checks')


if __name__ == '__main__':
    main()
