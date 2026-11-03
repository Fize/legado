#!/usr/bin/env python3
"""Exercise real constrained UIDT downloads, cancellation, reconnect and natural OS retry."""
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
CLASS = 'io.legado.app.ui.book.cache.ModernDownloadJobUiTest'
PERMISSION = 'android.permission.POST_NOTIFICATIONS'
CASES = {
    'normal-granted': ('downloadCompletesUnderTheExplicitNotificationPermissionState', 'granted'),
    'notification-cancel': ('notificationCancellationRejectsTheBlockedResponseAndDoesNotResume', 'granted'),
    'network-resume': ('lostConnectivityStopsAndAutomaticallyResumesWithANewOwner', 'granted'),
    'timeout-resume': ('systemTimeoutAutomaticallyResumesTheSameBatchWithoutAForceRun', 'granted'),
    'normal-denied': ('downloadCompletesUnderTheExplicitNotificationPermissionState', 'denied'),
}


def validate_trace(rows, label):
    if not isinstance(rows, list) or not rows or rows[-1].get('settings_restored') is not True:
        raise ValueError('Missing final fixture restoration proof')
    if any(rows[-1].get(field) is not True for field in ['owned_records_removed', 'owned_managers_removed']):
        raise ValueError('Owned test task and UI records were not cleaned')
    events = {row['event']: row for row in rows if 'event' in row}
    method, permission = CASES[label]
    if not {'ready', 'running', 'cleanup'} <= events.keys(): raise ValueError('Incomplete production UI/job lifecycle')
    for row in events.values():
        if row.get('permission') != permission or row.get('notifications_enabled') is not (permission == 'granted'):
            raise ValueError('Notification permission state differs from requested case')
    first = events['running']
    if first.get('task_phase') != 'running' or first.get('batch_phase') != 'running' or not first.get('task_owner') or first.get('user_initiated') is not True or first.get('validated_network') is not True:
        raise ValueError('Need an actual constrained running UIDT job and owned HTTP request')
    if label == 'notification-cancel':
        stopped = events.get('cancelled_response_rejected', {})
        if stopped.get('task_phase') != 'cancelled' or stopped.get('request_count') != 1 or stopped.get('task_owner') != '' or stopped.get('batch_id') != first['batch_id'] or 'completed' in events:
            raise ValueError('Notification cancel did not reject the held response')
        return {'requests': 1, 'same_batch': True, 'new_owner': False}
    expected_requests = 2 if label in {'network-resume', 'timeout-resume'} else 1
    done = events.get('completed', {})
    if done.get('task_phase') != 'completed' or done.get('batch_phase') != 'completed' or done.get('task_owner') != '' or done.get('request_count') != expected_requests:
        raise ValueError('Job did not complete the expected actual HTTP attempt')
    if expected_requests == 2:
        waiting = events.get('network_lost_waiting' if label == 'network-resume' else 'system_timeout_waiting', {})
        resumed = events.get('resumed', {})
        if waiting.get('task_phase') != 'queued' or waiting.get('batch_phase') != 'waiting' or waiting.get('task_owner') != '':
            raise ValueError('System stop did not persist a resumable batch')
        if resumed.get('task_phase') != 'running' or not resumed.get('task_owner') or resumed['task_owner'] == first['task_owner']:
            raise ValueError('Queued work is not proof of actual re-claim with a new owner')
        for field in ['task_id', 'batch_id', 'process']:
            if not first.get(field) or any(row.get(field) != first[field] for row in [waiting, resumed, done]):
                raise ValueError('Automatic retry changed batch/process identity: ' + field)
        if resumed['request_count'] != 2 or resumed['elapsed_realtime_ms'] <= waiting['elapsed_realtime_ms']:
            raise ValueError('No later real second request')
    return {'requests': expected_requests, 'same_batch': True, 'new_owner': expected_requests == 2,
            'resume_wait_ms': events['resumed']['elapsed_realtime_ms'] - waiting['elapsed_realtime_ms'] if expected_requests == 2 else None}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True); parser.add_argument('--serial', required=True)
    parser.add_argument('--expected-avd', required=True); parser.add_argument('--expected-api', type=int, required=True)
    parser.add_argument('--expected-abi', required=True, choices=['arm64-v8a', 'armeabi-v7a', 'x86_64'])
    parser.add_argument('--case', action='append', choices=list(CASES))
    parser.add_argument('--retry-wait-ms', type=int, default=2100000)
    parser.add_argument('--network-wait-ms', type=int, default=120000)
    parser.add_argument('--cancel-quiet-ms', type=int, default=90000)
    parser.add_argument('--allow-unvalidated-network', action='store_true')
    parser.add_argument('--output', type=Path, required=True); add_apk_arguments(parser)
    args = parser.parse_args(); resolve_apks(parser, args)
    if args.expected_api < 34 or not re.fullmatch(r'emulator-\d+', args.serial) or not 90000 <= args.retry_wait_ms <= 2400000 or not 30000 <= args.network_wait_ms <= 180000 or not 30000 <= args.cancel_quiet_ms <= 180000:
        parser.error('Explicit API34+ emulator and 30s–40min bounded cancellation/retry observations required')
    out = args.output.resolve()
    if out.exists() and any(out.iterdir()): parser.error('Output must be new or empty')
    out.mkdir(parents=True, exist_ok=True); base = [args.adb, '-s', args.serial]
    def run(*words, timeout=120):
        return subprocess.check_output(base + list(words), stderr=subprocess.STDOUT, timeout=timeout)
    def permissions():
        data = run('shell', 'dumpsys', 'package', PACKAGE).decode()
        rows = re.findall(r'android.permission.POST_NOTIFICATIONS: granted=(true|false), flags=\[([^\]]*)\]', data)
        if len(rows) != 1: raise ValueError('Ambiguous runtime notification permission')
        return {'granted': rows[0][0] == 'true', 'flags': rows[0][1]}
    report = {'status': 'running', 'serial': args.serial, 'cases': [], 'installed_apks': [],
              'scope': 'Production cache UI and naturally scheduled UIDT jobs over the app-owned HTTP server; no force-run, scheduling-constant changes or direct Worker/JobService execution',
              'allow_unvalidated_network': args.allow_unvalidated_network}
    previous = None
    try:
        if run('emu', 'avd', 'name').decode().splitlines()[0] != args.expected_avd: raise ValueError('Wrong explicitly owned AVD')
        api = int(run('shell', 'getprop', 'ro.build.version.sdk').decode().strip()); abis = run('shell', 'getprop', 'ro.product.cpu.abilist').decode().strip().split(',')
        validate_target(args.app_apk, api, abis, args.expected_api, args.expected_abi)
        for pkg, apk in [(PACKAGE, args.app_apk), (PACKAGE + '.test', args.test_apk)]:
            if b'Success' not in run('install', '-r', '-t', str(apk)): raise RuntimeError('APK install failed')
            path = run('shell', 'pm', 'path', pkg).decode().strip()
            if not re.fullmatch(r'package:/data/app/[A-Za-z0-9/_=+~.-]+/base\.apk', path): raise ValueError('Unexpected package path')
            actual = run('shell', 'sha256sum', path[8:]).decode().split()[0]
            if actual != hashlib.sha256(apk.read_bytes()).hexdigest(): raise ValueError('Installed APK differs')
            report['installed_apks'].append({'package': pkg, 'sha256': actual})
        previous = permissions(); report['permission_before'] = previous
        report['network_settings_before'] = {key: run('shell', 'settings', 'get', 'global', key).decode().strip() for key in ['wifi_on', 'mobile_data']}
        (out / 'connectivity-before.txt').write_bytes(run('shell', 'dumpsys', 'connectivity'))
        report['source'] = capture(args.adb, args.serial)
        for label in args.case or CASES:
            method, state = CASES[label]
            run('shell', 'pm', 'grant' if state == 'granted' else 'revoke', PACKAGE, PERMISSION)
            if permissions()['granted'] != (state == 'granted'): raise ValueError('Permission mutation failed')
            item = {'case': label, 'method': method, 'permission': state, 'status': 'running'}; report['cases'].append(item)
            print(f'START {label}: real production UIDT', flush=True)
            data = None
            try:
                with (out/(label+'.log')).open('wb') as stream:
                    subprocess.run(base + ['shell','am','instrument','-w','-r','-e','class',CLASS+'#'+method,
                        '-e','compatNotifications',state,'-e','compatRetryWaitMs',str(args.retry_wait_ms),'-e','compatNetworkWaitMs',str(args.network_wait_ms),
                        '-e','compatCancelQuietMs',str(args.cancel_quiet_ms),'-e','compatAllowUnvalidatedNetwork',
                        'yes' if args.allow_unvalidated_network else 'no',
                        PACKAGE+'.test/androidx.test.runner.AndroidJUnitRunner'],stdout=stream,stderr=subprocess.STDOUT,
                        timeout=(args.retry_wait_ms/1000+180 if label=='timeout-resume' else args.network_wait_ms/1000*2+180 if label=='network-resume' else 240),check=True)
            finally:
                trace = subprocess.run(base + ['exec-out','run-as',PACKAGE,'cat','cache/modern-download-'+method+'.json'],capture_output=True,timeout=30)
                if trace.returncode == 0:
                    data = trace.stdout; (out/(label+'-trace.json')).write_bytes(data)
                if label == 'timeout-resume':
                    diagnostic = subprocess.run(base + ['exec-out','run-as',PACKAGE,'cat','cache/modern-download-timeout-jobscheduler.txt'],capture_output=True,timeout=30)
                    if diagnostic.returncode == 0: (out/'timeout-jobscheduler-waiting.txt').write_bytes(diagnostic.stdout)
            validate_instrumentation((out/(label+'.log')).read_text(), 1)
            if data is None: raise ValueError('Missing fresh lifecycle trace')
            item['proof'] = validate_trace(json.loads(data), label); item['status'] = 'passed'
            run('shell','run-as',PACKAGE,'rm','cache/modern-download-'+method+'.json')
            if label == 'timeout-resume': run('shell','run-as',PACKAGE,'rm','cache/modern-download-timeout-jobscheduler.txt')
            print('PASS '+label,flush=True)
        after = capture(args.adb,args.serial)
        if after['source_sha256'] != report['source']['source_sha256'] or after['apks'] != report['source']['apks']: raise ValueError('Source or artifacts changed')
        report['network_settings_after'] = {key: run('shell','settings','get','global',key).decode().strip() for key in ['wifi_on','mobile_data']}
        if report['network_settings_after'] != report['network_settings_before']: raise ValueError('Network settings changed')
        report['status'] = 'passed'
    except Exception as error:
        report.update(status='failed',error=f'{type(error).__name__}: {error}'); raise
    finally:
        if previous is not None:
            try:
                run('shell','pm','grant' if previous['granted'] else 'revoke',PACKAGE,PERMISSION)
                report['permission_after'] = permissions()
                if report['permission_after'] != previous: report['status']='failed'; raise ValueError('Runtime permission/flags did not restore')
                report['permission_restored'] = True
            finally: (out/'evidence.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
        else: (out/'evidence.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')


if __name__ == '__main__': main()
