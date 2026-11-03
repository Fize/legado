#!/usr/bin/env python3
"""Run the small, self-contained device compatibility gate used by CI."""
import argparse
import hashlib
import json
import math
from pathlib import Path
import re
import subprocess
import sys
import zipfile

from android_device_checks import ROOT, add_apk_arguments, resolve_apks
from capture_android_compatibility import capture
from check_android_restore_safety import validate_instrumentation
from check_android_source_ime import PROFILES as SOURCE_IME_PROFILES, verify_source_ime_sample, verify_source_import_ime_sample

DRAFT_CASES = (
    'deletingLastMutableHistoryThenTypingKeepsTheEntireDraftAndConfirmation',
    'deletingLastObservableHistoryKeepsDraftBeforeAnotherKeystroke',
    'addingAndSelectingHistoryKeepsOneDraftAcrossEditorModes',
    'nativeKeysContinueInTheDraftAfterDeletingTheLastMutableHistory',
    'productionOnlineImportRemovesHistoryImmediatelyAndKeepsNativeTypingAndCancel',
    'nativeRapidUriInputWithInitialHistory',
    'nativeRapidUriInputWithoutInitialHistory',
)

BOOKMARK_DRAFT_FILES = {case: 'bookmark-draft-' + case + '.json' for case in ('cancel', 'confirm')}
BOOKMARK_DRAFT_STATE_FILES = {case: 'bookmark-draft-state-' + case + '.json' for case in ('cancel', 'confirm')}


def verify_bookmark_draft_samples(records):
    original = {'text': 'original content', 'note': 'original note'}
    edited = {'text': 'edited content', 'note': 'edited note'}
    added = {'text': 'new content', 'note': 'new note'}
    expected = {
        'cancel': [original, edited, edited, original, {'text': '', 'note': ''}],
        'confirm': [original, added, added, {'text': 'new content', 'note': 'new note-fresh'}],
    }
    if not isinstance(records, dict) or set(records) != set(expected):
        raise ValueError('Missing bookmark draft recreation diagnostics')
    for case, rows in expected.items():
        if records[case] != {'case': case, 'samples': rows}:
            raise ValueError('Bookmark draft characters or recreation stages differ: ' + case)

SOURCE_TARGET_FILES = {
    'large': 'source-target-large-state.json',
    'missing': 'source-target-missing-state.json',
    'legacy': 'source-target-legacy-conversion.json',
    'fault': 'source-target-legacy-fault.json',
    'inactive': 'source-target-inactive-task.json',
}

SOURCE_LAYOUT_FILES = {case: 'source-target-recovery-' + case + '.json'
                       for case in ['missing', 'legacy', 'valid']}

CHARSET_UNICODE_PHRASE = '中文编码回归，繁體閱讀！扩展𠮷与表情😀。'

SOURCE_EDITOR_DIAGNOSTICS = [
    'source-target-recovery-valid-editor-' + stage + suffix
    for stage in ['opened', 'recreated-0', 'recreated-1']
    for suffix in ['-before-wait.png', '-failure.png', '-failure-tree.txt', '-failure-ime.txt']
]

# The keyboard recreation test writes these even when its assertion fails.
# Keep them outside SOURCE_LAYOUT_FILES: that set has a separate passing schema.
SOURCE_KEYBOARD_DIAGNOSTICS = [
    'source-target-recovery-keyboard.json',
    'source-target-recovery-keyboard-failure.png',
    'source-target-recovery-keyboard-live-failure.png',
    'source-target-recovery-keyboard-live-window.txt',
    'source-target-recovery-keyboard-live-ime.txt',
    'source-target-recovery-keyboard-live-tree.txt',
] + [
    f'source-target-recovery-keyboard-before-back-{round}{suffix}'
    for round in range(2)
    for suffix in ('.png', '-window.txt', '-ime.txt')
]

LEGACY_INPUT_FAILURE_FILES = [
    'legacy-input-' + case + '-failure' + suffix
    for case in ['keyword', 'charset']
    for suffix in ['-profiles.json', '-tree.txt', '-window.txt', '-input_method.txt', '.png']
]



ONLINE_FAILURE_FILES = [
    'online-import-' + case + '-failure' + suffix
    for case in ('draftAndContinuedTypingSurviveTwoHostRecreationsAndCancel',
                 'confirmationAfterHostRecreationOpensTheExactInlineSourceReview')
    for suffix in ('-tree.txt', '.png', '-window.txt', '-input_method.txt', '-activity.txt')
]

CURRENT_INPUT_FILE = 'input-current-confirm.json'
CURRENT_INPUT_MODES = ('below-editor', 'plain', 'history', 'password', 'multiline')
LONG_INPUT_FILE = 'long-native-input.json'
LONG_INPUT_SHA256 = '88e99c168da3dc74e6535b2abf256a5da6aeb6c7f735fdf142e44664a4331a12'
LOGIN_TOGGLE_CLASS = 'io.legado.app.ui.login.SourceLoginEmptyToggleUiTest'
LOGIN_TOGGLE_TEST = 'emptyChoicesAreDisabledAndNormalChoicesStillCycleAfterRecreation'
PARTIAL_IMPORT_TESTS = (
    'partialSharedBookCopyReportsFailureAndKeepsSuccessfulBook',
    'partialSharedBookCopyShowsActualFailureCount',
)
SEARCH_MENU_TESTS = {
    'io.legado.app.ui.book.read.SearchMenuAccessibilityTest': (
        'bottomActionsFitLargeTextAcrossPhoneAndWideWindows',
        'emptyResultsDisableNavigationAndControlsUseLocalizedLabels',
    ),
    'io.legado.app.ui.book.read.SearchMenuReaderUiTest': (
        'contentSearchReturnsAndNavigatesBetweenActualResults',
    ),
    'io.legado.app.ui.book.read.SearchMenuResultPositionUiTest': (
        'importedSearchResultAfterReaderReconfigurationLandsOnItsOwnText',
    ),
    'io.legado.app.ui.book.searchContent.SearchContentShortWindowUiTest': (
        'resultsAndQueryRemainReachableInShortWindow',
        'resultAnchorSurvivesChangingBetweenShortAndRegularHeight',
    ),
}

VIDEO_REGRESSION_TESTS = {
    'io.legado.app.ui.video.VideoFullscreenPendingSeekTest': ('fullscreenReceivesTheSeekWaitingForPreparation',),
    'io.legado.app.help.gsyVideo.Exo2MediaPlayerStateTest': ('playIntentReasonsDoNotPretendThePlayerIsReadyOrEnded',),
    'io.legado.app.ui.video.VideoChapterLargeTextUiTest': ('twoLineTitlesRemainReadableAndClickableAtLargeFontScale',),
    'io.legado.app.ui.video.FloatingVideoHandoffUiTest': ('delayedContentStartsTheFullscreenPlayerAtTheSavedPosition',),
}


def verify_partial_import_instrumentation(text):
    validate_instrumentation(text, len(PARTIAL_IMPORT_TESTS))
    names = re.findall(r'^INSTRUMENTATION_STATUS: test=(.+)$', text, re.M)
    if sorted(names) != sorted(PARTIAL_IMPORT_TESTS * 2):
        raise ValueError('Partial import test identities are missing or unexpected')


def verify_login_toggle_instrumentation(text):
    validate_instrumentation(text, 1)
    classes = re.findall(r'^INSTRUMENTATION_STATUS: class=(.+)$', text, re.M)
    names = re.findall(r'^INSTRUMENTATION_STATUS: test=(.+)$', text, re.M)
    if classes != [LOGIN_TOGGLE_CLASS] * 2 or names != [LOGIN_TOGGLE_TEST] * 2:
        raise ValueError('Login toggle test identity is missing or unexpected')


def verify_search_menu_instrumentation(text):
    expected = {(cls, name) for cls, names in SEARCH_MENU_TESTS.items() for name in names}
    validate_instrumentation(text, len(expected))
    observed = []
    for block in re.split(r'^INSTRUMENTATION_STATUS_CODE: [^\n]+$', text, flags=re.M):
        classes = re.findall(r'^INSTRUMENTATION_STATUS: class=(.+)$', block, re.M)
        names = re.findall(r'^INSTRUMENTATION_STATUS: test=(.+)$', block, re.M)
        if classes or names:
            if len(classes) != 1 or len(names) != 1:
                raise ValueError('Search menu test identities are missing or ambiguous')
            observed.append((classes[0], names[0]))
    if sorted(observed) != sorted(list(expected) * 2):
        raise ValueError('Search menu test identities are missing or unexpected')


def verify_video_regression_instrumentation(text):
    expected = {(cls, name) for cls, names in VIDEO_REGRESSION_TESTS.items() for name in names}
    validate_instrumentation(text, len(expected))
    observed = []
    for block in re.split(r'^INSTRUMENTATION_STATUS_CODE: [^\n]+$', text, flags=re.M):
        classes = re.findall(r'^INSTRUMENTATION_STATUS: class=(.+)$', block, re.M)
        names = re.findall(r'^INSTRUMENTATION_STATUS: test=(.+)$', block, re.M)
        if classes or names:
            if len(classes) != 1 or len(names) != 1:
                raise ValueError('Video test identities are missing or ambiguous')
            observed.append((classes[0], names[0]))
    if sorted(observed) != sorted(list(expected) * 2):
        raise ValueError('Video test identities are missing or unexpected')


def verify_native_key_trace(diagnostic):
    trace = diagnostic.get('native_key_trace')
    stages = ['first-http-before', 'first-http-after', 'second-http-before', 'second-http-after']
    if (not isinstance(trace, list) or len(trace) != len(stages)
            or not all(isinstance(item, dict) for item in trace)
            or [item.get('stage') for item in trace] != stages):
        raise ValueError('Missing native input timing diagnostics')
    if any(type(item.get(key)) is not int or item[key] <= 0 for item in trace for key in ('wall_time_ms', 'uptime_ms')):
        raise ValueError('Invalid native input timing diagnostics')
    if any(a['uptime_ms'] > b['uptime_ms'] for a, b in zip(trace, trace[1:])):
        raise ValueError('Native input timing diagnostics are out of order')


def inactive_connection_events(log, diagnostic, pids):
    """Keep only generic inactive-connection warnings near the test's key bursts."""
    trace = diagnostic.get('native_key_trace', [])
    if not isinstance(trace, list) or not isinstance(pids, set) or not pids:
        return []
    by_stage = {row.get('stage'): row.get('wall_time_ms') for row in trace if isinstance(row, dict)}
    windows = []
    for name in ('first-http', 'second-http'):
        start, end = by_stage.get(name + '-before'), by_stage.get(name + '-after')
        if type(start) is int and type(end) is int and 0 < start <= end <= start + 60_000:
            windows.append((start - 1000, end + 1000))
    pattern = re.compile(r'^\s*(\d+)\.(\d{3,9})\s+(\d+)\s+\d+\s+W\s+RemoteInputConnectionImpl:\s+'
                         r'(commitText|sendKeyEvent) on inactive InputConnection\s*$')
    result = []
    for line in log.splitlines():
        match = pattern.fullmatch(line)
        if not match or int(match[3]) not in pids:
            continue
        time_ms = int(match[1]) * 1000 + int(match[2][:3])
        if any(start <= time_ms <= end for start, end in windows):
            result.append({'wall_time_ms': time_ms, 'pid': int(match[3]), 'operation': match[4]})
    return result


def recorded_trace_pids(diagnostic):
    """Use the test process identity recorded during input, not a later process."""
    trace = diagnostic.get('native_key_trace')
    if not isinstance(trace, list) or not trace:
        return set()
    pids = [row.get('pid') for row in trace if isinstance(row, dict)]
    if len(pids) != len(trace) or any(type(pid) is not int or pid <= 0 for pid in pids):
        return set()
    return set(pids) if len(set(pids)) == 1 else set()


def verify_long_input_sample(record, expected_api):
    if (type(expected_api) is not int or not isinstance(record, dict)
            or record.get('status') != 'passed' or record.get('error') is not None
            or type(record.get('api')) is not int or record['api'] != expected_api
            or record.get('keyboard_visible_before_input') is not True
            or record.get('native_input_method') != 'instrumentation_send_string_sync'
            or record.get('dismissed') is not True):
        raise ValueError('Missing long native-input evidence or wrong platform')
    payload = record.get('payload')
    if (not isinstance(payload, str) or len(payload) != 571
            or hashlib.sha256(payload.encode('utf-8')).hexdigest() != LONG_INPUT_SHA256
            or record.get('confirmed') != payload):
        raise ValueError('Long input or confirmation differs from the fixture')
    rows = record.get('samples')
    offsets = range(0, len(payload), 96)
    if not isinstance(rows, list) or len(rows) != len(offsets):
        raise ValueError('Missing long native-input segments')
    for offset, row in zip(offsets, rows):
        expected = payload[:min(len(payload), offset + 96)]
        if (not isinstance(row, dict) or type(row.get('offset')) is not int
                or row['offset'] != offset or row.get('expected') != expected
                or row.get('observed') != expected):
            raise ValueError('Long native-input segment lost or changed characters')


def verify_current_input_sample(record, expected_api):
    if (type(expected_api) is not int or not isinstance(record, dict)
            or record.get('status') != 'passed' or record.get('error') is not None
            or type(record.get('api')) is not int or record['api'] != expected_api):
        raise ValueError('Missing current-input confirmation evidence or wrong platform')
    rows = record.get('samples')
    if not isinstance(rows, list) or len(rows) != len(CURRENT_INPUT_MODES):
        raise ValueError('Missing current-input field variants')
    for mode, row in zip(CURRENT_INPUT_MODES, rows):
        expected = 'after-' + mode + '-fresh'
        if (not isinstance(row, dict) or row.get('mode') != mode
                or row.get('before') != 'before-' + mode
                or row.get('expected') != expected or row.get('actual') != expected
                or any(row.get(key) is not True for key in (
                    'set_text_accepted', 'confirm_action_accepted', 'same_ui_turn'))
                or type(row.get('dismiss_count')) is not int or row['dismiss_count'] != 1):
            raise ValueError('Confirmation did not deliver the current accepted editor input')


def verify_charset_unicode_sample(record):
    if not isinstance(record, dict):
        raise ValueError('Missing Unicode charset diagnostics')
    expected = dict(fixture_encoding='GB18030', unicode_expected=CHARSET_UNICODE_PHRASE,
                    unicode_body_excerpt=CHARSET_UNICODE_PHRASE,
                    wrong_encoding_verified=True, unicode_body_confirmed=True)
    if any(type(record.get(k)) is not type(v) or record[k] != v for k, v in expected.items()):
        raise ValueError('Incomplete wrong/correct Unicode charset evidence')


def verify_source_layout_samples(samples, expected_scale, short_landscape=False):
    if not isinstance(samples, dict) or set(samples) != set(SOURCE_LAYOUT_FILES):
        raise ValueError('Missing source recovery layout diagnostics')

    def number(value):
        return type(value) in [int, float] and math.isfinite(value)

    for case, record in samples.items():
        if not isinstance(record, dict):
            raise ValueError('Invalid source layout record: ' + case)
        exact = dict(case=case, status='passed', requested_short_landscape=short_landscape,
                     recreations=2, keyword_unchanged=True, no_check_session_started=True)
        exact.update(dict(draft_preserved=True, exact_target_set_preserved=True, pointer_cancel_closed_dialog=True)
                     if case == 'valid' else dict(pointer_confirm_closed_dialog=True))
        if any(type(record.get(k)) is not type(v) or record[k] != v for k, v in exact.items()):
            raise ValueError('Incomplete source layout result: ' + case)
        stages = ['opened', 'editor-visible' if case == 'valid' else 'error-visible',
                  'recreated-0', 'recreated-1', 'before-cancel' if case == 'valid' else 'before-confirm']
        profiles = record.get('profiles')
        if (not isinstance(profiles, list) or len(profiles) != 5
                or any(not isinstance(row, dict) for row in profiles)
                or [row.get('stage') for row in profiles] != stages):
            raise ValueError('Missing source layout window samples: ' + case)
        for row in profiles:
            if (any(type(row.get(k)) is not int or row[k] <= 0
                    for k in ['width_px', 'height_px', 'width_dp', 'height_dp', 'density_dpi'])
                    or not number(row.get('font_scale')) or abs(row['font_scale'] - expected_scale) >= .01):
                raise ValueError('Invalid source layout window/font sample: ' + case)
            if short_landscape and (row['width_px'], row['height_px'], row['density_dpi']) != (2424, 1080, 540):
                raise ValueError('Source layout short window not applied')
        keys = ['actions_after_recreation_0', 'actions_after_recreation_1',
                'before_cancel_actions' if case == 'valid' else 'before_confirm_actions']
        if case != 'valid':
            keys.insert(0, 'initial_actions')
        labels = None
        for key in keys:
            actions = record.get(key)
            if not isinstance(actions, list) or len(actions) != 3 or any(not isinstance(a, dict) for a in actions):
                raise ValueError('Missing source layout actions: ' + case + '/' + key)
            current = [a.get('label') for a in actions]
            if (not all(isinstance(label, str) and label for label in current) or len(set(current)) != 3
                    or labels is not None and labels != current):
                raise ValueError('Changed source layout actions: ' + case)
            labels = current
            for action in actions:
                if (action.get('text_not_clipped') is not True or any(
                        not number(action.get(k)) or action[k] <= 0 for k in ['width', 'height'])):
                    raise ValueError('Clipped source layout action: ' + case)
        measurements = record.get('action_measurements')
        if (not isinstance(measurements, list) or len(measurements) != len(keys) * 3
                or any(not isinstance(m, dict) for m in measurements)
                or [m.get('label') for m in measurements] != labels * len(keys)):
            raise ValueError('Missing source layout text measurements: ' + case)
        for measurement in measurements:
            layouts = measurement.get('layouts')
            if not isinstance(layouts, list) or not layouts:
                raise ValueError('Missing source action text layout')
            for layout in layouts:
                if not isinstance(layout, dict):
                    raise ValueError('Invalid source action text layout')
                rights = layout.get('line_rights')
                if (any(not number(layout.get(k)) or layout[k] <= 0 for k in ['width', 'height', 'last_line_bottom'])
                        or layout.get('overflow_height') is not False
                        or type(layout.get('line_count')) is not int or layout['line_count'] <= 0
                        or not isinstance(rights, list) or len(rights) != layout['line_count']
                        or any(not number(right) or right < -1 or right > layout['width'] + 1 for right in rights)
                        or layout['last_line_bottom'] > layout['height'] + 1):
                    raise ValueError('Clipped source action text measurement')


def verify_source_target_samples(samples, expected_api):
    if not isinstance(samples, dict) or set(samples) != set(SOURCE_TARGET_FILES):
        raise ValueError('Missing source target snapshot diagnostics')
    for case, record in samples.items():
        if not isinstance(record, dict) or record.get('status') != 'passed':
            raise ValueError('Incomplete source target result: ' + case)

    def exact(case, key, expected):
        actual = samples[case].get(key)
        if type(actual) is not type(expected) or actual != expected:
            raise ValueError('Incomplete source target result: ' + case + '/' + key)

    def bounded(case, key, maximum):
        value = samples[case].get(key)
        if type(value) is not int or not 0 < value < maximum:
            raise ValueError('Oversized or missing source target payload: ' + case + '/' + key)

    for case in ['large', 'legacy', 'fault']:
        exact(case, 'targets', 8000)
        exact(case, 'recreations', 2 if case == 'large' else 1)
    for key in ['exact_target_sets_preserved', 'owners_removed_when_host_finishes']:
        exact('large', key, True)
    bounded('large', 'dialog_argument_bytes', 1024)
    for key in ['activity_and_fragment_saved_state_bytes', 'restored_state_bytes_0', 'restored_state_bytes_1']:
        bounded('large', key, 50 * 1024)
    exact('legacy', 'exact_targets_preserved', True)
    for case in ['legacy', 'fault']:
        bounded(case, 'argument_bytes', 1024)
        bounded(case, 'saved_state_bytes', 50 * 1024)
    for case in ['missing', 'fault']:
        for key in ['keyword_unchanged', 'no_check_session_started']:
            exact(case, key, True)
    exact('fault', 'inline_targets_retained', False)
    exact('fault', 'page_failure_reported', True)
    exact('fault', 'snapshot_directory_restored', True)
    exact('inactive', 'api', expected_api)
    exact('inactive', 'selected_task_id', 12345)
    exact('inactive', 'snapshot_preserved', True)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def run_instrumentation(adb, serial, classes, log):
    # Stream output so a host timeout preserves the completed tests and failure context.
    with log.open('wb') as stream:
        subprocess.run([adb, '-s', serial, 'shell', 'am', 'instrument', '-w', '-r',
                        '-e', 'class', classes,
                        'com.xiaobaitu.reader.debug.test/androidx.test.runner.AndroidJUnitRunner'],
                       stdout=stream, stderr=subprocess.STDOUT, check=True, timeout=240)
    return log.read_bytes()


def verify_legacy_input_samples(samples, expected_scale):
    keyword, charset = samples['keyword'], samples['charset']
    if (type(keyword.get('http_requests')) is not int or keyword['http_requests'] != 1
            or not isinstance(keyword.get('confirmed_keyword'), str) or not keyword['confirmed_keyword']
            or keyword['confirmed_keyword'] != keyword.get('http_keyword')
            or keyword.get('activity_recreations') != 6
            or charset.get('activity_recreations') != 4):
        raise ValueError('Incomplete keyword confirmation or recreation diagnostics')
    for key in ['selected_check_passed', 'untouched_full_record_unchanged', 'untouched_check_state_unchanged',
                'configuration_visible_on_current_host', 'configuration_did_not_commit_draft',
                'configuration_pending_detail', 'configuration_cancel_kept_original_settings']:
        if keyword.get(key) is not True:
            raise ValueError('Incomplete keyword/configuration result: ' + key)
    for key in ['current_reader_callback', 'source_file_bytes_unchanged', 'untouched_full_record_unchanged']:
        if charset.get(key) is not True:
            raise ValueError('Incomplete charset result: ' + key)
    if not all(charset.get(key) == 'GB18030' for key in ['confirmed_charset', 'database_charset', 'reopened_charset']):
        raise ValueError('Charset confirmation did not persist in the current book')
    common = ['opened', 'cancel-recreated-0', 'cancel-recreated-1', 'confirm-recreated-0', 'confirm-recreated-1']
    for case, record, stages in [('keyword', keyword, common + ['configuration-visible', 'configuration-recreated-0', 'configuration-recreated-1']),
                                 ('charset', charset, common)]:
        profiles = record.get('profiles', [])
        if ([row.get('stage') for row in profiles] != stages
                or not all(type(row.get('width_px')) is int and row['width_px'] > 0
                           and type(row.get('height_px')) is int and row['height_px'] > 0
                           and type(row.get('font_scale')) in [int, float]
                           and abs(row['font_scale'] - expected_scale) < .01 for row in profiles)):
            raise ValueError('Missing actual window/font samples: ' + case)


def validate_target(app, actual_api, supported_abis, expected_api, expected_abi):
    if actual_api != expected_api:
        raise ValueError(f'Expected API {expected_api}, got {actual_api}')
    if expected_abi not in supported_abis:
        raise ValueError(f'Device does not support {expected_abi}: {supported_abis}')
    with zipfile.ZipFile(app) as apk:
        abis = {name.split('/')[1] for name in apk.namelist()
                if name.startswith('lib/') and name.endswith('.so') and len(name.split('/')) == 3}
    if abis != {expected_abi}:
        raise ValueError(f'Expected a {expected_abi} split APK, got {sorted(abis)}')


def verify_child(directory, identities, expected_tests=3):
    report = json.loads((directory / 'evidence.json').read_text())
    if (report.get('status') != 'passed' or type(report.get('tests')) is not int
            or report['tests'] != expected_tests
            or report.get('checks_passed') is not True
            or report.get('display_settings_restored') is not True
            or report.get('test_artifacts_removed') is not True):
        raise ValueError('Incomplete child check: ' + str(directory))
    installed = {row['package']: row['sha256'] for row in report['installed_apks']}
    if installed != identities or len(report['installed_apks']) != len(identities):
        raise ValueError('Child installed different APKs: ' + str(directory))
    validate_instrumentation((directory / 'instrumentation.log').read_text(), expected_tests)
    return report


def verify_menu_samples(samples):
    if not isinstance(samples, dict) or set(samples) != {'true', 'false', 'ordinary'}:
        raise ValueError('Incomplete menu accessibility cases')
    for name, states in [('true', [True, False]), ('false', [False, True]), ('ordinary', [None, None])]:
        record = samples[name]
        if (not isinstance(record, dict) or type(record.get('pointer_clicks')) is not int
                or record['pointer_clicks'] != 1 or record.get('menu_closed_after_pointer') is not True):
            raise ValueError('Menu pointer action must close and invoke once')
        rows = record.get('snapshots')
        if not isinstance(rows, list) or len(rows) != 2:
            raise ValueError('Missing initial/reopened menu state')
        for row, expected in zip(rows, states):
            if not isinstance(row, dict):
                raise ValueError('Invalid native menu snapshot')
            if expected is None:
                if row.get('checkable') is not False or 'checked' in row:
                    raise ValueError('Ordinary menu action became checkable')
            elif (row.get('checkable') is not True or row.get('checked') is not expected
                  or not isinstance(row.get('class'), str) or not row['class']):
                raise ValueError('Native menu state differs from the visible toggle')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--expected-api', required=True, type=int)
    parser.add_argument('--expected-abi', required=True, choices=['arm64-v8a', 'armeabi-v7a', 'x86_64'])
    parser.add_argument('--output', required=True, type=Path)
    add_apk_arguments(parser)
    args = parser.parse_args()
    resolve_apks(parser, args)
    if not re.fullmatch(r'emulator-\d+', args.serial):
        parser.error('This gate only supports emulators')
    output = args.output.resolve()
    if output.exists() and any(output.iterdir()):
        parser.error('Output must be new or empty')
    output.mkdir(parents=True, exist_ok=True)
    identities = {'com.xiaobaitu.reader.debug': digest(args.app_apk),
                  'com.xiaobaitu.reader.debug.test': digest(args.test_apk)}
    report = {'status': 'running', 'scope': 'Adaptive dialogs, durable source target state, image decoding, and background recovery guards',
              'expected_api': args.expected_api, 'expected_abi': args.expected_abi,
              'app_apk': str(args.app_apk), 'test_apk': str(args.test_apk), 'apks': identities, 'checks': []}
    try:
        source = capture(args.adb, args.serial)
        report['source'] = source
        device = source['device']
        validate_target(args.app_apk, int(device['api']), device['supported_abis'].split(','),
                        args.expected_api, args.expected_abi)
        for profile, scale, density, rotation in [('normal', '1.0', '420', '0'), ('short-large-font', '2.0', '540', '1')]:
            for name, script in [('customization', 'check_android_my_customization.py'),
                                 ('editors', 'check_android_settings_inputs.py')]:
                directory = output / profile / name
                log = output / f'{profile}-{name}-controller.log'
                with log.open('wb') as stream:
                    subprocess.run([sys.executable, '-B', str(ROOT / 'tools' / script), '--adb', args.adb,
                                    '--serial', args.serial, '--app-apk', str(args.app_apk), '--test-apk', str(args.test_apk),
                                    '--font-scale', scale, '--density', density, '--rotation', rotation,
                                    '--output', str(directory)], cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT,
                                   check=True, timeout=600)
                child = verify_child(directory, identities)
                if child['source']['source_sha256'] != source['source_sha256']:
                    raise ValueError('Source changed while device checks were running')
                report['checks'].append({'profile': profile, 'name': name, 'tests': 3,
                                         'report': str(directory.relative_to(output))})
        cleanup_log = output / 'harness-regressions-instrumentation.log'
        menu_files = ['files/compat-menu-toggle/' + name + '.json' for name in ['true', 'false', 'ordinary']]
        draft_files = ['cache/input-suggestion-' + name + '.json' for name in DRAFT_CASES]
        draft_failure_files = ['cache/input-suggestion-' + name + '-failure-' + service + '.txt'
                               for name in DRAFT_CASES for service in ('input_method', 'window')]
        bookmark_files = ['cache/' + name for name in [*BOOKMARK_DRAFT_FILES.values(), *BOOKMARK_DRAFT_STATE_FILES.values()]]
        subprocess.check_call([args.adb, '-s', args.serial, 'shell', 'run-as', 'com.xiaobaitu.reader.debug',
                               'rm', '-f', *menu_files, *draft_files, *draft_failure_files, *bookmark_files, *['cache/' + name for name in ONLINE_FAILURE_FILES]], timeout=30)
        data = run_instrumentation(args.adb, args.serial,
            'io.legado.app.ui.compat.SelectionToolbarUiTest,io.legado.app.ui.compat.MenuToggleAccessibilityTest,io.legado.app.ui.test.CleanupStepsTest,io.legado.app.ui.test.DisplayViewportTest,io.legado.app.ui.photo.PhotoDecodeBoundsTest,io.legado.app.ui.photo.PhotoDecodeFormatsTest,io.legado.app.help.storage.RestoreAudioCacheTest,io.legado.app.ui.config.InputSuggestionDraftUiTest,io.legado.app.ui.config.OnlineImportRecreationUiTest,io.legado.app.ui.code.CodeSettingsRecreationUiTest,io.legado.app.ui.book.source.manage.SourceGroupRecreationUiTest,io.legado.app.ui.book.bookmark.BookmarkDraftRecreationUiTest',
            cleanup_log)
        available = set(subprocess.check_output([args.adb, '-s', args.serial, 'shell', 'run-as',
                                                'com.xiaobaitu.reader.debug', 'ls', '-1', 'cache'], timeout=30).decode().splitlines())
        for remote in draft_failure_files:
            if Path(remote).name in available:
                raw = subprocess.check_output([args.adb, '-s', args.serial, 'exec-out', 'run-as',
                                               'com.xiaobaitu.reader.debug', 'cat', remote], timeout=30)
                (output / Path(remote).name).write_bytes(raw)
        for name in ONLINE_FAILURE_FILES:
            if name in available:
                raw = subprocess.check_output([args.adb, '-s', args.serial, 'exec-out', 'run-as',
                                               'com.xiaobaitu.reader.debug', 'cat', 'cache/' + name], timeout=30)
                (output / name).write_bytes(raw)
        draft_reports = {}
        for case, remote in zip(DRAFT_CASES, draft_files):
            if Path(remote).name not in available:
                continue
            raw = subprocess.check_output([args.adb, '-s', args.serial, 'exec-out', 'run-as',
                                           'com.xiaobaitu.reader.debug', 'cat', remote], timeout=30)
            (output / Path(remote).name).write_bytes(raw)
            draft_reports[case] = json.loads(raw)
        for name in [*BOOKMARK_DRAFT_FILES.values(), *BOOKMARK_DRAFT_STATE_FILES.values()]:
            if name in available:
                raw = subprocess.check_output([args.adb, '-s', args.serial, 'exec-out', 'run-as',
                                               'com.xiaobaitu.reader.debug', 'cat', 'cache/' + name], timeout=30)
                (output / name).write_bytes(raw)
        production = draft_reports.get('productionOnlineImportRemovesHistoryImmediatelyAndKeepsNativeTypingAndCancel', {})
        if any(row.get('stage') == 'settle-timeout' for row in production.get('checks', []) if isinstance(row, dict)):
            # Observational failure evidence only: do not capture arbitrary logcat messages
            # or change the original test's input timing or pass/fail outcome.
            diagnostic = {'status': 'unavailable', 'events': []}
            try:
                pids = recorded_trace_pids(production)
                logs = subprocess.run([args.adb, '-s', args.serial, 'logcat', '-d', '-v', 'epoch',
                                       '-s', 'RemoteInputConnectionImpl:W', '*:S'],
                                      capture_output=True, timeout=30)
                if pids and logs.returncode == 0:
                    diagnostic.update(status='captured', events=inactive_connection_events(
                        logs.stdout.decode(errors='replace'), production, pids))
            except (OSError, subprocess.SubprocessError, UnicodeError):
                pass
            (output / 'input-inactive-connection-events.json').write_text(json.dumps(diagnostic, indent=2) + '\n')
        validate_instrumentation(data.decode(), 54)
        report['harness_batches'] = [{'tests': 54, 'log': cleanup_log.name}]
        bookmark_samples = {case: json.loads((output / name).read_text())
                            for case, name in BOOKMARK_DRAFT_FILES.items()}
        verify_bookmark_draft_samples(bookmark_samples)
        report['bookmark_draft_samples'] = BOOKMARK_DRAFT_FILES
        report['bookmark_draft_state_samples'] = BOOKMARK_DRAFT_STATE_FILES
        if len(draft_reports) != len(DRAFT_CASES):
            raise ValueError('Missing input suggestion draft diagnostics')
        for case, diagnostic in draft_reports.items():
            settled = [row for row in diagnostic.get('checks', []) if row.get('stage') == 'settled']
            if (diagnostic.get('case') != case or not settled
                    or not all(type(row.get('expected')) is str and
                               row.get('editable_text') == row.get('input_text') == row['expected']
                               for row in settled)):
                raise ValueError('Incomplete exact draft character checks: ' + case)
            if case == 'productionOnlineImportRemovesHistoryImmediatelyAndKeepsNativeTypingAndCancel':
                verify_native_key_trace(diagnostic)
        menu_samples = {}
        for name, remote in zip(['true', 'false', 'ordinary'], menu_files):
            raw = subprocess.check_output([args.adb, '-s', args.serial, 'exec-out', 'run-as',
                                           'com.xiaobaitu.reader.debug', 'cat', remote], timeout=30)
            menu_samples[name] = json.loads(raw)
        (output / 'menu-accessibility-samples.json').write_text(json.dumps(menu_samples, indent=2) + '\n')
        verify_menu_samples(menu_samples)
        subprocess.check_call([args.adb, '-s', args.serial, 'shell', 'run-as', 'com.xiaobaitu.reader.debug',
                               'rm', '-f', *menu_files, *draft_files, *bookmark_files, *['cache/' + name for name in ONLINE_FAILURE_FILES]], timeout=30)
        selection = subprocess.check_output([args.adb, '-s', args.serial, 'exec-out', 'run-as',
                                            'com.xiaobaitu.reader.debug', 'cat', 'cache/selection-toolbar.json'],
                                           stderr=subprocess.STDOUT, timeout=30)
        (output / 'selection-toolbar-samples.json').write_bytes(selection)
        samples = json.loads(selection)
        if len(samples) < 12 or not all(row.get('all_actions_touched') is True and row.get('text_not_clipped') is True for row in samples):
            raise ValueError('Incomplete selection toolbar evidence')
        for direction in ['Ltr', 'Rtl']:
            name = 'selection-toolbar-' + direction + '.png'
            image = subprocess.check_output([args.adb, '-s', args.serial, 'exec-out', 'run-as',
                                             'com.xiaobaitu.reader.debug', 'cat', 'cache/' + name], timeout=30)
            if not image.startswith(b'\x89PNG'):
                raise ValueError('Missing selection toolbar image')
            (output / name).write_bytes(image)
        subprocess.check_call([args.adb, '-s', args.serial, 'shell', 'run-as', 'com.xiaobaitu.reader.debug',
                               'rm', '-f', 'cache/selection-toolbar.json', 'cache/selection-toolbar-Ltr.png',
                               'cache/selection-toolbar-Rtl.png'], timeout=30)
        # The expanding service/input suite has its own bounded run; no checks are dropped.
        legacy_files = {case: 'legacy-input-' + case + '-recovery.json' for case in ['keyword', 'charset', 'charset-unicode']}
        subprocess.check_call([args.adb, '-s', args.serial, 'shell', 'run-as', 'com.xiaobaitu.reader.debug',
                               'rm', '-f', *['cache/' + name for name in [*legacy_files.values(), *SOURCE_TARGET_FILES.values(), *SOURCE_LAYOUT_FILES.values(), *SOURCE_EDITOR_DIAGNOSTICS, *SOURCE_KEYBOARD_DIAGNOSTICS, *LEGACY_INPUT_FAILURE_FILES, CURRENT_INPUT_FILE]]], timeout=30)
        legacy_log = output / 'source-input-regressions-instrumentation.log'
        legacy_data = run_instrumentation(args.adb, args.serial,
            'io.legado.app.help.storage.RestoreCheckSourceServiceTest,io.legado.app.ui.config.LegacyInputRecreationUiTest,io.legado.app.ui.config.SourceTargetSavedStateUiTest,io.legado.app.ui.config.SourceTargetRecoveryLayoutUiTest,io.legado.app.ui.config.TextInputCurrentValueUiTest',
            legacy_log)
        available = set(subprocess.check_output([args.adb, '-s', args.serial, 'shell', 'run-as',
                                                'com.xiaobaitu.reader.debug', 'ls', '-1', 'cache'], timeout=30).decode().splitlines())
        legacy_samples = {}
        for case, name in legacy_files.items():
            if name in available:
                raw = subprocess.check_output([args.adb, '-s', args.serial, 'exec-out', 'run-as',
                                               'com.xiaobaitu.reader.debug', 'cat', 'cache/' + name], timeout=30)
                (output / name).write_bytes(raw)
                legacy_samples[case] = json.loads(raw)
        target_samples = {}
        for case, name in SOURCE_TARGET_FILES.items():
            if name in available:
                raw = subprocess.check_output([args.adb, '-s', args.serial, 'exec-out', 'run-as',
                                               'com.xiaobaitu.reader.debug', 'cat', 'cache/' + name], timeout=30)
                (output / name).write_bytes(raw)
                target_samples[case] = json.loads(raw)
        layout_samples = {}
        for case, name in SOURCE_LAYOUT_FILES.items():
            if name in available:
                raw = subprocess.check_output([args.adb, '-s', args.serial, 'exec-out', 'run-as',
                                               'com.xiaobaitu.reader.debug', 'cat', 'cache/' + name], timeout=30)
                (output / name).write_bytes(raw)
                layout_samples[case] = json.loads(raw)
        # Capture failure diagnostics before validating and before the outer owner cleans the package.
        for name in [*SOURCE_EDITOR_DIAGNOSTICS, *SOURCE_KEYBOARD_DIAGNOSTICS, *LEGACY_INPUT_FAILURE_FILES]:
            if name in available:
                raw = subprocess.check_output([args.adb, '-s', args.serial, 'exec-out', 'run-as',
                                               'com.xiaobaitu.reader.debug', 'cat', 'cache/' + name], timeout=30)
                (output / name).write_bytes(raw)
        current_input = None
        if CURRENT_INPUT_FILE in available:
            raw = subprocess.check_output([args.adb, '-s', args.serial, 'exec-out', 'run-as',
                                           'com.xiaobaitu.reader.debug', 'cat', 'cache/' + CURRENT_INPUT_FILE], timeout=30)
            (output / CURRENT_INPUT_FILE).write_bytes(raw)
            current_input = json.loads(raw)
        validate_instrumentation(legacy_data.decode(), 23)
        verify_current_input_sample(current_input, args.expected_api)
        verify_source_target_samples(target_samples, args.expected_api)
        if set(legacy_samples) != set(legacy_files):
            raise ValueError('Missing legacy input recovery diagnostics')
        scale = device['font_scale']
        verify_legacy_input_samples(legacy_samples, 1.0 if scale == 'null' else float(scale))
        verify_legacy_input_samples({'keyword': legacy_samples['keyword'], 'charset': legacy_samples['charset-unicode']},
                                    1.0 if scale == 'null' else float(scale))
        verify_charset_unicode_sample(legacy_samples['charset-unicode'])
        verify_source_layout_samples(layout_samples, 1.0 if scale == 'null' else float(scale))
        report['harness_batches'].append({'tests': 23, 'log': legacy_log.name})
        report['legacy_input_samples'] = legacy_files
        report['current_input_sample'] = CURRENT_INPUT_FILE
        report['source_target_samples'] = SOURCE_TARGET_FILES
        report['source_layout_samples'] = SOURCE_LAYOUT_FILES
        # This slower native-key check has its own bounded run so it cannot
        # consume the existing 54- or 22-test batches' time budgets.
        remote = 'cache/' + LONG_INPUT_FILE
        subprocess.check_call([args.adb, '-s', args.serial, 'shell', 'run-as',
                               'com.xiaobaitu.reader.debug', 'rm', '-f', remote], timeout=30)
        long_log = output / 'long-native-input-instrumentation.log'
        try:
            long_data = run_instrumentation(args.adb, args.serial,
                'io.legado.app.ui.config.LongNativeInputUiTest', long_log)
        finally:
            try:
                diagnostic = subprocess.run([args.adb, '-s', args.serial, 'exec-out', 'run-as',
                                             'com.xiaobaitu.reader.debug', 'cat', remote],
                                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
                if diagnostic.returncode == 0:
                    (output / LONG_INPUT_FILE).write_bytes(diagnostic.stdout)
            except (OSError, subprocess.SubprocessError) as failure:
                (output / 'long-native-input-diagnostic-error.txt').write_text(repr(failure) + '\n')
        validate_instrumentation(long_data.decode(), 1)
        long_record = json.loads((output / LONG_INPUT_FILE).read_text())
        verify_long_input_sample(long_record, args.expected_api)
        subprocess.check_call([args.adb, '-s', args.serial, 'shell', 'run-as',
                               'com.xiaobaitu.reader.debug', 'rm', '-f', remote], timeout=30)
        remaining = subprocess.check_output([args.adb, '-s', args.serial, 'shell', 'run-as',
                                             'com.xiaobaitu.reader.debug', 'ls', '-1', 'cache'], timeout=30)
        if LONG_INPUT_FILE in remaining.decode().splitlines():
            raise ValueError('Long native-input diagnostic was not removed from device')
        report['harness_batches'].append({'tests': 1, 'log': long_log.name})
        report['long_native_input_sample'] = LONG_INPUT_FILE
        toggle_log = output / 'login-empty-toggle-instrumentation.log'
        toggle_data = run_instrumentation(args.adb, args.serial, LOGIN_TOGGLE_CLASS, toggle_log)
        verify_login_toggle_instrumentation(toggle_data.decode())
        report['harness_batches'].append({'tests': 1, 'log': toggle_log.name})
        partial_log = output / 'partial-shared-import-instrumentation.log'
        partial_class = 'io.legado.app.ui.association.SharedFileImportTest#'
        partial_data = run_instrumentation(
            args.adb, args.serial,
            ','.join(partial_class + name for name in PARTIAL_IMPORT_TESTS), partial_log)
        verify_partial_import_instrumentation(partial_data.decode())
        report['harness_batches'].append({'tests': len(PARTIAL_IMPORT_TESTS), 'log': partial_log.name})
        search_log = output / 'search-menu-instrumentation.log'
        search_data = run_instrumentation(args.adb, args.serial, ','.join(
            f'{cls}#{name}' for cls, names in SEARCH_MENU_TESTS.items() for name in names), search_log)
        verify_search_menu_instrumentation(search_data.decode())
        report['harness_batches'].append({'tests': sum(map(len, SEARCH_MENU_TESTS.values())), 'log': search_log.name})
        video_log = output / 'video-regressions-instrumentation.log'
        video_data = run_instrumentation(args.adb, args.serial, ','.join(
            f'{cls}#{name}' for cls, names in VIDEO_REGRESSION_TESTS.items() for name in names), video_log)
        verify_video_regression_instrumentation(video_data.decode())
        report['harness_batches'].append({'tests': sum(map(len, VIDEO_REGRESSION_TESTS.values())), 'log': video_log.name})
        # Keep every existing batch and its timeout; real keyboard profiles run
        # separately so the expanding suite cannot starve the 54/22-test runs.
        for screen in ('manager', 'import'):
            for profile in SOURCE_IME_PROFILES:
                directory = output / ('source-ime' if screen == 'manager' else 'source-import-ime') / profile
                log = output / f'source-{screen}-ime-{profile}-controller.log'
                with log.open('wb') as stream:
                    subprocess.run([sys.executable, '-B', str(ROOT / 'tools/check_android_source_ime.py'),
                                    '--adb', args.adb, '--serial', args.serial,
                                    '--expected-api', str(args.expected_api), '--profile', profile, '--screen', screen,
                                    '--app-apk', str(args.app_apk), '--test-apk', str(args.test_apk),
                                    '--output', str(directory)], cwd=ROOT, stdout=stream,
                                   stderr=subprocess.STDOUT, check=True, timeout=420)
                child = verify_child(directory, identities, expected_tests=1)
                if child['source']['source_sha256'] != source['source_sha256']:
                    raise ValueError('Source changed while source IME checks were running')
                record = json.loads((directory / f'source-{screen}-ime-{profile}.json').read_text())
                (verify_source_import_ime_sample if screen == 'import' else verify_source_ime_sample)(record, args.expected_api, profile, child['before']['size'])
                report['checks'].append({'profile': profile, 'name': 'source-ime' if screen == 'manager' else 'source-import-ime', 'tests': 1,
                                         'report': str(directory.relative_to(output))})
        if capture(args.adb, args.serial)['source_sha256'] != source['source_sha256']:
            raise ValueError('Source changed while device regressions were running')
        total = sum(row['tests'] for row in report['checks'] + report['harness_batches'])
        if total != 105 + sum(map(len, VIDEO_REGRESSION_TESTS.values())):
            raise ValueError('Incomplete device compatibility batch inventory')
        report.update(status='passed', tests=total, search_menu_tests=6, bookmark_draft_recreation_tests=2, current_input_tests=1, current_input_cases=5, long_native_input_tests=1, long_native_input_characters=571, source_manager_ime_tests=3, source_import_ime_tests=3, real_source_ime_samples=33, unicode_charset_tests=1, source_layout_recovery_tests=3, source_target_state_tests=5, legacy_input_recreation_tests=3, source_group_recreation_tests=4, code_settings_recreation_tests=1, online_import_recreation_tests=2, input_suggestion_draft_tests=len(DRAFT_CASES), selection_toolbar_tests=1, selection_toolbar_samples=len(samples), menu_accessibility_tests=3, cleanup_regression_tests=3, viewport_regression_tests=4, photo_decode_regression_tests=8, source_recovery_regression_tests=10, audio_cache_recovery_tests=19)
    except Exception as failure:
        report.update(status='failed', error=f'{type(failure).__name__}: {failure}')
        raise
    finally:
        (output / 'evidence.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    print(f'PASS API {args.expected_api} {args.expected_abi}: {total} device compatibility and harness checks')


if __name__ == '__main__':
    main()
