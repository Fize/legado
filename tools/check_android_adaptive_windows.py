#!/usr/bin/env python3
"""Run actual freeform-window regressions on a dedicated, empty Android emulator."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

from android_device_checks import add_apk_arguments, resolve_apks, restore_settings
from capture_android_compatibility import capture
from check_android_restore_safety import validate_instrumentation

PACKAGE = 'com.xiaobaitu.reader.debug'
TEST_PACKAGE = PACKAGE + '.test'
CLASSES = [
    'io.legado.app.ui.main.BookshelfTaskResizeUiTest',
    'io.legado.app.ui.main.AdaptiveSearchTaskResizeUiTest',
    'io.legado.app.ui.main.explore.ExploreWindowResizeUiTest',
]
WIDTHS = [900, 860, 840, 839, 760, 640, 600, 599, 520, 420, 360,
          420, 520, 599, 600, 760, 839, 840, 900]
SAMPLES = {
    'bookshelf': 'bookshelf-task-resize.json',
    'results': 'adaptive-search-task-resize-results.json',
    'draft': 'adaptive-search-task-resize-draft.json',
    'explore': 'explore-window-resize.json',
}


def verify_samples(samples):
    """Independently verify geometry, identity and requested window transitions."""
    if set(samples) != set(SAMPLES):
        raise ValueError('Missing adaptive window samples')
    shelf, results, draft, explore = (samples[name] for name in SAMPLES)
    expected = {
        'bookshelf': [(w, None) for w in WIDTHS],
        'results': [(900, 600)] + [(w, 600) for w in WIDTHS] + [(840, 360), (599, 600), (600, 600)],
        'draft': [(360, 600), (599, 600), (600, 600), (839, 600), (840, 600), (900, 600),
                  (360, 600), (600, 600), (840, 600), (900, 600), (360, 600), (840, 360)],
        'explore': [(1200, 800), (900, 850), (840, 600), (600, 600), (360, 600), (840, 360), (1200, 800)],
    }
    for name, rows in samples.items():
        if not isinstance(rows, list) or not rows:
            raise ValueError('Missing samples for ' + name)
        actual = [(row.get('width_dp'), row.get('height_dp')) if name == 'explore' else
                  (row.get('requested_width_dp'), row.get('requested_height_dp')) for row in rows]
        if actual != expected[name]:
            raise ValueError('Unexpected window sequence for ' + name)
        if len({row.get('task_id') for row in rows}) != 1 or not rows[0].get('task_id'):
            raise ValueError('Task identity changed for ' + name)
        if len({row.get('activity_identity') for row in rows}) != 1 or not rows[0].get('activity_identity'):
            raise ValueError('Activity identity changed for ' + name)
    for row in shelf:
        if row.get('configuration_width_dp') != row['requested_width_dp'] or row.get('columns', 0) <= 0:
            raise ValueError('Incorrect bookshelf geometry')
    for row in results + draft:
        w, h = row['requested_width_dp'], row['requested_height_dp']
        if (row.get('configuration_width_dp'), row.get('configuration_height_dp')) != (w, h):
            raise ValueError('Incorrect search window geometry')
        if row.get('navigation') != ('bar' if w < 600 or h < 480 else 'rail'):
            raise ValueError('Incorrect navigation mode')
    if any(row.get('requests') != 0 for row in draft):
        raise ValueError('Unsubmitted draft caused a search')
    if any(row.get('requests') != 1 for row in results[1:]) or results[0].get('requests') not in (0, 1):
        raise ValueError('Search request was repeated or missing')
    anchors = [row.get('anchor') for row in results[1:20]]
    if not anchors[0] or len(set(anchors)) != 1:
        raise ValueError('Search anchor was lost')
    for row in explore:
        left, right = row['content_left_px'], row['content_right_px']
        list_left, list_right = row['list_left_px'], row['list_right_px']
        if not left <= list_left < list_right <= right or abs(left + right - list_left - list_right) > 4:
            raise ValueError('Discovery list is not centered in the content area')
        if not left <= row['row_left_px'] < row['row_right_px'] <= right or row['row_width_dp'] > 720:
            raise ValueError('Discovery row exceeds available width')
        if list_right - list_left > 1442:  # Harness fixes density at 320 dpi.
            raise ValueError('Discovery list exceeds 720 dp')
    return {name: len(rows) for name, rows in samples.items()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--expected-avd', required=True)
    parser.add_argument('--output', type=Path, required=True)
    add_apk_arguments(parser)
    args = parser.parse_args()
    resolve_apks(parser, args)
    if not re.fullmatch(r'emulator-\d+', args.serial):
        parser.error('This check requires a dedicated emulator')
    output = args.output.resolve()
    if output.exists() and any(output.iterdir()):
        parser.error('Output must be new or empty')
    output.mkdir(parents=True, exist_ok=True)
    base = [args.adb, '-s', args.serial]

    def run(*words, timeout=45):
        return subprocess.check_output(base + list(words), stderr=subprocess.STDOUT, timeout=timeout).decode().strip()

    def absent(package):
        result = subprocess.run(base + ['shell', 'pm', 'path', package], stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=20)
        return result.returncode in (0, 1) and not result.stdout.strip()

    def installed_hash(package):
        path = run('shell', 'pm', 'path', package)
        if not path.startswith('package:/data/app/') or '\n' in path:
            raise RuntimeError('Unexpected installed package path: ' + path)
        return run('shell', 'sha256sum', path[8:]).split()[0]

    def setting(key):
        return run('shell', 'settings', 'get', *key.split('/'))

    def put(key, value):
        if value != 'null' and not re.fullmatch(r'[0-9.]+', value):
            raise ValueError('Unexpected setting value: ' + value)
        run('shell', 'settings', 'delete' if value == 'null' else 'put', *key.split('/'),
            *(() if value == 'null' else (value,)))

    if run('shell', 'getprop', 'ro.kernel.qemu') != '1' or run('emu', 'avd', 'name').splitlines()[0] != args.expected_avd:
        raise RuntimeError('Unexpected emulator identity')
    if int(run('shell', 'getprop', 'ro.build.version.sdk')) < 30:
        raise RuntimeError('Actual freeform task tests require API 30 or later')
    if 'feature:android.software.freeform_window_management' not in run('shell', 'pm', 'list', 'features').splitlines():
        raise RuntimeError('Emulator does not support freeform windows')
    windows = run('shell', 'dumpsys', 'window', 'windows')
    (output / 'initial-windows.txt').write_text(windows + '\n')
    if 'Application Not Responding:' in windows:
        raise RuntimeError('A system or app not-responding dialog is blocking the emulator')
    packages = [PACKAGE, TEST_PACKAGE, 'com.xiaobaitu.reader.release']
    if not all(absent(package) for package in packages):
        raise RuntimeError('Dedicated emulator must not contain project packages')
    keys = ['system/font_scale', 'system/accelerometer_rotation', 'system/user_rotation',
            'global/enable_freeform_support', 'global/force_resizable_activities']
    before = {'size': run('shell', 'wm', 'size'), 'density': run('shell', 'wm', 'density'),
              'settings': {key: setting(key) for key in keys}}
    source = capture(args.adb, args.serial)
    apks = {PACKAGE: args.app_apk, TEST_PACKAGE: args.test_apk}
    hashes = {package: hashlib.sha256(path.read_bytes()).hexdigest() for package, path in apks.items()}
    result = {'status': 'running', 'serial': args.serial, 'avd': args.expected_avd,
              'source': source, 'before': before, 'apks': hashes, 'uninstalled': []}
    failure = None
    try:
        for package, apk in apks.items():
            if 'Success' not in run('install', '-t', str(apk), timeout=180) or installed_hash(package) != hashes[package]:
                raise RuntimeError('Installed package identity mismatch: ' + package)
        run('shell', 'wm', 'size', '3000x2400')
        run('shell', 'wm', 'density', '320')
        put('system/accelerometer_rotation', '0')
        put('system/user_rotation', '0')
        put('system/font_scale', '1.0')
        put('global/enable_freeform_support', '1')
        put('global/force_resizable_activities', '1')
        command = base + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', ','.join(CLASSES),
                          TEST_PACKAGE + '/androidx.test.runner.AndroidJUnitRunner']
        completed = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=600)
        (output / 'instrumentation.log').write_bytes(completed.stdout)
        samples = {}
        for name, filename in SAMPLES.items():
            raw = subprocess.check_output(base + ['exec-out', 'run-as', PACKAGE, 'cat', 'cache/' + filename], timeout=45)
            (output / filename).write_bytes(raw)
            samples[name] = json.loads(raw)
        validate_instrumentation(completed.stdout.decode(errors='replace'), 4)
        result['sample_counts'] = verify_samples(samples)
        for filename in ['adaptive-search-window-360.png', 'adaptive-search-window-600.png',
                         'adaptive-search-window-900.png', 'explore-window-360-600.png',
                         'explore-window-840-360.png', 'explore-window-1200-800.png']:
            raw = subprocess.check_output(base + ['exec-out', 'run-as', PACKAGE, 'cat', 'cache/' + filename], timeout=45)
            if not raw.startswith(b'\x89PNG\r\n\x1a\n'):
                raise RuntimeError('Missing screenshot: ' + filename)
            (output / filename).write_bytes(raw)
        after = capture()
        if after['source_sha256'] != source['source_sha256'] or after['apks'] != source['apks']:
            raise RuntimeError('Source or build artifacts changed during testing')
        result.update(checks_passed=True, tests=4)
    except BaseException as error:
        failure = error
        result.update(status='failed', error=repr(error))
    finally:
        try:
            for kind in ['size', 'density']:
                if run('shell', 'wm', kind) != before[kind]:
                    match = re.search(r'Override ' + kind + r': ([0-9x]+)', before[kind])
                    run('shell', 'wm', kind, match[1] if match else 'reset')
            result['restore_repairs'] = restore_settings(before['settings'], setting, put)
            if any(run('shell', 'wm', k) != before[k] for k in ['size', 'density']):
                raise RuntimeError('Original display did not restore')
            result['settings_restored'] = True
            for package in [TEST_PACKAGE, PACKAGE]:
                if not absent(package):
                    if installed_hash(package) != hashes[package]:
                        raise RuntimeError('Refusing to remove an unexpected package: ' + package)
                    if run('uninstall', package) != 'Success' or not absent(package):
                        raise RuntimeError('Unable to remove owned package: ' + package)
                    result['uninstalled'].append(package)
            if not all(absent(package) for package in packages):
                raise RuntimeError('A project package remains on the dedicated emulator')
            result['all_project_packages_absent'] = True
        except BaseException as cleanup_error:
            result.update(status='failed', cleanup_error=repr(cleanup_error))
            if failure is None:
                failure = cleanup_error
        if failure is None:
            result['status'] = 'passed'
        (output / 'evidence.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    if failure is not None:
        raise failure
    print('PASS: four adaptive-window tests; 61 samples and original settings restored')


if __name__ == '__main__':
    main()
