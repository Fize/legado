#!/usr/bin/env python3
"""Verify actual Android dataSync timeout, safe interruption and explicit foreground retry."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time
import uuid

from android_device_checks import add_apk_arguments, resolve_apks
from capture_android_compatibility import capture
from check_android_device_ci import validate_target
from check_android_restore_safety import validate_instrumentation

PACKAGE = 'com.xiaobaitu.reader.debug'
CLASS = 'io.legado.app.help.storage.ExportForegroundTimeoutProbe'
MODES = ['hold', 'hold_read', 'hold_main_create_before', 'hold_main_create_after',
         'hold_image_create_before', 'hold_image_create_after', 'hold_after_backup',
         'hold_before_activation', 'hold_after_activation']
KEY = 'data_sync_fgs_timeout_duration'


def validate_detached(before, after, pid):
    for value in [before, after]:
        if 'ACTIVITY MANAGER' not in value or 'Unknown command' in value or 'Bad activity command' in value:
            raise ValueError('Unsupported instrumentation-state query')
    if 'Active instrumentation:' not in before or 'Active instrumentation:' in after or f'{pid}:' not in after:
        raise ValueError('Need positive instrumented control and a live detached process')


def validate_timeout(result, log, case_id, mode):
    if result.get('id') != case_id or result.get('mode') != mode:
        raise ValueError('Timeout result belongs to another case')
    pid = result.get('pid'); provider = result.get('provider_pid'); token = result.get('service_token')
    if type(pid) is not int or pid <= 0 or type(provider) is not int or provider <= 0 or provider == pid or not re.fullmatch('[0-9a-f]+', token or ''):
        raise ValueError('Missing actual service/process identity')
    begin = f'BEGIN:{case_id}:{pid}'; end = f'END:{case_id}:{pid}'
    if log.count(begin) != 1 or log.count(end) != 1 or log.index(end) <= log.index(begin):
        raise ValueError('Missing bounded same-process platform log window')
    window = log[log.index(begin):log.index(end)]
    pattern = r'FGS \(dataSync\) timed out: ServiceRecord\{' + re.escape(token) + r'\b[^\n]*BookExportForegroundService'
    if len(re.findall(pattern, window)) != 1:
        raise ValueError('OS must time out this exact service instance')
    for field in ['service_foreground_before', 'service_gone_while_io_blocked', 'old_task_interrupted',
                  'old_owner_cleared', 'original_preserved_before_retry', 'foreground_return_before_retry',
                  'retry_completed', 'service_stopped_after_retry', 'unrelated_preserved']:
        if result.get(field) is not True: raise ValueError('Missing timeout proof: ' + field)
    if result.get('fragments_after_retry') != 0:
        raise ValueError('Uncommitted fragments remain')
    if any(type(result.get(field)) is not int for field in ['timeout_observed_elapsed_ms', 'background_elapsed_ms', 'fragments_after_retry']):
        raise ValueError('Elapsed time and fragment accounting must be integers')
    elapsed = result['timeout_observed_elapsed_ms'] - result['background_elapsed_ms']
    if not 3000 <= elapsed < 15000:
        raise ValueError('Timeout observation outside the configured 5 second OS allowance')
    for field in ['batch', 'dispatch']:
        if result.get('new_' + field) == result.get('old_' + field) or not result.get('new_' + field):
            raise ValueError('Retry reused old ownership')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True); parser.add_argument('--serial', required=True)
    parser.add_argument('--expected-avd', required=True); parser.add_argument('--expected-api', type=int, required=True)
    parser.add_argument('--expected-abi', required=True, choices=['arm64-v8a', 'armeabi-v7a', 'x86_64'])
    parser.add_argument('--mode', action='append', choices=MODES)
    parser.add_argument('--output', type=Path, required=True); add_apk_arguments(parser)
    args = parser.parse_args(); resolve_apks(parser, args)
    if not re.fullmatch(r'emulator-\d+', args.serial) or args.expected_api < 35:
        parser.error('Only an explicitly selected API35+ emulator is supported')
    out = args.output.resolve()
    if out.exists() and any(out.iterdir()): parser.error('Output must be new or empty')
    out.mkdir(parents=True, exist_ok=True); base = [args.adb, '-s', args.serial]
    def run(*words, timeout=120):
        return subprocess.check_output(base + list(words), stderr=subprocess.STDOUT, timeout=timeout)
    report = {'status': 'running', 'serial': args.serial, 'cases': [], 'installed_apks': [], 'configured_timeout_ms': 5000,
              'scope': 'Real OS dataSync timeout after detaching instrumentation, temporary 5000ms allowance, production service and owned provider; no direct onTimeout invocation or compat override'}
    previous = None
    try:
        if run('emu', 'avd', 'name').decode().splitlines()[0] != args.expected_avd: raise ValueError('Wrong owned AVD')
        api = int(run('shell', 'getprop', 'ro.build.version.sdk').decode().strip()); abis = run('shell', 'getprop', 'ro.product.cpu.abilist').decode().strip().split(',')
        validate_target(args.app_apk, api, abis, args.expected_api, args.expected_abi)
        for pkg, apk in [(PACKAGE, args.app_apk), (PACKAGE + '.test', args.test_apk)]:
            if b'Success' not in run('install', '-r', '-t', str(apk)): raise RuntimeError('Install failed')
            path = run('shell', 'pm', 'path', pkg).decode().strip()
            if not re.fullmatch(r'package:/data/app/[A-Za-z0-9/_=+~.-]+/base\.apk', path): raise ValueError('Unexpected installed APK path')
            installed = run('shell', 'sha256sum', path[8:]).decode().split()[0]
            if installed != hashlib.sha256(apk.read_bytes()).hexdigest(): raise ValueError('Installed artifact differs')
            report['installed_apks'].append({'package': pkg, 'sha256': installed})
        report['source'] = capture(args.adb, args.serial)
        previous = run('shell', 'device_config', 'get', 'activity_manager', KEY).decode().strip()
        report['timeout_config_before'] = previous
        run('shell', 'device_config', 'put', 'activity_manager', KEY, '5000')
        if run('shell', 'device_config', 'get', 'activity_manager', KEY).decode().strip() != '5000': raise ValueError('Timeout config not applied')
        for mode in args.mode or MODES:
            case = str(uuid.uuid4()); item = {'mode': mode, 'id': case, 'status': 'running'}; report['cases'].append(item)
            def probe(method, stage=None, background_at=None):
                words = ['shell', 'am', 'instrument', '-w', '-r']
                if stage: words += ['--no-restart', '-e', 'finishActivities', 'false', '-e', 'stage', stage]
                if background_at is not None: words += ['-e', 'backgroundAt', str(background_at)]
                return run(*words, '-e', 'class', CLASS + '#' + method,
                    '-e', 'exportTimeout', 'yes', '-e', 'caseId', case, '-e', 'mode', mode,
                    PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner')
            try:
                run('shell', 'am', 'start', '-W', '-n', PACKAGE + '/io.legado.app.ui.AppActivity')
                text = probe('actualSystemTimeoutAndExplicitRetry', stage='prepare')
                (out/(mode+'-prepare.log')).write_bytes(text); validate_instrumentation(text.decode(), 1)
                prepared = json.loads(run('exec-out', 'run-as', PACKAGE, 'cat', f'files/compat-export-timeout/{case}.prepared.json').decode())
                item['prepared'] = prepared
                live = run('shell', 'pidof', PACKAGE).decode().split()
                if str(prepared['pid']) not in live: raise ValueError('Preparation must detach without killing the application')
                before_instrumentation = run('exec-out', 'run-as', PACKAGE, 'cat', f'files/compat-export-timeout/{case}.instrumented.txt').decode()
                (out/(mode+'-instrumented-control.txt')).write_text(before_instrumentation)
                instrumentation = run('shell', 'dumpsys', 'activity', '-p', PACKAGE)
                (out/(mode+'-detached-instrumentation.txt')).write_bytes(instrumentation)
                validate_detached(before_instrumentation, instrumentation.decode(), prepared['pid'])
                item['not_instrumented_during_timer'] = True
                background_at = int(float(run('shell', 'cat', '/proc/uptime').decode().split()[0]) * 1000)
                run('shell', 'input', 'keyevent', 'KEYCODE_HOME')
                uid_state = run('shell', 'cmd', 'activity', 'get-uid-state', str(prepared['uid'])).decode().strip()
                item['uid_state_after_home'] = uid_state
                deadline = time.monotonic() + 12
                token = re.escape(prepared['service_token'])
                pattern = r'FGS \(dataSync\) timed out: ServiceRecord\{' + token + r'\b[^\n]*BookExportForegroundService'
                while True:
                    platform = run('shell', 'logcat', '-d', '-v', 'threadtime', '-s', 'ActivityManager:V', 'LegadoExportTimeout:I', 'AndroidRuntime:E').decode(errors='replace')
                    if re.search(pattern, platform): break
                    if time.monotonic() >= deadline: raise TimeoutError('No OS timeout for detached service')
                    time.sleep(.1)
                (out/(mode+'-detached-platform.log')).write_text(platform)
                if str(prepared['pid']) not in run('shell', 'pidof', PACKAGE).decode().split(): raise ValueError('Timeout crashed the app')
                item['same_pid_after_timeout'] = True; item['platform_timeout_before_reattach'] = True
                text = probe('actualSystemTimeoutAndExplicitRetry', stage='complete', background_at=background_at)
                (out/(mode+'.log')).write_bytes(text)
                platform = run('shell', 'logcat', '-d', '-v', 'threadtime', '-s', 'ActivityManager:V', 'LegadoExportTimeout:I', 'AndroidRuntime:E').decode(errors='replace')
                (out/(mode+'-platform.log')).write_text(platform)
                validate_instrumentation(text.decode(), 1)
                result = json.loads(run('exec-out', 'run-as', PACKAGE, 'cat', f'files/compat-export-timeout/{case}.json').decode()); item['result'] = result
                validate_timeout(result, platform, case, mode)
                records = {}
                for path in ['no_backup/book-export-staging-v1', 'files/book-export-journal']:
                    value = run('shell', 'run-as', PACKAGE, 'ls', path).decode().strip()
                    records[path] = value
                    if value: raise ValueError('Product records remain before fixture cleanup: ' + path)
                (out/(mode+'-records-before-cleanup.json')).write_text(json.dumps(records,indent=2)+'\n')
                item['ownership_and_publication_records_empty_before_cleanup'] = True
                item['status'] = 'passed'
            finally:
                diagnostic = subprocess.run(base + ['exec-out', 'run-as', PACKAGE, 'cat', f'files/compat-export-timeout/{case}.services.txt'], capture_output=True)
                if diagnostic.returncode == 0: (out/(mode+'-services-before.txt')).write_bytes(diagnostic.stdout)
                text = probe('cleanupOwnedFixtureAfterHostFailure'); (out/(mode+'-cleanup.log')).write_bytes(text); validate_instrumentation(text.decode(), 1); item['cleanup_passed'] = True
            print(mode + ': actual system timeout, interruption and explicit retry passed', flush=True)
        after = capture(args.adb, args.serial)
        if after['source_sha256'] != report['source']['source_sha256'] or after['apks'] != report['source']['apks']: raise ValueError('Source or artifacts changed')
        for pkg, path in [(PACKAGE, 'no_backup/book-export-staging-v1'), (PACKAGE, 'files/book-export-journal'), (PACKAGE + '.test', 'cache/saf-sidecar-fixtures')]:
            if run('shell', 'run-as', pkg, 'ls', path).strip(): raise ValueError('Fixture/export records remain: ' + path)
        report['status'] = 'passed'
    except Exception as error:
        report.update(status='failed', error=f'{type(error).__name__}: {error}'); raise
    finally:
        if previous is not None:
            try:
                if previous == 'null': run('shell', 'device_config', 'delete', 'activity_manager', KEY)
                else: run('shell', 'device_config', 'put', 'activity_manager', KEY, previous)
                actual = run('shell', 'device_config', 'get', 'activity_manager', KEY).decode().strip()
                report['timeout_config_after'] = actual; report['timeout_config_restored'] = actual == previous
                if actual != previous: report['status'] = 'failed'; raise RuntimeError('Timeout setting restoration failed')
            finally: (out/'evidence.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
        else: (out/'evidence.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')


if __name__ == '__main__': main()
