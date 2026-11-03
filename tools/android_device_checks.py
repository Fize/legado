"""Shared artifact selection and final-report gates for Android device checks."""
import json
from pathlib import Path
import subprocess
import time

ROOT = Path(__file__).resolve().parent.parent


def add_apk_arguments(parser):
    parser.add_argument('--app-apk', type=Path,
                        default=ROOT / 'app/build/outputs/apk/app/debug/app-app-arm64-v8a-debug.apk')
    parser.add_argument('--test-apk', type=Path,
                        default=ROOT / 'app/build/outputs/apk/androidTest/app/debug/app-app-debug-androidTest.apk')


def resolve_apks(parser, args):
    for name in ['app_apk', 'test_apk']:
        path = getattr(args, name).resolve()
        if not path.is_file():
            parser.error('APK not found: ' + str(path))
        setattr(args, name, path)


def complete_report(report, output, cleanup):
    """Publish success only after device checks, setting restoration and cleanup all finish."""
    try:
        if report.get('checks_passed') is not True:
            raise RuntimeError('Device checks did not pass')
        if report.get('display_settings_restored') is not True:
            raise RuntimeError('Display settings did not restore')
        cleanup()
        report.update(status='passed', test_artifacts_removed=True)
    except Exception as failure:
        report.update(status='failed', test_artifacts_removed=False,
                      error_stage='postconditions', error=str(failure))
        if isinstance(failure, subprocess.CalledProcessError):
            data = failure.output or b''
            report['postcondition_exit_code'] = failure.returncode
            report['postcondition_error_output'] = data.decode(errors='replace') if isinstance(data, bytes) else str(data)
        raise
    finally:
        (output / 'evidence.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')



def restore_settings(old, read, put, *, clock=time.monotonic, pause=time.sleep):
    """Restore owned settings until exact values remain stable after async display updates."""
    deadline = clock() + 10
    stable_since = None
    repairs = []
    for key, value in old.items():
        put(key, value)
    while clock() < deadline:
        actual = {key: read(key) for key in old}
        if actual == old:
            if stable_since is None:
                stable_since = clock()
            if clock() - stable_since >= 1:
                return repairs
        else:
            stable_since = None
            for key, value in old.items():
                if actual[key] != value:
                    repairs.append({'key': key, 'observed': actual[key], 'restored': value})
                    put(key, value)
        pause(.1)
    raise RuntimeError('Display settings did not stabilize at their original values')


def validate_screenshot_backends(samples, api):
    if isinstance(api, dict) and 'error' in api:
        raise ValueError('Unable to read Android API level: ' + str(api['error']))
    if isinstance(api, bool) or not isinstance(api, (int, str)) or not str(api).isdigit() or int(api) < 1:
        raise ValueError('Invalid or missing Android API level: ' + repr(api))
    expected = 'shell-screencap' if int(api) == 26 else 'uiautomation'
    if not samples or any(row.get('screenshot_backend') != expected for row in samples):
        raise ValueError('Wrong or missing screenshot backend: expected ' + expected)
