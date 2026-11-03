#!/usr/bin/env python3
"""Exercise export SIGKILL or platform user stop during owned SAF operations; require clean retry."""
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
CLASS = 'io.legado.app.help.storage.SafExportDeathProbe'
CASES = {
    'copy': ('hold', 'restore'),
    'read': ('hold_read', 'restore'),
    'backup-restore': ('hold_after_backup', 'restore'),
    'backup-keep': ('hold_after_backup', 'keep'),
    'activation-pending-restore': ('hold_before_activation', 'restore'),
    'activation-pending-keep': ('hold_before_activation', 'keep'),
    'activation-reply-restore': ('hold_after_activation', 'restore'),
    'activation-reply-keep': ('hold_after_activation', 'keep'),
    'main-create-before': ('hold_main_create_before', 'restore'),
    'main-create-after': ('hold_main_create_after', 'restore'),
    'image-create-before': ('hold_image_create_before', 'restore'),
    'image-create-after': ('hold_image_create_after', 'restore'),
    'main-create-late': ('hold_main_create_before', 'restore'),
    'image-create-late': ('hold_image_create_before', 'restore'),
}
CASES.update({'service-' + name: value for name, value in list(CASES.items())})
CREATION_MODES = {mode for mode, _ in CASES.values() if '_create_' in mode}
LATE_CASES = {'main-create-late', 'image-create-late', 'service-main-create-late', 'service-image-create-late'}
SERVICE_COMPONENT = 'io.legado.app.service.BookExportForegroundService'


def validate_service(proof, pid):
    if (not isinstance(proof, dict) or type(proof.get('pid')) is not int or proof['pid'] != pid or
            proof.get('foreground') is not True or proof.get('component') != SERVICE_COMPONENT):
        raise ValueError('An actual foreground export service in the target PID is required')
    for field in ['batch', 'dispatch']:
        try:
            if str(uuid.UUID(proof[field])) != proof[field]:
                raise ValueError('Noncanonical service ownership')
        except (ValueError, KeyError, TypeError, AttributeError) as error:
            raise ValueError('Missing exact service ownership: ' + field) from error


def validate_death(marker, mode, log, live_pids, foreground_service=False, termination='sigkill'):
    if not re.search(r'process (?:crashed|crash)', log, re.I):
        raise ValueError('Expected an actual crashed instrumentation process')
    if marker.get('mode') != mode or type(marker.get('pid')) is not int or marker['pid'] <= 0:
        raise ValueError('Death marker does not identify the requested operation')
    if str(marker['pid']) in live_pids:
        raise ValueError('Killed application PID is still alive')
    if marker.get('termination', 'sigkill') != termination:
        raise ValueError('Process termination does not match the requested mechanism')
    if termination == 'user-stop' and not foreground_service:
        raise ValueError('User stop requires an actual foreground service')
    if type(marker.get('provider_pid')) is not int or marker['provider_pid'] <= 0 or marker['provider_pid'] == marker['pid']:
        raise ValueError('Provider must survive in a separate process')
    if marker.get('persisted_grant') is not True:
        raise ValueError('A persistent selected-tree grant is required for the death fixture')
    if marker.get('foreground_service', False) is not foreground_service:
        raise ValueError('Requested foreground service was not exercised')
    if foreground_service:
        validate_service(marker.get('service'), marker['pid'])
        try:
            owner = marker['service']['task_owner']
            if str(uuid.UUID(owner)) != owner: raise ValueError('Invalid owner')
        except (ValueError, KeyError, TypeError, AttributeError) as error:
            raise ValueError('Service must own a running task at death') from error
    if mode in CREATION_MODES:
        after = mode.endswith('after')
        if (marker.get('operation') != ('create_after' if after else 'create_before') or
                marker.get('active_creates') != 1 or marker.get('active_descriptors') != 0 or marker.get('active_renames') != 0 or
                marker.get('journal_records') != 0 or marker.get('unbound_intents') != 1 or
                marker.get('staging_records') != (1 if mode.startswith('hold_main') else 2) or
                marker.get('creation_visible') is not after):
            raise ValueError('Creation death needs a durable unbound intent and the exact provider creation effect')
    elif mode in {'hold', 'hold_read'}:
        if marker.get('operation') != ('write' if mode == 'hold' else 'read') or marker.get('active_descriptors', 0) < 1 or marker.get('journal_records') != 0:
            raise ValueError('Copy/read death must occur inside an uncommitted proxy operation')
    elif marker.get('operation') not in {'rename_before', 'rename_after'} or marker.get('active_renames') != 1 or marker.get('journal_records') != 1:
        raise ValueError('Rename death must have one active provider operation and one durable image journal')
    elif marker.get('journal_phase') != ('prepared' if mode == 'hold_after_backup' else 'backed_up'):
        raise ValueError('Durable journal does not match the blocked rename boundary')


def validate_result(result, marker, action, late_create=False, foreground_service=False, termination='sigkill'):
    if result.get('id') != marker.get('id') or result.get('mode') != marker.get('mode') or result.get('action') != action:
        raise ValueError('Result belongs to a different case')
    if type(result.get('pid')) is not int or result['pid'] <= 0 or result['pid'] in {marker['pid'], marker['provider_pid']} or result.get('provider_pid') != marker['provider_pid']:
        raise ValueError('Recovery needs a new target PID and the same surviving provider')
    for field in ['old_dispatch_rejected', 'retry_completed', 'unrelated_preserved', 'persisted_grant']:
        if result.get(field) is not True:
            raise ValueError('Missing recovery proof: ' + field)
    if result.get('journal_records_recovered') != marker['journal_records']:
        raise ValueError('Image journal recovery count changed')
    if result.get('termination', 'sigkill') != termination or marker.get('termination', 'sigkill') != termination:
        raise ValueError('Recovery belongs to a different termination mechanism')
    if termination == 'user-stop':
        exit_info = result.get('exit_info')
        if (not foreground_service or not isinstance(exit_info, dict) or
                exit_info.get('pid') != marker['pid'] or type(exit_info.get('reason')) is not int or
                exit_info['reason'] != 10 or exit_info.get('process') != PACKAGE or
                type(exit_info.get('timestamp')) is not int or type(marker.get('wall_time_ms')) is not int or
                exit_info['timestamp'] < marker['wall_time_ms']):
            raise ValueError('System user stop needs matching REASON_USER_REQUESTED exit history')
    if result.get('foreground_service', False) is not foreground_service:
        raise ValueError('Requested service retry was not exercised')
    if foreground_service:
        validate_service(result.get('retry_service'), result['pid'])
        for field in ['cache_entry_recovered', 'service_stopped_after_retry']:
            if result.get(field) is not True:
                raise ValueError('Missing service lifecycle proof: ' + field)
        if result.get('recovery_service_used') is not (marker['journal_records'] > 0):
            raise ValueError('Pending journal must be recovered by the real service')
        for field in ['batch', 'dispatch']:
            if result['retry_service'][field] == marker['service'][field]:
                raise ValueError('Service retry reused an old dispatch or batch')
    if result.get('late_create', False) is not late_create or marker.get('late_create', False) is not late_create:
        raise ValueError('Late creation scenario was not exercised as requested')
    if late_create:
        if marker.get('mode') not in {'hold_main_create_before', 'hold_image_create_before'} or marker.get('creation_visible') is not False:
            raise ValueError('A late creation must still be absent when its original process dies')
        for field in ['late_intent_retained', 'late_file_observed', 'provider_active_during_first_retry', 'followup_export_completed']:
            if result.get(field) is not True:
                raise ValueError('Missing late-creation proof: ' + field)
    before = result.get('fragments_before_retry')
    after = result.get('fragments_after_retry')
    if not isinstance(before, dict) or not isinstance(after, dict):
        raise ValueError('Missing uncommitted-fragment accounting')
    if not set(after).issubset(before) or any(after[name] != before[name] for name in after):
        raise ValueError('Retry created or rewrote unexplained fragments')
    return len(after)


def main():
    if not __debug__:
        raise SystemExit('Run without -O: this process-killing harness requires assertions')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--expected-avd', required=True)
    parser.add_argument('--expected-api', required=True, type=int)
    parser.add_argument('--expected-abi', required=True, choices=['arm64-v8a', 'armeabi-v7a', 'x86_64'])
    parser.add_argument('--scenario', action='append', choices=list(CASES))
    parser.add_argument('--termination', choices=['sigkill', 'user-stop'], default='sigkill')
    parser.add_argument('--output', required=True, type=Path)
    add_apk_arguments(parser)
    args = parser.parse_args(); resolve_apks(parser, args)
    selected = args.scenario or [name for name in CASES if args.termination != 'user-stop' or name.startswith('service-')]
    if args.termination == 'user-stop' and (args.expected_api < 33 or any(not n.startswith('service-') for n in selected)):
        parser.error('System user-stop tests require API33+ and actual service scenarios')
    if not re.fullmatch(r'emulator-\d+', args.serial):
        parser.error('Only an explicitly selected emulator is supported')
    output = args.output.resolve()
    if output.exists() and any(output.iterdir()):
        parser.error('Output must be new or empty')
    output.mkdir(parents=True, exist_ok=True)
    base = [args.adb, '-s', args.serial]

    def run(*words, timeout=120):
        return subprocess.run(base + list(words), stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)

    def checked(*words):
        result = run(*words)
        if result.returncode:
            raise RuntimeError(result.stdout.decode(errors='replace'))
        return result.stdout

    report = {'status': 'running', 'serial': args.serial, 'scenarios': [], 'termination': args.termination,
              'scope': 'Actual target termination via ' + args.termination + ' during owned separate-provider operations. Runner cases use a dedicated persistent task DB; service cases use the real scheduler/Hilt foreground service/app DB/cache UI recovery and explicit service retry. Not OS timeout, manual task-manager UI or JobScheduler certification.'}
    try:
        name = checked('emu', 'avd', 'name').decode().splitlines()[0]
        if name != args.expected_avd:
            raise ValueError('Selected AVD differs from explicit expectation')
        api = int(checked('shell', 'getprop', 'ro.build.version.sdk').decode().strip())
        abis = checked('shell', 'getprop', 'ro.product.cpu.abilist').decode().strip().split(',')
        validate_target(args.app_apk, api, abis, args.expected_api, args.expected_abi)
        report['installed_apks'] = []
        for package, apk in [(PACKAGE, args.app_apk), (PACKAGE + '.test', args.test_apk)]:
            if b'Success' not in checked('install', '-r', '-t', str(apk)):
                raise RuntimeError('Installation failed')
            paths = [row[8:] for row in checked('shell', 'pm', 'path', package).decode().splitlines() if row.endswith('/base.apk')]
            if len(paths) != 1 or not re.fullmatch(r'/data/app/[A-Za-z0-9/_=+~.-]+/base\.apk', paths[0]):
                raise ValueError('Unexpected installed APK path')
            digest = checked('shell', 'sha256sum', paths[0]).decode().split()[0]
            if digest != hashlib.sha256(apk.read_bytes()).hexdigest():
                raise ValueError('Installed APK does not match selected artifact')
            report['installed_apks'].append({'package': package, 'sha256': digest})
        report['source'] = capture(args.adb, args.serial)
        for label in selected:
            mode, action = CASES[label]; case_id = str(uuid.uuid4())
            item = {'scenario': label, 'case_id': case_id, 'mode': mode, 'action': action, 'status': 'running'}
            service_case = label.startswith('service-')
            item['foreground_service'] = service_case
            report['scenarios'].append(item)

            def probe(method):
                words = ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', CLASS + '#' + method,
                             '-e', 'safExportDeath', 'yes', '-e', 'caseId', case_id,
                             '-e', 'mode', mode, '-e', 'action', action,
                             '-e', 'lateCreate', 'yes' if label in LATE_CASES else 'no',
                             '-e', 'foregroundService', 'yes' if service_case else 'no',
                             '-e', 'termination', args.termination,
                             PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner']
                if args.termination == 'user-stop' and method == 'exportAndKillWhileProviderIsBlocked':
                    path = output / (label + '-' + method + '.log')
                    with path.open('wb') as stream:
                        process = subprocess.Popen(base + words, stdout=stream, stderr=subprocess.STDOUT)
                        try:
                            deadline = time.monotonic() + 45
                            while True:
                                if process.poll() is not None:
                                    raise RuntimeError('Probe ended before the platform user-stop command')
                                prefix = 'files/compat-saf-export-death/' + case_id + '/'
                                ready = run('shell', 'run-as', PACKAGE, 'ls', prefix + 'marker.ready', timeout=5)
                                if ready.returncode == 0:
                                    marker = json.loads(checked('exec-out', 'run-as', PACKAGE, 'cat', prefix + 'marker.json').decode())
                                    if marker.get('id') != case_id or marker.get('termination') != 'user-stop':
                                        raise ValueError('Wrong platform-stop marker')
                                    validate_service(marker.get('service'), marker['pid'])
                                    live = checked('shell', 'pidof', PACKAGE).decode().split()
                                    if str(marker['pid']) not in live:
                                        raise ValueError('Target must still be alive before system user stop')
                                    break
                                if time.monotonic() >= deadline:
                                    raise TimeoutError('No durable blocked-service marker')
                                time.sleep(.1)
                            services = checked('shell', 'dumpsys', 'activity', 'services', PACKAGE)
                            (output / (label + '-services-before-stop.txt')).write_bytes(services)
                            if SERVICE_COMPONENT.rsplit('.', 1)[1].encode() not in services:
                                raise ValueError('Actual service absent before user-stop command')
                            reply = checked('shell', 'cmd', 'activity', 'stop-app', PACKAGE)
                            item['system_stop_command'] = {'status': 'completed', 'pid_before': marker['pid'], 'reply': reply.decode()}
                            process.wait(timeout=35)
                        finally:
                            if process.poll() is None:
                                process.terminate()  # adb client only; cleanup remains a separate owned probe.
                                process.wait(timeout=5)
                    return path.read_text()
                result = run(*words)
                (output / (label + '-' + method + '.log')).write_bytes(result.stdout)
                return result.stdout.decode(errors='replace')

            def read(name):
                return json.loads(checked('exec-out', 'run-as', PACKAGE, 'cat',
                                         'files/compat-saf-export-death/' + case_id + '/' + name).decode())

            try:
                death_log = probe('exportAndKillWhileProviderIsBlocked')
                marker = read('marker.json'); item['death'] = marker
                if marker.get('id') != case_id:
                    raise ValueError('Death marker belongs to another fixture')
                live = run('shell', 'pidof', PACKAGE).stdout.decode().split()
                validate_death(marker, mode, death_log, live, foreground_service=service_case, termination=args.termination)
                text = probe('reopenRecoverAndRetryInANewProcess')
                if label in LATE_CASES:
                    observation = run('exec-out', 'run-as', PACKAGE, 'cat', 'files/compat-saf-export-death/' + case_id + '/late-observation.json')
                    if observation.returncode == 0: item['late_observation'] = json.loads(observation.stdout.decode())
                validate_instrumentation(text, 1)
                result = read('result.json'); item['result'] = result
                item['remaining_fragments'] = validate_result(result, marker, action, late_create=label in LATE_CASES, foreground_service=service_case, termination=args.termination)
                item['status'] = 'unresolved_fragments' if item['remaining_fragments'] else 'passed'
            finally:
                cleanup = probe('cleanupOwnedFixture'); validate_instrumentation(cleanup, 1)
                item['owned_fixture_cleaned'] = True
            print(f'{label}: {item["status"]}; {item["remaining_fragments"]} retry fragments; fixture cleaned', flush=True)
        after = capture(args.adb, args.serial)
        if after['source_sha256'] != report['source']['source_sha256'] or after['apks'] != report['source']['apks']:
            raise RuntimeError('Source or APK identity changed during death checks')
        if checked('shell', 'run-as', PACKAGE + '.test', 'ls', 'cache/saf-sidecar-fixtures').strip():
            raise RuntimeError('Provider fixture directories remain')
        report['provider_fixture_dirs_empty'] = True
        report['status'] = 'unresolved_fragments' if any(i['remaining_fragments'] for i in report['scenarios']) else 'passed'
    except Exception as error:
        report.update(status='failed', error=f'{type(error).__name__}: {error}')
        raise
    finally:
        (output / 'evidence.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    if report['status'] != 'passed':
        raise SystemExit('Published files and manual recovery verified; unjournaled retry fragments remain. Cleanup gate is not passed.')
    print(f'PASS: {len(report["scenarios"])} real {args.termination} cases with safe recovery, retry and no remaining fragments')


if __name__ == '__main__':
    main()
