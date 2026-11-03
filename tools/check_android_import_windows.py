#!/usr/bin/env python3
"""Verify production local import across actual freeform task sizes on an explicitly owned emulator."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import uuid

from android_device_checks import add_apk_arguments, resolve_apks, restore_settings
from capture_android_compatibility import capture
from check_android_device_ci import validate_target
from check_android_restore_safety import validate_instrumentation

PACKAGE = 'com.xiaobaitu.reader.debug'
MATRIX = [(360, 480), (320, 320), (320, 240), (600, 320), (840, 320), (360, 480)]
OWNED = ['compact-import-samples.json', 'compact-import-before-delete.png'] + ['compact-import-window-' + str(i) + '.png' for i in range(6)]


def validate_windows(samples, font_scale):
    if len(samples) != len(MATRIX):
        raise ValueError('Incomplete actual window sequence')
    identities = set()
    for row, (width, height) in zip(samples, MATRIX):
        fixture = row.get('fixture')
        try:
            if str(uuid.UUID(fixture)) != fixture:
                raise ValueError('Noncanonical fixture UUID')
        except (ValueError, TypeError, AttributeError) as error:
            raise ValueError('Missing fixture identity') from error
        identities.add((fixture, row.get('task_id'), row.get('activity_identity')))
        if type(row.get('task_id')) is not int or row['task_id'] <= 0 or type(row.get('activity_identity')) is not int or row['activity_identity'] == 0:
            raise ValueError('Missing actual task/activity identity')
        density = row.get('density', 0)
        if not 0 < density <= 10 or abs(row.get('font_scale', 0) - font_scale) > .01:
            raise ValueError('Wrong measured font or density')
        if row.get('width_dp') != width or row.get('height_dp') != height or abs(row.get('actual_width_px', 0) - width * density) > 2 * density or abs(row.get('actual_height_px', 0) - height * density) > 2 * density:
            raise ValueError('Requested sizes alone do not prove task resize')
        if row.get('list_height_px', 0) < 72 * density - 1:
            raise ValueError('No usable file row')
        for key in ['selection_kept', 'last_and_first_reachable', 'reverse_round_trip', 'main_prompt_cancelled', 'delete_menu_reachable']:
            if row.get(key) is not True:
                raise ValueError('Missing actual interaction: ' + key)
        if row.get('directory_entries', 0) < 120 or row.get('quick_actions_available') is not True:
            raise ValueError('Long-folder quick access not verified')
        if row.get('fixed_actions_visible') is not True and any(row.get(key) is not True for key in ['row_shortcut_used', 'shortcut_main_without_list_scroll', 'shortcut_select_all_round_trip', 'shortcut_reverse_round_trip']):
            raise ValueError('Scrolling to the footer is not a nearby action')
        if row.get('fixed_actions_visible') is not True:
            native = row.get('row_menu_native_samples')
            if not isinstance(native, list) or len(native) != 2:
                raise ValueError('Missing actual native menu state and caption bounds')
            for item, checked in zip(native, [False, True]):
                bounds = list(map(int, re.findall(r'-?\d+', item.get('bounds', ''))))
                if (item.get('checkable') is not True or item.get('checked') is not checked
                        or len(bounds) != 4 or type(item.get('safe_top_px')) is not int
                        or type(item.get('window_bottom_px')) is not int
                        or type(item.get('caption_inset_px')) is not int
                        or item['caption_inset_px'] < 0
                        or type(row.get('platform_api')) is not int
                        or row['platform_api'] >= 35 and item['caption_inset_px'] <= 0):
                    raise ValueError('Native menu toggle/caption evidence differs from the actual control')
                if (bounds[1] < item['safe_top_px'] or bounds[3] > item['window_bottom_px']
                        or bounds[3] - bounds[1] < 48 * density - 1):
                    raise ValueError('Native menu is covered or its hit target is clipped')
    if len(identities) != 1:
        raise ValueError('Resize changed the task/activity or mixed fixtures')
    if any(samples[-1].get(key) is not True for key in ['search_draft_kept', 'actual_import_completed', 'imported_body_kept']):
        raise ValueError('Draft/actual imported content not verified')
    if (samples[-1].get('completed_import_window_width_dp'), samples[-1].get('completed_import_window_height_dp')) != (320, 240):
        raise ValueError('Import must complete in the short window')
    if any(samples[-1].get(key) is not True for key in ['row_shortcut_delete_completed', 'unselected_files_preserved']):
        raise ValueError('Scoped shortcut deletion and preservation not verified')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--expected-avd', required=True)
    parser.add_argument('--expected-api', type=int, required=True)
    parser.add_argument('--expected-abi', choices=['arm64-v8a', 'armeabi-v7a', 'x86_64'], required=True)
    parser.add_argument('--font-scale', choices=['1.0', '1.3', '2.0'], default='2.0')
    parser.add_argument('--output', type=Path, required=True)
    add_apk_arguments(parser)
    args = parser.parse_args()
    resolve_apks(parser, args)
    if not re.fullmatch(r'emulator-\d+', args.serial) or args.expected_api < 30:
        parser.error('Explicit owned API30+ freeform emulator required')
    output = args.output.resolve()
    if output.exists() and any(output.iterdir()):
        parser.error('Output must be new or empty')
    output.mkdir(parents=True, exist_ok=True)
    base = [args.adb, '-s', args.serial]

    def run(*words):
        return subprocess.check_output(base + list(words), stderr=subprocess.STDOUT, timeout=300)

    def setting(key):
        return run('shell', 'settings', 'get', 'system', key).decode().strip()

    def put(key, value):
        if value != 'null' and not re.fullmatch(r'[0-9.]+', value):
            raise ValueError('Unexpected setting value')
        run('shell', 'settings', 'delete' if value == 'null' else 'put', 'system', key, *([] if value == 'null' else [value]))

    report = {'status': 'running', 'serial': args.serial, 'expected_avd': args.expected_avd,
              'scope': 'Actual freeform task bounds in production navigation/import, native touch actions, draft retention and completed local import; not physical folding or process recovery',
              'installed_apks': []}
    original = None
    try:
        if run('emu', 'avd', 'name').decode().splitlines()[0] != args.expected_avd:
            raise ValueError('Wrong owned AVD')
        validate_target(args.app_apk, int(run('shell', 'getprop', 'ro.build.version.sdk')),
                        run('shell', 'getprop', 'ro.product.cpu.abilist').decode().strip().split(','), args.expected_api, args.expected_abi)
        if 'feature:android.software.freeform_window_management' not in run('shell', 'pm', 'list', 'features').decode().splitlines():
            raise ValueError('Freeform unsupported; no test was qualified')
        original = {key: setting(key) for key in ['font_scale', 'accelerometer_rotation', 'user_rotation']}
        report['before'] = original
        report['source'] = capture(args.adb, args.serial)
        for package, apk in [(PACKAGE, args.app_apk), (PACKAGE + '.test', args.test_apk)]:
            if b'Success' not in run('install', '-r', '-t', str(apk)):
                raise ValueError('APK install failed')
            path = run('shell', 'pm', 'path', package).decode().strip()
            if not re.fullmatch(r'package:/data/app/[A-Za-z0-9/_=+~.-]+/base\.apk', path):
                raise ValueError('Unexpected package path')
            digest = hashlib.sha256(apk.read_bytes()).hexdigest()
            if run('shell', 'sha256sum', path[8:]).decode().split()[0] != digest:
                raise ValueError('Installed APK differs')
            report['installed_apks'].append({'package': package, 'sha256': digest})
        report['books_before'] = run('shell', 'content', 'query', '--uri', 'content://' + PACKAGE + '.readerProvider/books/query').decode().strip()
        run('shell', 'run-as', PACKAGE, 'rm', '-f', *['cache/' + name for name in OWNED])
        put('font_scale', args.font_scale)
        data = run('shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                   'io.legado.app.ui.book.localimport.LocalImportTaskResizeUiTest', PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner')
        (output / 'instrumentation.log').write_bytes(data)
        validate_instrumentation(data.decode(), 1)
        report['checks_passed'] = True
    except Exception as error:
        report.update(status='failed', error=repr(error))
        raise
    finally:
        try:
            if original is not None:
                for name in OWNED:
                    try:
                        raw = run('exec-out', 'run-as', PACKAGE, 'cat', 'cache/' + name)
                    except subprocess.CalledProcessError as error:
                        report.setdefault('artifact_read_errors', []).append({'name': name, 'output': error.output.decode(errors='replace')})
                        continue
                    if (name.endswith('.png') and raw.startswith(b'\x89PNG')) or (name.endswith('.json') and raw.lstrip().startswith(b'[')):
                        (output / name).write_bytes(raw)
                report['restore_repairs'] = restore_settings(original, setting, put)
                report['after'] = {key: setting(key) for key in original}
                if original != report['after']:
                    raise ValueError('Device settings did not restore')
                report['books_after'] = run('shell', 'content', 'query', '--uri', 'content://' + PACKAGE + '.readerProvider/books/query').decode().strip()
                if 'books_before' in report and report['books_after'] != report['books_before']:
                    raise ValueError('Original book catalog changed')
                run('shell', 'run-as', PACKAGE, 'rm', '-f', *['cache/' + name for name in OWNED])
                report['artifacts_removed'] = not (set(run('shell', 'run-as', PACKAGE, 'ls', 'cache').decode().splitlines()) & set(OWNED))
                if not report['artifacts_removed']:
                    raise ValueError('Owned artifacts remain')
                after = capture(args.adb, args.serial)
                if after['source_sha256'] != report['source']['source_sha256'] or after['apks'] != report['source']['apks']:
                    raise ValueError('Source or APK changed during run')
                (output / 'last-window.txt').write_bytes(run('shell', 'dumpsys', 'window'))
                if report.get('checks_passed'):
                    samples = json.loads((output / 'compact-import-samples.json').read_text())
                    validate_windows(samples, float(args.font_scale))
                    if any(not (output / name).is_file() for name in OWNED if name != 'compact-import-before-delete.png'):
                        raise ValueError('Missing window images')
                    report.update(status='passed', tests=1, window_samples=len(samples))
        except Exception as error:
            report.update(status='failed', postcondition_error=repr(error))
            raise
        finally:
            (output / 'evidence.json').write_text(json.dumps(report, indent=2) + '\n')
    print('PASS actual import task resize: 6 windows, font=' + args.font_scale + ', original data/settings restored')


if __name__ == '__main__':
    main()
