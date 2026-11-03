#!/usr/bin/env python3
"""Verify editor IME recovery on an explicitly owned, preconfigured API35 emulator.

Required profile: actual landscape 2424x1080, density 540, font scale 2.0,
freeform support. This tool never changes display or global device settings.
"""
import argparse
from collections import Counter
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
CASES = ('code', 'compose', 'code-order', 'code-burst')
ORDER_SUFFIX = 'qwertyuiopasdfghjklzxcvbnm0123456789'
SUFFIXES = ('.json', '-before.png', '-recovered.png', '-resized.png', '-failure.png', '-last.png')
OWNED = tuple('ime-recovery-' + case + suffix for case in CASES for suffix in SUFFIXES)


def validate_case(row, case):
    if case not in CASES:
        raise ValueError('Unknown recovery case')
    suffix = '   ' if case == 'code' else ORDER_SUFFIX if case in ('code-order', 'code-burst') else 'queued'
    expected = ('{}' if case == 'code' else 'DRAFT-') + suffix
    if row.get('status') != 'passed' or row.get('case') != case:
        raise ValueError('Case did not pass: ' + case)
    if (row.get('font_scale'), row.get('density'), row.get('display_width_px'), row.get('display_height_px')) != (2, 3.375, 2424, 1080):
        raise ValueError('Wrong actual display/font profile')
    for key in ['recovery_message_visible', 'recovery_live_region_polite', 'draft_not_committed_during_recovery',
                'same_editor_during_recovery', 'retry_ime_visible', 'preferences_restored',
                'recovery_hint_not_clipped', 'recovery_ime_animation_finished']:
        if row.get(key) is not True:
            raise ValueError('Missing verified recovery: ' + key)
    for key in ['recovered_exact_text', 'recreated_exact_text', 'retry_exact_text']:
        if row.get(key) != expected:
            raise ValueError('Draft lost or changed: ' + key)
    if row.get('completion') != ('confirmed' if case == 'code' else 'cancelled'):
        raise ValueError('Missing real completion action')
    if (row.get('queued_suffix') != suffix or row.get('injection_method') !=
            ('shell-input-text' if case == 'code-burst' else 'uiautomation-synchronous-key-events')):
        raise ValueError('Missing distinct input method/character evidence')
    original, recreated = row.get('original_activity_identity'), row.get('recreated_activity_identity')
    if not all(type(value) is int and value > 0 for value in [original, recreated, row.get('task_id')]) or original == recreated:
        raise ValueError('No actual activity recreation/task identity')
    heights = row.get('recovery_action_heights_px')
    if not isinstance(heights, list) or len(heights) != 2 or any(not isinstance(h, (float, int)) or h < 48 * 3.375 - 1 for h in heights):
        raise ValueError('Missing usable confirmation/cancellation targets')
    frames = row.get('frames', [])
    opening = [f for f in frames if f.get('phase') in ('launch', 'open-ime', 'await-recovery')]
    if not opening or {f.get('root_bounds') for f in opening} != {'[303,196][2121,938]'} or len({f.get('root_identity') for f in opening}) != 1:
        raise ValueError('Input root moved/replaced or wrong actual freeform bounds')
    underflow = [i for i, f in enumerate(opening) if f.get('ime_visible') is True and 0 < f.get('usable_height_px', 0) < 48 * 3.375]
    if not underflow or not any(f.get('ime_visible') is False and f.get('ime_bottom_px') == 0 for f in opening[underflow[-1] + 1:]):
        raise ValueError('No real underflow followed by keyboard recovery')
    retry = [f for f in frames if f.get('phase') == 'fullscreen-retry']
    if not any(f.get('ime_visible') is True and f.get('root_bounds') == '[0,0][2424,1080]' for f in retry):
        raise ValueError('No actual full-window IME retry')
    if case != 'compose' and any(type(f.get('editor_height_px')) is not int or f['editor_height_px'] <= 0 for f in frames):
        raise ValueError('Native editor collapsed to zero or lacks measurement')
    start, end = row.get('key_injection_started_ms'), row.get('key_injection_completed_ms')
    if not all(type(t) is int and t > 0 for t in [start, end]) or end <= start:
        raise ValueError('Missing queued real key injection')
    keys = row.get('key_events', [])
    expected_keys = ([62, 62, 62] if case == 'code' else
                     [29 + ord(c) - ord('a') if c.isalpha() else 7 + int(c) for c in suffix])
    # The IME can return an unhandled key-up before the corresponding relayed
    # key-down. Require every up and the exact text-producing down order.
    downs = [k.get('key_code') for k in keys if k.get('action') == 0]
    ups = [k.get('key_code') for k in keys if k.get('action') == 1]
    if (downs != expected_keys or Counter(ups) != Counter(expected_keys) or
            any(k.get('action') not in (0, 1) for k in keys)):
        raise ValueError('Missing native key dispatch sequence')
    if any(k.get('root_identity') != opening[0]['root_identity'] or k.get('is_text_editor') is not True or
           k.get('action') == 0 and k.get('handled') is not True for k in keys):
        raise ValueError('Keys did not reach the same active editor')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--expected-avd', required=True)
    parser.add_argument('--expected-abi', choices=['arm64-v8a', 'x86_64'], required=True)
    parser.add_argument('--output', type=Path, required=True)
    add_apk_arguments(parser)
    args = parser.parse_args()
    resolve_apks(parser, args)
    if not re.fullmatch(r'emulator-\d+', args.serial):
        parser.error('Explicit owned emulator required')
    output = args.output.resolve()
    if output.exists() and any(output.iterdir()):
        parser.error('Output must be new or empty')
    output.mkdir(parents=True, exist_ok=True)
    def run(*words):
        return subprocess.check_output([args.adb, '-s', args.serial, *words], stderr=subprocess.STDOUT, timeout=180)
    def profile():
        return {name: run('shell', *command).decode().strip() for name, command in {
            'size': ['wm', 'size'], 'density': ['wm', 'density'],
            'font_scale': ['settings', 'get', 'system', 'font_scale'],
            'rotation_auto': ['settings', 'get', 'system', 'accelerometer_rotation'],
            'rotation': ['settings', 'get', 'system', 'user_rotation']}.items()}
    report = {'status': 'running', 'scope': 'Two production editors: real freeform IME underflow, queued keys, recreation, full-window retry and confirm/cancel; not Chinese composition, passwords, process death or physical devices', 'installed_apks': []}
    before = None
    try:
        if run('emu', 'avd', 'name').decode().splitlines()[0] != args.expected_avd:
            raise ValueError('Wrong owned AVD')
        validate_target(args.app_apk, int(run('shell', 'getprop', 'ro.build.version.sdk')),
                        run('shell', 'getprop', 'ro.product.cpu.abilist').decode().strip().split(','), 35, args.expected_abi)
        if b'feature:android.software.freeform_window_management' not in run('shell', 'pm', 'list', 'features'):
            raise ValueError('Freeform unsupported')
        before = profile(); report['before'] = before
        if (before['font_scale'] != '2.0' or 'Override density: 540' not in before['density'] or
                b'DisplayFrames w=2424 h=1080 r=1' not in run('shell', 'dumpsys', 'window', 'displays')):
            raise ValueError('Required actual profile is not ready')
        report['source_before'] = capture()
        for package, apk in [(PACKAGE, args.app_apk), (PACKAGE + '.test', args.test_apk)]:
            if b'Success' not in run('install', '-r', '-t', str(apk)):
                raise ValueError('Install failed')
            path = run('shell', 'pm', 'path', package).decode().strip()
            if not re.fullmatch(r'package:/data/app/[A-Za-z0-9/_=+~.-]+/base\.apk', path):
                raise ValueError('Unexpected package path')
            digest = hashlib.sha256(apk.read_bytes()).hexdigest()
            if run('shell', 'sha256sum', path[8:]).decode().split()[0] != digest:
                raise ValueError('Installed APK differs')
            report['installed_apks'].append({'package': package, 'sha256': digest})
        query = ['shell', 'content', 'query', '--uri', 'content://' + PACKAGE + '.readerProvider/books/query']
        report['books_before'] = run(*query).decode().strip()
        run('shell', 'run-as', PACKAGE, 'rm', '-f', *['cache/' + name for name in OWNED])
        data = run('shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                   'io.legado.app.ui.config.EditorImeRecoveryUiTest', PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner')
        (output / 'instrumentation.log').write_bytes(data)
        validate_instrumentation(data.decode(), 4)
        for case in CASES:
            for suffix in ['.json', '-before.png', '-recovered.png', '-resized.png', '-last.png']:
                name = 'ime-recovery-' + case + suffix
                raw = run('exec-out', 'run-as', PACKAGE, 'cat', 'cache/' + name)
                (output / name).write_bytes(raw)
                if suffix == '.json':
                    validate_case(json.loads(raw), case)
                elif not raw.startswith(b'\x89PNG\r\n\x1a\n'):
                    raise ValueError('Invalid screenshot')
        report['books_after'] = run(*query).decode().strip()
        if report['books_before'] != report['books_after']:
            raise ValueError('Public books changed')
        after = capture()
        if after['source_sha256'] != report['source_before']['source_sha256'] or after['apks'] != report['source_before']['apks']:
            raise ValueError('Source or artifacts changed during test')
        report.update(checks_passed=True, tests=4)
    except Exception as error:
        report.update(status='failed', error=repr(error))
        raise
    finally:
        try:
            if before is not None and report['installed_apks']:
                available = run('shell', 'run-as', PACKAGE, 'ls', 'cache').decode().splitlines()
                for name in OWNED:
                    if name in available:
                        (output / name).write_bytes(run('exec-out', 'run-as', PACKAGE, 'cat', 'cache/' + name))
                run('shell', 'run-as', PACKAGE, 'rm', '-f', *['cache/' + name for name in OWNED])
                report['test_artifacts_removed'] = True
                report['display_settings_unchanged'] = profile() == before
                if report.get('checks_passed') and report['display_settings_unchanged']:
                    report['status'] = 'passed'
                elif report.get('checks_passed'):
                    raise ValueError('Display settings changed')
        except Exception as error:
            report.update(status='failed', cleanup_error=repr(error))
            raise
        finally:
            (output / 'evidence.json').write_text(json.dumps(report, indent=2) + '\n')
    print('PASS: 4 real editor recovery cases, including distinct key order/bursts; settings and public books preserved')


if __name__ == '__main__':
    main()
