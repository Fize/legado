#!/usr/bin/env python3
"""Exercise production My customization and saved dialog drafts on an emulator; restore display settings afterwards."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / 'tools'))
from capture_android_compatibility import capture
from android_device_checks import add_apk_arguments, resolve_apks, complete_report, restore_settings, validate_screenshot_backends
from check_android_restore_safety import validate_instrumentation


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
        return subprocess.check_output(base + list(args), stderr=subprocess.STDOUT, timeout=180)
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
        put('font_scale', a.font_scale)
        put('accelerometer_rotation', '0')
        put('user_rotation', a.rotation)
        if a.density:
            run('shell', 'wm', 'density', str(a.density))
        report['source'] = capture(a.adb, a.serial)
        data = run('shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                   'io.legado.app.ui.main.MyCustomizationUiTest',
                   '-e', 'compatSettingsRotation', a.rotation,
                   'com.xiaobaitu.reader.debug.test/androidx.test.runner.AndroidJUnitRunner')
        (out / 'instrumentation.log').write_bytes(data)
        validate_instrumentation(data.decode(), 3)
        traces = []
        missing = []
        for case in ['cancel', 'confirm', 'semantics']:
            name = f'my-customization-{case}'
            raw = run('exec-out', 'run-as', 'com.xiaobaitu.reader.debug', 'cat', f'cache/{name}.json')
            (out / f'{name}.json').write_bytes(raw)
            case_trace = json.loads(raw)
            rows = case_trace['samples']
            for stage in (['editing', 'last'] if case == 'semantics' else ['editing', 'recreated', 'last']):
                pixels = run('exec-out', 'run-as', 'com.xiaobaitu.reader.debug', 'cat', f'cache/{name}-{stage}.png')
                if not pixels.startswith(b'\x89PNG'):
                    missing.append(f'{name}-{stage}')
                else:
                    (out / f'{name}-{stage}.png').write_bytes(pixels)
            traces.extend(rows)
            if case_trace.get('preferences_restored') is not True:
                raise RuntimeError('Application settings were not restored')
        if missing:
            raise RuntimeError(f'Missing screenshots: {missing}')
        if len(traces) != 5 or not all(abs(row['font_scale'] - float(a.font_scale)) < .01 for row in traces):
            raise RuntimeError('Wrong font scale or incomplete trace')
        if a.density and not all(row['density_dpi'] == a.density for row in traces):
            raise RuntimeError('Wrong display density')
        if not all((row['width_dp'] > row['height_dp']) == (a.rotation == '1') for row in traces):
            raise RuntimeError('Requested orientation did not take effect')
        validate_screenshot_backends(traces, report['source']['device']['api'])
        report['status'] = 'checks_passed'
        report['checks_passed'] = True
        report['tests'] = 3
        report['scope'] = 'Production My dialog and More route, recreation drafts, cancel/confirm and checkbox semantics; no manual TalkBack session'
        report['samples'] = traces
    except Exception as error:
        report['status'] = 'failed'
        report['error'] = f'{type(error).__name__}: {error}'
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
    def cleanup():
        names = [f'my-customization-{case}{suffix}'
                 for case in ['cancel', 'confirm', 'semantics']
                 for suffix in (['.json', '-editing.png', '-last.png'] + ([] if case == 'semantics' else ['-recreated.png']))]
        run('shell', 'run-as', 'com.xiaobaitu.reader.debug', 'rm', '-f', *['cache/' + name for name in names])
        remaining = set(run('shell', 'run-as', 'com.xiaobaitu.reader.debug', 'ls', 'cache').decode().splitlines()) & set(names)
        if remaining:
            raise RuntimeError('Owned test artifacts remain: ' + ', '.join(sorted(remaining)))
    complete_report(report, out, cleanup)
    print(f'PASS {a.serial}: 3 My customization checks; drafts, selection semantics and display settings restored')


if __name__ == '__main__':
    main()
