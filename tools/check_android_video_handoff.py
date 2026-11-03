#!/usr/bin/env python3
"""Verify decoded video handoffs, Room receipt ownership and actual detail returns on emulators."""
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
from check_android_restore_safety import validate_instrumentation

FLOATING_CLASS = 'io.legado.app.ui.video.FloatingVideoHandoffUiTest'
FLOATING_METHODS = [
    'decodedBookKeepsPlayingAcrossActivityFloatingActivityFloating',
    'delayedContentStartsTheFullscreenPlayerAtTheSavedPosition',
    'durableRequestStaysDisplayedUntilTheFinalFloatingPlayerStops',
    'defaultFloatingEntryKeepsTheRequestAcrossTheFirstActivityDestruction',
    'recreatingTheReturnedFullscreenHostDoesNotReplayItsOldHandoff',
    'recoveryDuringTheQueuedFullscreenHandoffDoesNotPublishAReturnOrWriteProgress',
    'stoppingWhileFullscreenClaimIsQueuedCannotLeaveAReaderOrReceiptAlive',
    'aSupersededQueuedStartCannotStopOrSeekTheNewerRequest',
    'aNewPlainInputCannotBeReplacedByAnOlderQueuedOwnedInitialization',
    'aRepeatedFullscreenClickWhileTheClaimIsQueuedIsIgnored',
    'stoppingBeforeTheFloatingReceiverClaimsCannotLeavePlaybackOrAReceiptAlive',
    'anUnownedQueuedFullscreenReceiverCanBeStopped',
    'aQueuedFullscreenReceiverWithAClosedReceiptCanBeStopped',
    'aDefaultUnownedFloatingPlayerCanReturnToFullscreen',
    'aLateFloatingStopAlsoFinishesItsAlreadyPlayingUnownedReceiver',
    'aLateFloatingStopDoesNotFinishANewIndependentInputForTheSameBook',
    'playingFullscreenCheckpointsWithoutLeavingThePageOrFinishingItsReceipt',
    'playingFloatingCheckpointsWithoutClosingItsWindowOrReceipt',
    'pausingAfterASeekSavesTheCurrentPositionBeforeLeavingThePage',
]
CLASSES = [f'{FLOATING_CLASS}#{name}' for name in FLOATING_METHODS] + [
           'io.legado.app.data.navigation.PlatformMediaPreparationTest',
           'io.legado.app.ui.book.read.PlatformMediaReturnSessionTest',
           'io.legado.app.ui.book.read.MediaHandoffReturnRecoveryTest',
           'io.legado.app.ui.book.read.PlatformMediaEntryUiTest#audioDetailUsesTheMatureHostAndKeepsBackgroundPlaybackAfterReturning',
           'io.legado.app.ui.book.read.PlatformMediaEntryUiTest#audioRecoveryStopsBackgroundPlaybackAndRejectsLateTransport',
           'io.legado.app.ui.book.read.PlatformMediaEntryUiTest#audioSameChapterNewRequestSeeksOnceAndSurvivesRecreation',
           'io.legado.app.ui.book.read.PlatformMediaEntryUiTest#videoDetailKeepsTheUnpreparedPositionAcrossRecreationAndReturn',
           'io.legado.app.ui.book.read.PlatformMediaEntryUiTest#actualVideoDecodesAndSavesItsPositionThroughTheDetailReturn',
           'io.legado.app.ui.book.read.PlatformMediaEntryUiTest#defaultFloatingVideoReturnsToTheActualDetailOnlyAfterFinalStop',
           'io.legado.app.ui.book.read.PlatformMediaEntryUiTest#explicitFloatingRoundTripKeepsTheActualDetailRequestUntilPlaybackEnds',
           'io.legado.app.ui.book.read.PlatformMediaEntryUiTest#actualVideoSameBookSeekRotationRecreationAndReturnPreservePositionOnce',
           'io.legado.app.ui.book.read.PlatformMediaEntryUiTest#malformedOwnedMediaRouteCannotOpenAnUnownedPlayer']
EXPECTED_TESTS = 90


def overlay_mode(text):
    found = re.search(r'SYSTEM_ALERT_WINDOW: (allow|deny|ignore|default|foreground)(?=[;\s]|$)', text)
    if found:
        return found.group(1)
    if 'No operations' in text:
        return 'default'
    raise ValueError('Unrecognized overlay app-op response')


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--adb', required=True)
    p.add_argument('--serial', required=True)
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    if not re.fullmatch(r'emulator-\d+', a.serial):
        p.error('This fixture runner only supports emulators')
    if a.output.exists() and any(a.output.iterdir()):
        p.error('Output must be new or empty')
    a.output.mkdir(parents=True, exist_ok=True)
    base = [a.adb, '-s', a.serial]
    def run(*args, timeout=360):
        return subprocess.check_output(base + list(args), stderr=subprocess.STDOUT, timeout=timeout)
    report = {'status': 'running', 'scope': 'Actual H264 decoding and UI handoffs, real Room receipts, detail route returns and queued recovery fault',
              'tests': EXPECTED_TESTS, 'classes': CLASSES, 'installed_apks': [], 'instrumentation_timeout_seconds': 600}
    try:
        for package, apk in [('com.xiaobaitu.reader.debug', ROOT/'app/build/outputs/apk/app/debug/app-app-arm64-v8a-debug.apk'),
                             ('com.xiaobaitu.reader.debug.test', ROOT/'app/build/outputs/apk/androidTest/app/debug/app-app-debug-androidTest.apk')]:
            assert b'Success' in run('install', '-r', '-t', str(apk))
            paths = [line[8:] for line in run('shell', 'pm', 'path', package).decode().splitlines() if line.endswith('/base.apk')]
            assert len(paths) == 1 and re.fullmatch(r'/data/app/[A-Za-z0-9/_=+~.-]+/base\.apk', paths[0])
            actual = run('shell', 'sha256sum', paths[0]).decode().split()[0]
            assert actual == hashlib.sha256(apk.read_bytes()).hexdigest()
            report['installed_apks'].append({'package': package, 'sha256': actual})
        before = overlay_mode(run('shell', 'appops', 'get', 'com.xiaobaitu.reader.debug', 'SYSTEM_ALERT_WINDOW').decode())
        report['source'] = capture(a.adb, a.serial)
        try:
            data = run('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', ','.join(CLASSES),
                       'com.xiaobaitu.reader.debug.test/androidx.test.runner.AndroidJUnitRunner', timeout=600)
        except (subprocess.CalledProcessError, subprocess.TimeoutExpired) as failure:
            # A vanished emulator or failed runner must retain any partial diagnostic output.
            (a.output/'instrumentation.log').write_bytes(failure.output or b'')
            report['runner_exit'] = getattr(failure, 'returncode', 'timeout')
            raise
        (a.output/'instrumentation.log').write_bytes(data)
        after = overlay_mode(run('shell', 'appops', 'get', 'com.xiaobaitu.reader.debug', 'SYSTEM_ALERT_WINDOW').decode())
        report.update({'overlay_appop_before': before, 'overlay_appop_after': after,
                       'overlay_appop_restored': before == after, 'log_sha256': hashlib.sha256(data).hexdigest()})
        assert before == after, 'The owned overlay app-op was not restored'
        validate_instrumentation(data.decode(), EXPECTED_TESTS)
        report['status'] = 'passed'
    except Exception as failure:
        report.update({'status': 'failed', 'error': str(failure)})
        raise
    finally:
        (a.output/'evidence.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(f'PASS {a.serial}: {EXPECTED_TESTS} video/Room/detail handoff checks; installed APKs and restored overlay app-op verified')


if __name__ == '__main__':
    main()
