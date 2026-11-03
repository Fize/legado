#!/usr/bin/env python3
"""Verify source-manager or import real-IME actions on an owned emulator display profile."""
import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import struct
import subprocess

from android_device_checks import (add_apk_arguments, resolve_apks, restore_settings,
                                   complete_report)
from capture_android_compatibility import capture
from check_android_restore_safety import validate_instrumentation

PROFILES = {
    'wide': (3000, 2400, 320, 1.0),
    'phone-130': (1080, 2400, 420, 1.3),
    'short-200': (2424, 1080, 540, 2.0),
}
PACKAGE = 'com.xiaobaitu.reader.debug'
SUFFIXES = ('.json', '-opened.png', '-recreated-1.png', '-recreated-2.png',
            '-failure.png', '-failure-tree.txt')


def source_ime_profile(api, profile):
    width, height, density, scale = PROFILES[profile]
    # Android 8 caps a pixel_2's override width at twice its 1080px panel.
    # Request this explicit large-window profile instead of accepting a clamp.
    return (min(width, 2160) if api == 26 else width), height, density, scale


def source_ime_screenshot_size(api, profile, physical_display=None):
    if api != 26:
        return source_ime_profile(api, profile)[:2]
    physical = re.search(r'^Physical size: (\d+)x(\d+)$', physical_display or '', re.MULTILINE)
    if not physical or min(map(int, physical.groups())) <= 0:
        raise ValueError('Missing Android 8 physical display identity')
    # Rotation is locked to zero. screencap returns the physical panel, including
    # letterboxing, while the app's window metrics use the logical override size.
    return tuple(map(int, physical.groups()))


def verify_source_ime_sample(record, expected_api, profile, physical_display=None):
    """A green test alone cannot certify keyboard visibility, safe bounds or scope."""
    if profile not in PROFILES or type(expected_api) is not int:
        raise ValueError('Unknown source IME target')
    if (not isinstance(record, dict) or record.get('status') != 'passed' or record.get('error') is not None
            or record.get('profile') != profile
            or record.get('fixtures_removed') is not True
            or record.get('hardware_ime_setting_restored') is not True):
        raise ValueError('Incomplete source IME result or cleanup')
    rows = record.get('samples')
    if not isinstance(rows, list) or len(rows) != 3:
        raise ValueError('Missing source IME stages')
    width, height, density, scale = source_ime_profile(expected_api, profile)
    image_width, image_height = source_ime_screenshot_size(expected_api, profile, physical_display)

    def rectangle(value):
        if not isinstance(value, str) or not re.fullmatch(r'\[-?\d+,-?\d+\]\[-?\d+,-?\d+\]', value):
            raise ValueError('Invalid source IME rectangle')
        x1, y1, x2, y2 = map(int, re.findall(r'-?\d+', value))
        if x1 >= x2 or y1 >= y2:
            raise ValueError('Empty source IME rectangle')
        return x1, y1, x2, y2

    def inside(rect, bottom):
        if not (0 <= rect[0] < rect[2] <= width and 0 <= rect[1] < rect[3] <= bottom):
            raise ValueError('Source action or editor is covered by the IME/window')

    query = None
    for index, row in enumerate(rows):
        if not isinstance(row, dict):
            raise ValueError('Invalid source IME stage')
        if (row.get('stage') != ('opened' if index == 0 else f'recreated-{index}')
                or type(row.get('api')) is not int or row['api'] != expected_api
                or type(row.get('density_dpi')) is not int or row['density_dpi'] != density
                or type(row.get('font_scale')) not in (int, float)
                or not math.isfinite(row['font_scale']) or abs(row['font_scale'] - scale) >= .01
                or row.get('screenshot_backend') != ('shell-screencap' if expected_api == 26 else 'uiautomation')
                or type(row.get('screenshot_width_px')) is not int or row['screenshot_width_px'] != image_width
                or type(row.get('screenshot_height_px')) is not int or row['screenshot_height_px'] != image_height
                or rectangle(row.get('window')) != (0, 0, width, height)):
            raise ValueError('Wrong source IME stage/system/display profile')
        if (row.get('ime_visible') is not True or type(row.get('ime_bottom_px')) is not int
                or not 0 < row['ime_bottom_px'] < height
                or type(row.get('keyboard_top_px')) is not int
                or row['keyboard_top_px'] != height - row['ime_bottom_px']):
            raise ValueError('Missing actual source IME visibility or height')
        if (not isinstance(row.get('query'), str) or not re.fullmatch(r'ime[0-9a-f]{8}', row['query'])
                or query is not None and row['query'] != query):
            raise ValueError('Source query changed across recreation')
        query = row['query']
        compact = profile == 'short-200'
        controls = row.get('controls')
        if (type(row.get('compact_actions')) is not bool or row['compact_actions'] != compact
                or not isinstance(controls, dict)
                or set(controls) != ({'menu'} if compact else {'menu', 'delete', 'inverse'})):
            raise ValueError('Missing source selection actions')
        editor = rectangle(row.get('editor_bounds'))
        inside(editor, row['keyboard_top_px'])
        for value in controls.values():
            control = rectangle(value)
            inside(control, row['keyboard_top_px'])
            if (editor[0] < control[2] and editor[2] > control[0]
                    and editor[1] < control[3] and editor[3] > control[1]):
                raise ValueError('Source search overlaps selection actions')
        flags = ['native_menu_and_group_pointer_used', 'untouched_source_unchanged']
        if compact:
            flags += ['compact_inverse_round_trip_verified', 'compact_delete_cancel_verified']
        if any(row.get(key) is not True for key in flags):
            raise ValueError('Missing native source action or data-scope proof')
        if row.get('selected_group') != 'keep' + ''.join(f',added-{query[3:]}-{n}' for n in range(index + 1)):
            raise ValueError('Selected source group scope was not preserved')
        visible = row.get('group_menu_ime_visible')
        bottom = row.get('group_menu_keyboard_top_px')
        if type(visible) is not bool or type(bottom) is not int or bottom != (row['keyboard_top_px'] if visible else height):
            raise ValueError('Missing group menu keyboard geometry')
        inside(rectangle(row.get('group_menu_bounds')), bottom)



IMPORT_STAGES = ('cancel', 'cancel-unselected', 'cancel-reselected', 'confirm-opened',
                 'confirm-opened-unselected', 'confirm-opened-reselected',
                 'confirm-recreated-1', 'confirm-recreated-2')


def verify_source_import_ime_sample(record, expected_api, profile, physical_display=None):
    if profile not in PROFILES or type(expected_api) is not int:
        raise ValueError('Unknown import IME target')
    if (not isinstance(record, dict) or record.get('status') != 'passed'
            or record.get('error') is not None or record.get('profile') != profile
            or any(record.get(key) is not True for key in (
                'fixtures_removed', 'preferences_restored', 'hardware_ime_setting_restored',
                'cancel_did_not_import', 'confirm_imported_selected_only'))):
        raise ValueError('Incomplete import IME actions or cleanup')
    rows = record.get('samples')
    if not isinstance(rows, list) or len(rows) != len(IMPORT_STAGES):
        raise ValueError('Missing import IME stages')
    width, height, density, scale = source_ime_profile(expected_api, profile)
    image = source_ime_screenshot_size(expected_api, profile, physical_display)
    query = None
    def rectangle(value):
        if not isinstance(value, str) or not re.fullmatch(r'\[-?\d+,-?\d+\]\[-?\d+,-?\d+\]', value):
            raise ValueError('Invalid import IME rectangle')
        r = tuple(map(int, re.findall(r'-?\d+', value)))
        if not (0 <= r[0] < r[2] <= width and 0 <= r[1] < r[3] <= height):
            raise ValueError('Empty or off-window import IME target')
        return r
    def intersects(a, b):
        return a[0] < b[2] and b[0] < a[2] and a[1] < b[3] and b[1] < a[3]
    for index, row in enumerate(rows):
        if (not isinstance(row, dict) or row.get('stage') != IMPORT_STAGES[index]
                or type(row.get('api')) is not int or row['api'] != expected_api
                or type(row.get('density_dpi')) is not int or row['density_dpi'] != density
                or type(row.get('font_scale')) not in (int, float)
                or not math.isfinite(row['font_scale']) or abs(row['font_scale'] - scale) >= .01
                or row.get('window') != f'[0,0][{width},{height}]'
                or row.get('screenshot_backend') != ('shell-screencap' if expected_api == 26 else 'uiautomation')
                or any(type(row.get(k)) is not int or row[k] != v for k, v in zip(
                    ('screenshot_width_px', 'screenshot_height_px'), image))):
            raise ValueError('Wrong import IME system/window/image identity')
        if (row.get('ime_visible') is not True or type(row.get('ime_bottom_px')) is not int
                or not 0 < row['ime_bottom_px'] < height
                or type(row.get('keyboard_top_px')) is not int
                or row['keyboard_top_px'] != height - row['ime_bottom_px']):
            raise ValueError('Missing real import IME')
        if (not isinstance(row.get('query'), str) or not re.fullmatch(r'needle[0-9a-f]{8}', row['query'])
                or query is not None and query != row['query']):
            raise ValueError('Import search changed across recreation')
        query = row['query']
        editor = rectangle(row.get('editor_bounds'))
        targets = []
        for key in ('controls', 'native_controls'):
            if not isinstance(row.get(key), dict) or set(row[key]) != {'selectAll', 'cancel', 'ok'}:
                raise ValueError('Missing import action targets')
            rects = [rectangle(row[key][name]) for name in ('selectAll', 'cancel', 'ok')]
            if any(intersects(a, b) for i, a in enumerate(rects) for b in rects[:i]):
                raise ValueError('Import actions overlap')
            targets.extend(rects)
        if any(r[3] > row['keyboard_top_px'] for r in [editor] + targets) or any(intersects(editor, r) for r in targets):
            raise ValueError('Import editor or native action is covered')
        for name in ('selectAll', 'cancel', 'ok'):
            logical, native = rectangle(row['controls'][name]), rectangle(row['native_controls'][name])
            x, y = (logical[0] + logical[2]) // 2, (logical[1] + logical[3]) // 2
            if not (native[0] <= x < native[2] and native[1] <= y < native[3]):
                raise ValueError('Native import target differs from drawn action')
        if index in (2, 5, 6, 7) and (row.get('selection') != [True, False, False]
                or any(type(value) is not bool for value in row['selection'])):
            raise ValueError('Import selected targets changed')
        if index in (6, 7) and row.get('native_edit_after_recreate') is not True:
            raise ValueError('Missing native input and deletion after import recreation')

    entries = record.get('native_query_entries')
    if not isinstance(entries, list) or len(entries) != 2:
        raise ValueError('Missing current input connection before native queries')
    for index, entry in enumerate(entries):
        if (not isinstance(entry, dict) or type(entry.get('attempt')) is not int
                or entry['attempt'] != index or entry.get('query') != query
                or entry.get('current_window_input_connection_ready') is not True):
            raise ValueError('Native query was not entered through the current ready connection')


def collect_source_ime_diagnostics(run, output, owned):
    result = {'copied': [], 'errors': []}
    try:
        available = set(run('exec-out', 'run-as', PACKAGE, 'ls', '-1', 'cache').decode().splitlines())
    except (OSError, subprocess.SubprocessError) as failure:
        result['errors'].append({'operation': 'list', 'error': repr(failure)})
        return result
    for name in owned:
        if name in available:
            try:
                (output / name).write_bytes(run('exec-out', 'run-as', PACKAGE, 'cat', 'cache/' + name))
                result['copied'].append(name)
            except (OSError, subprocess.SubprocessError) as failure:
                result['errors'].append({'operation': name, 'error': repr(failure)})
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--expected-api', required=True, type=int)
    parser.add_argument('--profile', required=True, choices=PROFILES)
    parser.add_argument('--screen', choices=('manager', 'import'), default='manager')
    parser.add_argument('--output', required=True, type=Path)
    add_apk_arguments(parser)
    args = parser.parse_args()
    resolve_apks(parser, args)
    if not re.fullmatch(r'emulator-\d+', args.serial):
        parser.error('Source IME probes only support emulators')
    output = args.output.resolve()
    if output.exists() and any(output.iterdir()):
        parser.error('Output must be new or empty')
    output.mkdir(parents=True, exist_ok=True)
    base = [args.adb, '-s', args.serial]

    def run(*words):
        return subprocess.check_output(base + list(words), stderr=subprocess.STDOUT, timeout=45)

    def setting(key):
        return run('shell', 'settings', 'get', *key.split('/')).decode().strip()

    def put(key, value):
        if value != 'null' and not re.fullmatch(r'[0-9.]+', value):
            raise ValueError('Unexpected setting value: ' + key)
        run('shell', 'settings', 'delete' if value == 'null' else 'put', *key.split('/'),
            *(() if value == 'null' else (value,)))

    prefix = 'source-' + args.screen + '-ime-' + args.profile
    stages = IMPORT_STAGES if args.screen == 'import' else ('opened', 'recreated-1', 'recreated-2')
    suffixes = ('.json', '-failure.png', '-failure-tree.txt') + tuple('-' + stage + '.png' for stage in stages)
    owned = [prefix + suffix for suffix in suffixes]
    test_class = ('io.legado.app.ui.association.SourceImportImeUiTest' if args.screen == 'import'
                  else 'io.legado.app.ui.book.source.manage.SourceManagerImeUiTest')
    verifier = verify_source_import_ime_sample if args.screen == 'import' else verify_source_ime_sample
    old = {key: setting(key) for key in ['system/font_scale', 'system/accelerometer_rotation',
                                        'system/user_rotation', 'secure/show_ime_with_hard_keyboard']}
    display = {kind: run('shell', 'wm', kind).decode().strip() for kind in ['size', 'density']}
    report = dict(status='running', tests=1, installed_apks=[], requested_profile=args.profile,
                  before={'settings': old, **display})
    try:
        if run('shell', 'getprop', 'ro.build.version.sdk').decode().strip() != str(args.expected_api):
            raise ValueError('Wrong source IME system')
        for package, apk in [(PACKAGE, args.app_apk), (PACKAGE + '.test', args.test_apk)]:
            if b'Success' not in run('install', '-r', '-t', str(apk)):
                raise RuntimeError('Source IME APK installation failed')
            paths = run('shell', 'pm', 'path', package).decode().splitlines()
            if len(paths) != 1 or not paths[0].startswith('package:/data/app/') or not paths[0].endswith('/base.apk'):
                raise ValueError('Unexpected installed source APK path')
            digest = hashlib.sha256(apk.read_bytes()).hexdigest()
            if run('shell', 'sha256sum', paths[0][8:]).decode().split()[0] != digest:
                raise ValueError('Installed source APK differs from local input')
            report['installed_apks'].append(dict(package=package, sha256=digest))
        run('exec-out', 'run-as', PACKAGE, 'rm', '-f', *['cache/' + name for name in owned])
        width, height, density, scale = source_ime_profile(args.expected_api, args.profile)
        report['requested_display'] = dict(width=width, height=height, density=density, font_scale=scale)
        put('system/accelerometer_rotation', '0')
        run('shell', 'wm', 'size', f'{width}x{height}')
        run('shell', 'wm', 'density', str(density))
        # A new natural display orientation can adjust the rotation lock; set it after geometry.
        put('system/user_rotation', '0')
        put('system/font_scale', str(scale))
        actual_size = re.findall(r'(?:Physical|Override) size: (\d+)x(\d+)', run('shell', 'wm', 'size').decode())
        if not actual_size or tuple(map(int, actual_size[-1])) != (width, height):
            raise ValueError('Requested source IME window was clamped or not applied')
        report['source'] = capture(args.adb, args.serial)
        try:
            with (output / 'instrumentation.log').open('wb') as stream:
                subprocess.run(base + ['shell', 'am', 'instrument', '-w', '-r', '-e', 'sourceImeProfile', args.profile,
                               '-e', 'class', test_class,
                               PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner'],
                               stdout=stream, stderr=subprocess.STDOUT, check=True, timeout=240)
        finally:
            # Keep available diagnostics even when instrumentation fails or times out.
            report['diagnostics'] = collect_source_ime_diagnostics(run, output, owned)
        validate_instrumentation((output / 'instrumentation.log').read_text(), 1)
        record = json.loads((output / (prefix + '.json')).read_text())
        verifier(record, args.expected_api, args.profile, display['size'])
        expected_image = source_ime_screenshot_size(args.expected_api, args.profile, display['size'])
        for stage in stages:
            image = (output / (prefix + '-' + stage + '.png')).read_bytes()
            if not image.startswith(b'\x89PNG\r\n\x1a\n') or len(image) < 24 or struct.unpack('>II', image[16:24]) != expected_image:
                raise ValueError('Missing source IME screenshot or wrong display size')
        if capture()['source_sha256'] != report['source']['source_sha256']:
            raise ValueError('Source changed during the IME probe')
        for entry, apk in zip(report['installed_apks'], [args.app_apk, args.test_apk]):
            if hashlib.sha256(apk.read_bytes()).hexdigest() != entry['sha256']:
                raise ValueError('Source IME input APK changed during the probe')
        report.update(checks_passed=True, sample_file=prefix + '.json', real_ime_samples=len(stages), screen=args.screen)
    except Exception as failure:
        report.update(status='failed', error=f'{type(failure).__name__}: {failure}')
        raise
    finally:
        for kind in ['size', 'density']:
            override = re.search(r'Override ' + kind + r': ([0-9x]+)', display[kind])
            run('shell', 'wm', kind, override[1] if override else 'reset')
        report['setting_restore_repairs'] = restore_settings(old, setting, put)
        after = {kind: run('shell', 'wm', kind).decode().strip() for kind in display}
        report['after'] = dict(settings={key: setting(key) for key in old}, **after)
        report['display_settings_restored'] = after == display and report['after']['settings'] == old
        (output / 'evidence.json').write_text(json.dumps(report, indent=2) + '\n')
        if not report['display_settings_restored']:
            raise RuntimeError('Source IME display/settings did not restore')

    def cleanup():
        run('exec-out', 'run-as', PACKAGE, 'rm', '-f', *['cache/' + name for name in owned])
        remaining = set(run('exec-out', 'run-as', PACKAGE, 'ls', '-1', 'cache').decode().splitlines()) & set(owned)
        if remaining:
            raise RuntimeError('Source IME artifacts remain')

    complete_report(report, output, cleanup)
    print(f'PASS API {args.expected_api} {args.profile}: real IME, native actions and restored settings')


if __name__ == '__main__':
    main()
