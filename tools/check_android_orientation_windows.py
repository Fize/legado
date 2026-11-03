#!/usr/bin/env python3
"""Opt in to real API 36+ emulator display changes; verify reader/video/WebView state and restore settings."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import subprocess
import tarfile
from check_android_restore_safety import validate_instrumentation

PACKAGE = 'com.xiaobaitu.reader.debug'
TEST_PACKAGE = PACKAGE + '.test'
CLASSES = [
    'io.legado.app.ui.book.read.PlatformReaderEntryUiTest#matureReaderKeepsItsAnchorWhenLargeScreenIgnoresPortraitLock',
    'io.legado.app.ui.book.read.PlatformMediaEntryUiTest#actualVideoKeepsPlayingAcrossIgnoredOrientationAndLargeScreenResize',
    'io.legado.app.ui.compat.WebViewOrientationUiTest',
]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--associated', action='store_true', help='Run all 11 related reader/media/browser tests, including the three display probes')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    base = [args.adb, '-s', args.serial]

    def adb(*words, check=True, timeout=30):
        result = subprocess.run(base + list(words), stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, timeout=timeout)
        if check and result.returncode:
            raise RuntimeError((result.stdout + result.stderr).decode(errors='replace'))
        return result.stdout

    def settings():
        return {'size': adb('shell', 'wm', 'size').decode().strip(),
                'density': adb('shell', 'wm', 'density').decode().strip(),
                'rotation': {key: adb('shell', 'settings', 'get', 'system', key).decode().strip()
                             for key in ['accelerometer_rotation', 'user_rotation']}}

    def folders():
        text = adb('shell', 'run-as', PACKAGE, 'ls', 'cache/orientation-compat', check=False).decode()
        return {name for name in text.splitlines()
                if re.fullmatch(r'(text-reader|native-video|web-document)-[a-f0-9-]{36}', name)}

    def installed_hash(package):
        path = adb('shell', 'pm', 'path', package).decode().strip().removeprefix('package:')
        return adb('shell', 'sha256sum', path).decode().split()[0]

    api = int(adb('shell', 'getprop', 'ro.build.version.sdk'))
    assert api >= 36, 'The real ignored-orientation matrix requires API 36+'
    assert adb('shell', 'getprop', 'ro.kernel.qemu').strip() == b'1', 'Display overrides are restricted to emulators'
    selected_classes = list(dict.fromkeys(name.split('#')[0] for name in CLASSES)) if args.associated else CLASSES
    expected_tests = 11 if args.associated else 3
    before = settings()
    previous = folders()
    result = {'serial': args.serial, 'api': api, 'status': 'running', 'original_display': before,
              'debug_apk_sha256': installed_hash(PACKAGE),
              'test_apk_sha256': installed_hash(TEST_PACKAGE), 'host_display_repair_required': False}
    try:
        run = subprocess.run(base + ['shell', 'am', 'instrument', '-w', '-r', '-e',
            'largeScreenOrientation', 'yes', '-e', 'class', ','.join(selected_classes),
            TEST_PACKAGE + '/androidx.test.runner.AndroidJUnitRunner'],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=240)
        (args.output / 'instrumentation.log').write_bytes(run.stdout)
        created = folders() - previous
        assert len(created) == 3, ('Expected exactly three new, owned probe directories', created)
        cases = []
        for name in sorted(created):
            raw = adb('exec-out', 'run-as', PACKAGE, 'tar', '-cf', '-', 'cache/orientation-compat/' + name)
            with tarfile.open(fileobj=io.BytesIO(raw)) as archive:
                for member in archive.getmembers():
                    if not member.isfile():
                        continue
                    relative = Path(member.name)
                    assert relative.parts[:3] == ('cache', 'orientation-compat', name)
                    target = args.output.joinpath(*relative.parts[2:])
                    assert target.resolve().is_relative_to(args.output.resolve())
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(archive.extractfile(member).read())
            directory = args.output / name
            original = json.loads((directory / 'original.json').read_text())
            restored = json.loads((directory / 'restored.json').read_text())
            assert original == restored, ('Test did not restore its display settings', name)
            samples = json.loads((directory / 'samples.json').read_text())
            assert len(samples) == 4
            assert [(s['requestedWindowWidthDp'], s['requestedWindowHeightDp']) for s in samples] == [
                (1280, 800), (800, 1280), (900, 850), (1280, 800)]
            for sample in samples:
                assert sample['smallestWidthDp'] >= 600
                assert (sample['windowWidthPx'] > sample['windowHeightPx']) == (
                    sample['requestedWindowWidthDp'] > sample['requestedWindowHeightDp'])
            assert all(sample['requestedOrientation'] == 1 for sample in samples)
            states = [sample['state'] for sample in samples]
            if name.startswith('text-reader-'):
                assert all(s['rawAnchor'] == s['openedAnchor'] and s['rawAnchor'] > 0 and s['chapter'] == 0 for s in states)
            elif name.startswith('native-video-'):
                assert all(s['playingAndFullscreen'] and s['positionMs'] >= s['openedPositionMs'] for s in states)
                assert states[-1]['positionMs'] > states[0]['positionMs']
            else:
                assert len({s['marker'] for s in states}) == 1
                assert all(s['draft'] == s['marker'] and s['scroll'] > 0 for s in states)
            assert len(list(directory.glob('sample-*.png'))) == 4
            cases.append({'name': name, 'samples': samples, 'own_display_settings_restored': True})
        text = run.stdout.decode(errors='replace')
        validate_instrumentation(text, expected_tests)
        assert settings() == before, 'Final device display differs from its original state'
        result.update(status='passed', cases=cases, tests=expected_tests, window_samples=12)
        for name in created:
            adb('shell', 'run-as', PACKAGE, 'rm', '-r', 'cache/orientation-compat/' + name)
        result['owned_probe_files_removed'] = True
        print(f'PASS: {expected_tests} related tests including 3 real host flows, 12 large-screen window samples; original display restored')
    finally:
        after = settings()
        if after != before:
            result['host_display_repair_required'] = True
            for key in ['size', 'density']:
                match = re.search(r'Override ' + key + r': (\S+)', before[key])
                adb('shell', 'wm', key, match.group(1) if match else 'reset')
            for key, value in before['rotation'].items():
                adb('shell', 'settings', 'delete' if value == 'null' else 'put', 'system', key,
                    *([] if value == 'null' else [value]))
        result['final_display'] = settings()
        (args.output / 'evidence.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')


if __name__ == '__main__':
    main()
