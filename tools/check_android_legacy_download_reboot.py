#!/usr/bin/env python3
"""Verify an explicitly cancelled WorkManager download does not revive after reboot."""
import argparse
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re
import subprocess
import threading
import time
import uuid

from android_device_checks import add_apk_arguments, resolve_apks
from capture_android_compatibility import capture
from check_android_device_ci import validate_target
from check_android_restore_safety import validate_instrumentation

PACKAGE = 'com.xiaobaitu.reader.debug'
TEST_PACKAGE = PACKAGE + '.test'
CLASS = 'io.legado.app.ui.book.cache.LegacyDownloadCancelRebootProbe'
TEST_METHOD = 'exerciseCancelledWorkAcrossKernelReboot'
NOTIFICATION = 'android.permission.POST_NOTIFICATIONS'


def validate_cancel_reboot(prepared, observed, requests, proof):
    before = proof.get('boot_before')
    after = proof.get('boot_after')
    try:
        if str(uuid.UUID(before)) != before or str(uuid.UUID(after)) != after or before == after:
            raise ValueError('No real kernel reboot')
    except (TypeError, AttributeError, ValueError) as error:
        raise ValueError('Distinct canonical kernel boot IDs required') from error
    case = prepared.get('id')
    if not case or observed.get('id') != case or prepared.get('boot_before') != before:
        raise ValueError('Mixed fixture or boot identity')
    if type(prepared.get('pid')) is not int or prepared['pid'] <= 0 or type(prepared.get('uid')) is not int:
        raise ValueError('Missing original application identity')
    if prepared.get('notification_cancel_action') is not True:
        raise ValueError('Cancellation did not use the production notification action')
    if prepared.get('task_phase') != 'cancelled' or prepared.get('task_owner') != '':
        raise ValueError('The task was not durably cancelled before reboot')
    if prepared.get('work_state') != 'CANCELLED':
        raise ValueError('WorkManager did not report CANCELLED before reboot')
    for field in ['cached_chapter_preserved', 'uncached_chapter_absent_at_cancel']:
        if prepared.get(field) is not True:
            raise ValueError('Cancellation safety precondition missing: ' + field)
    if observed.get('boot_before') != before or observed.get('boot_after') != after:
        raise ValueError('Observed state belongs to another boot')
    if observed.get('task') != prepared.get('task') or observed.get('old_batch') != prepared.get('batch'):
        raise ValueError('The cancelled durable task or batch identity was lost')
    if observed.get('task_phase') != 'cancelled' or observed.get('task_owner') != '':
        raise ValueError('Task revived or acquired an owner after reboot')
    if observed.get('work_state') not in ['CANCELLED', 'ABSENT']:
        raise ValueError('Cancelled WorkManager state became schedulable')
    for field in ['cancelled_task_preserved', 'cached_chapter_preserved', 'uncached_chapter_absent']:
        if observed.get(field) is not True:
            raise ValueError('Post-boot safety proof missing: ' + field)
    for field in ['boot_completed', 'user_unlocked', 'network_connected', 'internet_validated',
                  'fixture_reachable', 'reverse_reconnected', 'old_work_not_executed']:
        if proof.get(field) is not True:
            raise ValueError('Reboot readiness proof missing: ' + field)
    if proof.get('post_boot_idle_seconds', 0) < 10:
        raise ValueError('At least ten post-boot seconds without a request are required')
    if (proof.get('requests_before_open') != 1
            or len(requests) != 1
            or requests[0].get('path') != f'/{case}/chapter/1'
            or requests[0].get('attempt') != 1):
        raise ValueError('Reboot or cache UI restarted the cancelled uncached chapter')
    if proof.get('requests_after_ui') != 1:
        raise ValueError('Opening the cache page created a new request')


class Fixture:
    def __init__(self):
        self.release = threading.Event()
        self.received = threading.Event()
        self.lock = threading.Lock()
        self.requests = []

    def snapshot(self):
        with self.lock:
            return [dict(record) for record in self.requests]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--expected-avd', required=True)
    parser.add_argument('--expected-api', type=int, choices=[26, 33], required=True)
    parser.add_argument('--expected-abi', choices=['arm64-v8a', 'armeabi-v7a', 'x86_64'],
                        default='arm64-v8a')
    parser.add_argument('--boot-wait-seconds', type=int, default=240)
    parser.add_argument('--output', type=Path, required=True)
    add_apk_arguments(parser)
    args = parser.parse_args()
    resolve_apks(parser, args)
    if (not re.fullmatch(r'emulator-\d+', args.serial)
            or not 120 <= args.boot_wait_seconds <= 600):
        parser.error('Explicit owned emulator and a 120-600 second boot allowance are required')
    out = args.output.resolve()
    if out.exists() and any(out.iterdir()):
        parser.error('Output must be new or empty')
    out.mkdir(parents=True, exist_ok=True)
    base = [args.adb, '-s', args.serial]

    def run(*words, timeout=180):
        return subprocess.check_output(
            base + list(words), stderr=subprocess.STDOUT, timeout=timeout
        )

    def soft(*words):
        try:
            return subprocess.run(
                base + list(words), capture_output=True, timeout=8
            ).stdout.decode().strip()
        except subprocess.TimeoutExpired:
            return ''

    def permission():
        rows = re.findall(
            r'android.permission.POST_NOTIFICATIONS: granted=(true|false), flags=\[([^\]]*)\]',
            run('shell', 'dumpsys', 'package', PACKAGE).decode()
        )
        if len(rows) != 1:
            raise ValueError('Ambiguous notification permission state')
        return {'granted': rows[0][0] == 'true', 'flags': rows[0][1]}

    def window_ready(prefix):
        deadline = time.monotonic() + 30
        while True:
            windows = run('shell', 'dumpsys', 'window').decode()
            if re.search(r'mCurrentFocus=Window\{', windows):
                break
            if time.monotonic() >= deadline:
                raise TimeoutError('No focused window after boot')
            time.sleep(.3)
        (out / (prefix + '-window.txt')).write_text(windows)
        if re.search(r'mCurrentFocus=.*Application Error:', windows):
            raise ValueError('A system crash dialog blocks the test UI')

    fixtures = {}
    ready_token = str(uuid.uuid4())

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *unused):
            pass

        def do_GET(self):
            if self.path == '/ready/' + ready_token:
                body = ready_token.encode()
                self.send_response(200)
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                return
            case = self.path.split('/')[1] if self.path.startswith('/') else ''
            fixture = fixtures.get(case)
            if fixture is None:
                self.send_error(404)
                return
            with fixture.lock:
                attempt = len(fixture.requests) + 1
                record = {'path': self.path, 'attempt': attempt}
                fixture.requests.append(record)
            fixture.received.set()
            if attempt == 1:
                fixture.release.wait(args.boot_wait_seconds + 240)
            body = (
                f'<body>{"Late" if attempt == 1 else "Fresh"} after cancel '
                f'{case} attempt={attempt}</body>'
            ).encode()
            try:
                self.send_response(200)
                self.send_header('Content-Type', 'text/html')
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)
                record['response_written'] = True
            except (BrokenPipeError, ConnectionResetError):
                record['client_closed'] = True

    server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    server.daemon_threads = True
    threading.Thread(target=server.serve_forever, daemon=True).start()
    port = server.server_port
    endpoint = 'tcp:' + str(port)
    report = {
        'status': 'running',
        'serial': args.serial,
        'expected_api': args.expected_api,
        'installed_apks': [],
        'scope': (
            'API26/33 production WorkManager path: real notification cancellation, '
            'actual kernel reboot, no automatic request before or after opening the cache UI, '
            'durable cancelled task and preserved cached chapter'
        )
    }
    previous = None
    reverse_owned = False
    before_reverse = ''
    case = str(uuid.uuid4())
    fixture = fixtures[case] = Fixture()
    item = {'id': case, 'status': 'running'}
    report['case'] = item

    def read(suffix):
        data = run(
            'exec-out', 'run-as', PACKAGE, 'cat',
            f'files/compat-legacy-download-cancel-reboot/{case}.{suffix}'
        )
        (out / suffix).write_bytes(data)
        return data

    def probe(stage, boot_before, boot_after):
        words = base + [
            'shell', 'am', 'instrument', '-w', '-r',
            '-e', 'class', f'{CLASS}#{TEST_METHOD}',
            '-e', 'legacyCancelReboot', 'yes',
            '-e', 'caseId', case,
            '-e', 'stage', stage,
            '-e', 'serverPort', str(port),
            '-e', 'bootBefore', boot_before,
            '-e', 'bootAfter', boot_after,
            TEST_PACKAGE + '/androidx.test.runner.AndroidJUnitRunner'
        ]
        result = subprocess.run(
            words, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=240
        )
        (out / (stage + '.log')).write_bytes(result.stdout)
        result.check_returncode()
        validate_instrumentation(result.stdout.decode(), 1)

    boot_before = ''
    boot_after = ''
    try:
        if run('emu', 'avd', 'name').decode().splitlines()[0] != args.expected_avd:
            raise ValueError('Wrong owned AVD')
        api = int(run('shell', 'getprop', 'ro.build.version.sdk').decode())
        abis = run('shell', 'getprop', 'ro.product.cpu.abilist').decode().strip().split(',')
        validate_target(args.app_apk, api, abis, args.expected_api, args.expected_abi)
        for package, apk in [(PACKAGE, args.app_apk), (TEST_PACKAGE, args.test_apk)]:
            if b'Success' not in run('install', '-r', '-t', str(apk)):
                raise RuntimeError('APK installation failed')
            path = run('shell', 'pm', 'path', package).decode().strip()
            if not re.fullmatch(
                    r'package:/data/app/[A-Za-z0-9/_=+~.-]+/base\.apk', path):
                raise ValueError('Unexpected installed APK path')
            actual = run('shell', 'sha256sum', path[8:]).decode().split()[0]
            if actual != hashlib.sha256(apk.read_bytes()).hexdigest():
                raise ValueError('Installed APK differs from selected artifact')
            report['installed_apks'].append({'package': package, 'sha256': actual})
        if api >= 33:
            previous = permission()
            report['permission_before'] = previous
            run('shell', 'pm', 'grant', PACKAGE, NOTIFICATION)
            if permission()['granted'] is not True:
                raise ValueError('Notification permission did not apply')
        before_reverse = run('reverse', '--list').decode()
        report['reverse_before'] = before_reverse
        if before_reverse.strip():
            raise ValueError('Reboot gate requires no preexisting reverse mappings')
        run('reverse', endpoint, endpoint)
        reverse_owned = True
        window_ready('initial')
        report['source'] = capture(args.adb, args.serial)
        boot_before = run(
            'shell', 'cat', '/proc/sys/kernel/random/boot_id'
        ).decode().strip()
        uuid.UUID(boot_before)

        print('START: prepare a real WorkManager download and cancel its notification', flush=True)
        probe('prepare', boot_before, boot_before)
        prepared = json.loads(read('state.json'))
        item['prepared'] = prepared
        if not fixture.received.wait(20):
            raise ValueError('No real uncached-chapter request reached the host server')
        fixture.release.set()
        start = time.monotonic()
        while time.monotonic() - start < 5:
            if len(fixture.snapshot()) != 1:
                raise ValueError('Cancelled WorkManager retried before reboot')
            time.sleep(.3)
        item['pre_reboot_no_request_seconds'] = time.monotonic() - start
        run('shell', 'input', 'keyevent', 'KEYCODE_HOME')

        print('REBOOT: API' + str(api), flush=True)
        run('reboot', timeout=30)
        start = time.monotonic()
        deadline = start + args.boot_wait_seconds
        next_notice = start + 20
        users = ''
        while True:
            boot_after = soft('shell', 'cat', '/proc/sys/kernel/random/boot_id')
            completed = soft('shell', 'getprop', 'sys.boot_completed')
            users = soft('shell', 'dumpsys', 'user')
            unlocked = (
                '0=RUNNING_UNLOCKED' in users
                or re.search(r'State: RUNNING_UNLOCKED', users) is not None
            )
            if boot_after and boot_after != boot_before and completed == '1' and unlocked:
                break
            if time.monotonic() >= deadline:
                raise TimeoutError('No distinct completed, unlocked kernel boot within allowance')
            if time.monotonic() >= next_notice:
                print('WAIT: same boot, elapsed ' + str(round(time.monotonic() - start)) + 's',
                      flush=True)
                next_notice += 20
            time.sleep(1)
        uuid.UUID(boot_after)
        (out / 'post-boot-users.txt').write_text(users)
        proof = {
            'boot_before': boot_before,
            'boot_after': boot_after,
            'boot_completed': True,
            'user_unlocked': True,
            'boot_wait_ms': round((time.monotonic() - start) * 1000),
        }
        item['proof'] = proof
        if run('emu', 'avd', 'name').decode().splitlines()[0] != args.expected_avd:
            raise ValueError('Device identity changed across reboot')
        run('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
        run('shell', 'wm', 'dismiss-keyguard')
        window_ready('post-boot')
        mappings = run('reverse', '--list').decode()
        if any(endpoint in row.split() for row in mappings.splitlines()):
            run('reverse', '--remove', endpoint)
        run('reverse', endpoint, endpoint)
        proof['reverse_reconnected'] = True

        deadline = time.monotonic() + 120
        while True:
            connectivity = run('shell', 'dumpsys', 'connectivity').decode()
            if proof.get('network_connected') is not True and 'CONNECTED' in connectivity:
                proof['network_connected'] = True
            if proof.get('network_connected') is True and 'VALIDATED' in connectivity:
                break
            if time.monotonic() >= deadline:
                raise TimeoutError('Network was not connected and validated after reboot')
            time.sleep(1)
        (out / 'post-boot-network.txt').write_text(connectivity)
        proof['internet_validated'] = True

        request = (
            f'GET /ready/{ready_token} HTTP/1.0\r\nHost: localhost\r\n\r\n'
        ).encode()
        with subprocess.Popen(
                base + ['shell', 'toybox', 'nc', '-w', '5', '-W', '5',
                        '127.0.0.1', str(port)],
                stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT) as connection:
            try:
                connection.stdin.write(request)
                connection.stdin.flush()
                connection.wait(timeout=15)
                response = connection.stdout.read()
            except BaseException:
                connection.kill()
                connection.wait()
                raise
        (out / 'post-boot-fixture-http.txt').write_bytes(response)
        if b'200 OK' not in response or not response.rstrip().endswith(ready_token.encode()):
            raise ValueError('Reconnected fixture HTTP route is not usable')
        proof['fixture_reachable'] = True

        start = time.monotonic()
        while time.monotonic() - start < 10:
            if len(fixture.snapshot()) != 1:
                raise ValueError('Cancelled work made a request after reboot before the UI opened')
            time.sleep(.3)
        proof['post_boot_idle_seconds'] = time.monotonic() - start
        proof['requests_before_open'] = len(fixture.snapshot())
        proof['old_work_not_executed'] = True

        print('OBSERVE: cancelled task and cache state after reboot', flush=True)
        probe('observe', boot_before, boot_after)
        observed = json.loads(read('observed.json'))
        item['observed'] = observed
        time.sleep(2)
        proof['requests_after_ui'] = len(fixture.snapshot())
        item['requests'] = fixture.snapshot()
        validate_cancel_reboot(prepared, observed, item['requests'], proof)
        item['status'] = 'passed'

        probe('cleanup', boot_before, boot_after)
        cleanup = json.loads(read('cleanup.json'))
        item['cleanup'] = cleanup
        if (cleanup.get('owned_records_removed') is not True
                or cleanup.get('baseline_managers_preserved') is not True):
            raise ValueError('Incomplete UUID fixture cleanup')
        for suffix in ['state.json', 'observed.json', 'cleanup.json', 'instrumented.txt']:
            run(
                'shell', 'run-as', PACKAGE, 'rm', '-f',
                f'files/compat-legacy-download-cancel-reboot/{case}.{suffix}'
            )
        after = capture(args.adb, args.serial)
        if (after['source_sha256'] != report['source']['source_sha256']
                or after['apks'] != report['source']['apks']):
            raise ValueError('Source or APK identity changed during the checks')
        report['checks_passed'] = True
        print('PASS: explicit cancellation did not revive after a real kernel reboot', flush=True)
    except Exception as error:
        report.update(status='failed', error=f'{type(error).__name__}: {error}')
        raise
    finally:
        try:
            fixture.release.set()
            if previous is not None:
                run(
                    'shell', 'pm',
                    'grant' if previous['granted'] else 'revoke',
                    PACKAGE, NOTIFICATION
                )
                report['permission_after'] = permission()
                report['permission_restored'] = report['permission_after'] == previous
                if not report['permission_restored']:
                    raise ValueError('Notification permission state did not restore')
            if reverse_owned:
                mappings = run('reverse', '--list').decode()
                if any(endpoint in row.split() for row in mappings.splitlines()):
                    run('reverse', '--remove', endpoint)
                report['reverse_after'] = run('reverse', '--list').decode()
                report['reverse_restored'] = report['reverse_after'] == before_reverse
                if not report['reverse_restored']:
                    raise ValueError('Reverse mappings did not restore')
            if report.get('checks_passed') is True:
                report['status'] = 'passed'
        except Exception as error:
            report.update(status='failed', restoration_error=repr(error))
            raise
        finally:
            fixture.release.set()
            server.shutdown()
            server.server_close()
            (out / 'evidence.json').write_text(
                json.dumps(report, ensure_ascii=False, indent=2) + '\n'
            )


if __name__ == '__main__':
    main()
