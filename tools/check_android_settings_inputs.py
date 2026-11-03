#!/usr/bin/env python3
"""Exercise production settings editors on an emulator; restore display settings afterwards."""
import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / 'tools'))
from capture_android_compatibility import capture
from android_device_checks import add_apk_arguments, resolve_apks, complete_report, restore_settings, validate_screenshot_backends
from check_android_restore_safety import validate_instrumentation

CASES = ('user-agent', 'custom-hosts', 'user-agent-counterexample')
SUFFIXES = ('.json', '-text-checks.json', '-touches.json', '-editing.png', '-recreated.png', '-last.png', '-failure.json', '-failure.png')
OWNED = tuple(f'settings-input-{case}{suffix}' for case in CASES for suffix in SUFFIXES)


def validate_editor_profile(rows, font_scale, density, rotation):
    if len(rows) != 6 or not all(abs(row['font_scale'] - font_scale) < .01 for row in rows):
        raise ValueError('Wrong font scale or incomplete trace')
    if density and not all(row['density_dpi'] == density for row in rows):
        raise ValueError('Wrong display density')
    for row in rows:
        geometry = row.get('geometry', {})
        width, height = geometry.get('display_width_px'), geometry.get('display_height_px')
        if (type(width) is not int or type(height) is not int or min(width, height) <= 0
                or type(row.get('in_multi_window')) is not bool
                or type(row.get('task_id')) is not int or row['task_id'] <= 0):
            raise ValueError('Missing actual display and window identity')
        if ((row['width_dp'] > row['height_dp']) != (rotation == '1')
                or (width > height) != (rotation == '1')):
            raise ValueError('Requested display and application window orientation did not both take effect')


def validate_caret_probe(checks):
    selected = [row for row in checks if row.get('stage') == 'selected-middle-line']
    if len(selected) != 1:
        raise ValueError('Missing unique second-line caret probe')
    row = selected[0]
    fields = ['caret_line_index', 'editor_line_top_px', 'editor_line_bottom_px',
              'editor_height_px', 'editor_scroll_y_px']
    if (not all(type(row.get(key)) is int for key in fields)
            or row['caret_line_index'] != 1
            or not 0 <= row['editor_line_top_px'] < row['editor_line_bottom_px'] <= row['editor_height_px']):
        raise ValueError('Second-line caret glyphs are clipped or unverified')


def validate_editor_touches(rows):
    """Require real editor hits and a stable input origin across IME transitions.

    The initial pointer pair can overlap the system's popup entrance translation.
    View layout origins do not include that surface animation. Check that down in
    both screen and window coordinates; retain the 2px mapping guard for the rapid
    second pair and the confirmed-IME pair, without waiting away the IME transition.
    """
    taps = rows[:6]
    if len(taps) != 6 or [row['action'] for row in taps] != [0, 1, 0, 1, 0, 1]:
        raise ValueError('Missing native editor tap events')
    bounds = {row['decor_bounds'] for row in taps}
    if len(bounds) != 1:
        raise ValueError('Native editor window moved during IME input')
    for index, row in enumerate(taps):
        decor = list(map(int, re.findall(r'-?\d+', row['decor_bounds'])))
        editor = list(map(int, re.findall(r'-?\d+', row['editor_bounds'])))
        window_editor = list(map(int, re.findall(r'-?\d+', row.get('editor_window_bounds', ''))))
        if (len(decor) != 4 or len(editor) != 4 or len(window_editor) != 4
                or row['editor_attached'] is not True
                or any(type(row.get(key)) not in (int, float) or not math.isfinite(row[key])
                       for key in ['raw_x', 'raw_y', 'x', 'y'])):
            raise ValueError('Incomplete editor window evidence')
        opening_pair = index < 2 and taps[0]['ime_visible'] is False
        if not opening_pair and (abs(row['raw_x'] - row['x'] - decor[0]) > 2
                                 or abs(row['raw_y'] - row['y'] - decor[1]) > 2):
            raise ValueError('Native event coordinates differ from visible window')
        # A down captures the gesture. IME layout can move the editor before its up.
        if row['action'] == 0 and not (editor[0] <= row['raw_x'] <= editor[2] and editor[1] <= row['raw_y'] <= editor[3]):
            raise ValueError('Native tap missed the visible editor')
        if row['action'] == 0 and not (window_editor[0] <= row['x'] <= window_editor[2]
                                       and window_editor[1] <= row['y'] <= window_editor[3]):
            raise ValueError('Native tap missed the editor in window coordinates')
    if not all(row['ime_visible'] is True for row in taps[4:]):
        raise ValueError('Missing native tap after confirmed IME visibility')


def collect_failure_evidence(run, output):
    """Best effort only: missing diagnostics must never replace the original test failure."""
    result = {'copied': [], 'errors': []}
    try:
        available = set(run('shell', 'run-as', 'com.xiaobaitu.reader.debug', 'ls', 'cache').decode().splitlines())
    except (OSError, subprocess.SubprocessError) as error:
        result['errors'].append({'operation': 'list', 'error': repr(error)})
        return result
    for name in OWNED:
        if name not in available:
            continue
        try:
            data = run('exec-out', 'run-as', 'com.xiaobaitu.reader.debug', 'cat', 'cache/' + name)
            (output / name).write_bytes(data)
            result['copied'].append(name)
        except (OSError, subprocess.SubprocessError) as error:
            result['errors'].append({'operation': name, 'error': repr(error)})
    return result


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--adb', required=True)
    p.add_argument('--serial', required=True)
    p.add_argument('--output', required=True, type=Path)
    p.add_argument('--font-scale', choices=['1.0', '1.3', '2.0'], default='1.0')
    p.add_argument('--density', type=int)
    p.add_argument('--rotation', choices=['0', '1'], default='0')
    add_apk_arguments(p)
    a = p.parse_args()
    resolve_apks(p, a)
    if not re.fullmatch(r'emulator-\d+', a.serial):
        p.error('Display-setting probes are restricted to emulators')
    if a.density is not None and not 160 <= a.density <= 640:
        p.error('Density must be between 160 and 640 dpi')
    out = a.output.resolve()
    if out.exists() and any(out.iterdir()):
        p.error('--output must be new or empty')
    out.mkdir(parents=True, exist_ok=True)
    base = [a.adb, '-s', a.serial]
    def run(*args):
        # A cold-installed instrumentation package can spend several minutes
        # compiling and staging three editor tests on an ARM emulator.
        return subprocess.check_output(base + list(args), stderr=subprocess.STDOUT, timeout=600)
    def setting(key):
        return run('shell', 'settings', 'get', 'system', key).decode().strip()
    def put(key, value):
        if value == 'null':
            run('shell', 'settings', 'delete', 'system', key)
        else:
            if not re.fullmatch(r'[0-9.]+', value):
                raise ValueError(f'Unexpected setting: {key}={value}')
            run('shell', 'settings', 'put', 'system', key, value)
    old = {key: setting(key) for key in ['font_scale', 'accelerometer_rotation', 'user_rotation']}
    old_density = run('shell', 'wm', 'density').decode()
    override = re.search(r'Override density: (\d+)', old_density)
    report = {'requested': {'font_scale': a.font_scale, 'density': a.density, 'rotation': a.rotation},
              'before': {'settings': old, 'density': old_density}, 'installed_apks': []}
    try:
        for package, apk in [('com.xiaobaitu.reader.debug', a.app_apk),
                             ('com.xiaobaitu.reader.debug.test', a.test_apk)]:
            if b'Success' not in run('install', '-r', '-t', str(apk)):
                raise RuntimeError('APK installation failed')
            paths = [s[8:] for s in run('shell', 'pm', 'path', package).decode().splitlines() if s.endswith('/base.apk')]
            if len(paths) != 1 or not re.fullmatch(r'/data/app/[A-Za-z0-9/_=+~.-]+/base\.apk', paths[0]):
                raise RuntimeError('Unexpected installed APK path')
            actual = run('shell', 'sha256sum', paths[0]).decode().split()[0]
            if actual != hashlib.sha256(apk.read_bytes()).hexdigest():
                raise RuntimeError('Installed APK differs from local artifact')
            report['installed_apks'].append({'package': package, 'sha256': actual})
        # A failed earlier attempt may leave diagnostics; never attribute them to this run.
        run('shell', 'run-as', 'com.xiaobaitu.reader.debug', 'rm', '-f', *['cache/' + name for name in OWNED])
        put('font_scale', a.font_scale)
        put('accelerometer_rotation', '0')
        put('user_rotation', a.rotation)
        if a.density:
            run('shell', 'wm', 'density', str(a.density))
        report['source'] = capture(a.adb, a.serial)
        report['instrumentation_started'] = True
        data = run('shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                   'io.legado.app.ui.config.SettingsInputDialogUiTest',
                   '-e', 'compatSettingsRotation', a.rotation,
                   'com.xiaobaitu.reader.debug.test/androidx.test.runner.AndroidJUnitRunner')
        (out / 'instrumentation.log').write_bytes(data)
        validate_instrumentation(data.decode(), 3)
        traces = []
        missing = []
        for case in CASES:
            name = f'settings-input-{case}'
            raw = run('exec-out', 'run-as', 'com.xiaobaitu.reader.debug', 'cat', f'cache/{name}.json')
            (out / f'{name}.json').write_bytes(raw)
            rows = json.loads(raw)
            metadata = run('exec-out', 'run-as', 'com.xiaobaitu.reader.debug', 'cat', f'cache/{name}-text-checks.json')
            (out / f'{name}-text-checks.json').write_bytes(metadata)
            checks = json.loads(metadata)
            touches = run('exec-out', 'run-as', 'com.xiaobaitu.reader.debug', 'cat', f'cache/{name}-touches.json')
            (out / f'{name}-touches.json').write_bytes(touches)
            if case == 'user-agent':
                editor_checks = [row for row in checks if row.get('source') == 'android-accessibility']
                initial_cancel = [row for row in checks if row['stage'] == 'cancel-before-editing']
                assert [row['stage'] for row in editor_checks] == [
                    'editing-after-replacement',
                    'after-recreation',
                    'reopened-after-cancel',
                ]
                assert all(row['source'] == 'android-accessibility'
                           and row['assertion_equal'] and row['platform_text_equal']
                           for row in editor_checks)
                assert len(initial_cancel) == 1 and initial_cancel[0]['dialog_closed'] is True
            elif case == 'custom-hosts':
                validate_editor_touches(json.loads(touches))
                validate_caret_probe(checks)
                restored = [row for row in checks if row['stage'] in (
                    'after-recreation', 'reopened-after-confirm')]
                assert [row['stage'] for row in restored] == [
                    'after-recreation', 'reopened-after-confirm']
                assert all(row['source'] == 'android-accessibility'
                           and row['assertion_equal'] and row['platform_text_equal']
                           for row in restored)
                initial_cancel = [row for row in checks if row['stage'] == 'cancel-before-editing']
                cancellations = [row for row in checks if row['stage'] in (
                    'action-cancel', 'back-cancel')]
                assert len(initial_cancel) == 1 and initial_cancel[0]['dialog_closed'] is True
                assert [row['stage'] for row in cancellations] == ['action-cancel', 'back-cancel']
                assert all(row['dialog_closed'] is True and row['preferences_unchanged'] is True for row in cancellations)
            elif case == 'user-agent-counterexample':
                initial_cancel = [row for row in checks if row['stage'] == 'cancel-before-editing']
                typed = [row for row in checks if row['stage'] == 'after-real-key-input']
                rebuilt = [row for row in checks if row['stage'] == 'after-recreation-counterexample']
                rejected = [row for row in checks if row['stage'] == 'reject-wrong-content']
                restored = [row for row in checks if row['stage'] == 'after-negative-control']
                assert len(initial_cancel) == 1 and initial_cancel[0]['dialog_closed'] is True
                assert len(typed) == len(rebuilt) == len(rejected) == len(restored) == 1
                valid = [typed[0], rebuilt[0], restored[0]]
                assert all(row['source'] == 'android-accessibility'
                           and row['assertion_equal'] and row['platform_text_equal']
                           for row in valid)
                wrong = rejected[0]
                assert (wrong['source'] == 'android-accessibility'
                        and not wrong['assertion_equal']
                        and not wrong['platform_text_equal'])
                assert wrong['actual_length'] == wrong['expected_length'] and wrong['actual_text_sha256'] != wrong['expected_text_sha256']
                assert restored[0]['assertion_equal']
                negative = run('exec-out', 'run-as', 'com.xiaobaitu.reader.debug', 'cat', f'cache/{name}-failure.json')
                (out / f'{name}-failure.json').write_bytes(negative)
                diagnostic = json.loads(negative)
                assert diagnostic['expected_failure'] is True and diagnostic['cancel_visible_before_cleanup'] is True
                assert diagnostic['step'] == 'failure-evidence-counterexample'
                assert diagnostic['failure_class'] == 'java.lang.AssertionError'
                assert diagnostic['failure_message'] == 'Expected editor evidence counterexample'
            for stage in ['editing', 'recreated', 'last'] + (['failure'] if case == 'user-agent-counterexample' else []):
                pixels = run('exec-out', 'run-as', 'com.xiaobaitu.reader.debug', 'cat', f'cache/{name}-{stage}.png')
                if not pixels.startswith(b'\x89PNG'):
                    missing.append(f'{name}-{stage}')
                else:
                    (out / f'{name}-{stage}.png').write_bytes(pixels)
            traces.extend(rows[:-1])
            if rows[-1].get('settings_restored') is not True:
                raise RuntimeError('Application settings were not restored')
        if missing:
            raise RuntimeError(f'Missing screenshots: {missing}')
        validate_editor_profile(traces, float(a.font_scale), a.density, a.rotation)
        report['actual_window_profiles'] = [
            {'case': index // 2, 'stage': row['stage'], 'task_id': row['task_id'],
             'in_multi_window': row['in_multi_window'],
             'window_dp': [row['width_dp'], row['height_dp']],
             'display_px': [row['geometry']['display_width_px'], row['geometry']['display_height_px']]}
            for index, row in enumerate(traces)]
        if not all(row['ime_visible'] for row in traces if row['stage'] == 'editing'):
            raise RuntimeError('Editing did not show the real IME')
        validate_screenshot_backends(traces, report['source']['device']['api'])
        report['status'] = 'checks_passed'
        report['checks_passed'] = True
        report['tests'] = 3
        report['scope'] = 'Two production editors plus real platform key input and same-length wrong-content rejection; exact displayed and raw input strings'
        report['samples'] = traces
    except Exception as error:
        report['status'] = 'failed'
        report['error'] = f'{type(error).__name__}: {error}'
        report['failure_evidence'] = (collect_failure_evidence(run, out) if report.get('instrumentation_started')
                                      else {'copied': [], 'errors': [], 'skipped': 'instrumentation_not_started'})
        raise
    finally:
        # Density changes can materialize a missing font_scale on a fresh API 26 device.
        run('shell', 'wm', 'density', override.group(1) if override else 'reset')
        report['setting_restore_repairs'] = restore_settings(old, setting, put)
        after = {key: setting(key) for key in old}
        density = run('shell', 'wm', 'density').decode()
        report['after'] = {'settings': after, 'density': density}
        report['display_settings_restored'] = after == old and density == old_density
        if not report['display_settings_restored']:
            report.update(status='failed', error='Display settings did not restore', error_stage='postconditions')
        (out / 'evidence.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
        if not report['display_settings_restored']:
            raise RuntimeError('Display settings did not restore')
    # Delete only the owned artifacts in one command, then verify their absence.
    def cleanup():
        names = OWNED
        run('shell', 'run-as', 'com.xiaobaitu.reader.debug', 'rm', '-f', *['cache/' + name for name in names])
        remaining = set(run('shell', 'run-as', 'com.xiaobaitu.reader.debug', 'ls', 'cache').decode().splitlines()) & set(names)
        if remaining:
            raise RuntimeError('Owned test artifacts remain: ' + ', '.join(sorted(remaining)))
    complete_report(report, out, cleanup)
    print(f'PASS {a.serial}: 2 editors and 3 checks, real IME/key input and recreation, display settings restored')


if __name__ == '__main__':
    main()
