import json
from pathlib import Path
import tempfile
import unittest
import zipfile

from android_device_checks import restore_settings, validate_screenshot_backends
from check_android_device_ci import (validate_target, verify_child, verify_menu_samples,
                                    verify_source_target_samples, verify_source_layout_samples,
                                    verify_charset_unicode_sample, CHARSET_UNICODE_PHRASE, verify_bookmark_draft_samples,
                                    verify_partial_import_instrumentation, PARTIAL_IMPORT_TESTS, verify_native_key_trace,
                                    inactive_connection_events, recorded_trace_pids,
                                    verify_search_menu_instrumentation, SEARCH_MENU_TESTS,
                                    verify_video_regression_instrumentation, VIDEO_REGRESSION_TESTS)


class NativeKeyTraceTest(unittest.TestCase):
    def sample(self):
        stages = ['first-http-before', 'first-http-after', 'second-http-before', 'second-http-after']
        return {'native_key_trace': [dict(stage=stage, wall_time_ms=1000+i, uptime_ms=100+i)
                                     for i, stage in enumerate(stages)]}

    def test_accepts_complete_ordered_trace(self):
        verify_native_key_trace(self.sample())

    def test_rejects_missing_or_reversed_trace(self):
        for trace in (self.sample()['native_key_trace'][:-1], list(reversed(self.sample()['native_key_trace']))):
            with self.assertRaises(ValueError):
                verify_native_key_trace({'native_key_trace': trace})

    def test_filters_inactive_connection_warnings_by_pid_and_burst(self):
        trace = {'native_key_trace': [
            {'stage': 'first-http-before', 'wall_time_ms': 1700000000100},
            {'stage': 'first-http-after', 'wall_time_ms': 1700000000300},
        ]}
        logs = '\n'.join([
            '1700000000.200 123 123 W RemoteInputConnectionImpl: commitText on inactive InputConnection',
            '1700000000.210 456 456 W RemoteInputConnectionImpl: sendKeyEvent on inactive InputConnection',
            '1700000009.000 123 123 W RemoteInputConnectionImpl: commitText on inactive InputConnection',
            '1700000000.220 123 123 W RemoteInputConnectionImpl: commitText private text',
        ])
        self.assertEqual(inactive_connection_events(logs, trace, {123}), [
            {'wall_time_ms': 1700000000200, 'pid': 123, 'operation': 'commitText'}])

    def test_incomplete_burst_or_missing_pid_produces_no_events(self):
        log = '1700000000.200 123 123 W RemoteInputConnectionImpl: commitText on inactive InputConnection'
        self.assertEqual(inactive_connection_events(log, {'native_key_trace': [
            {'stage': 'first-http-before', 'wall_time_ms': 1700000000100}]}, {123}), [])
        self.assertEqual(inactive_connection_events(log, self.sample(), set()), [])

    def test_trace_pid_must_be_positive_and_consistent(self):
        self.assertEqual(recorded_trace_pids({'native_key_trace': [{'pid': 123}, {'pid': 123}]}), {123})
        for trace in ([], [{'pid': 123}, {'pid': 456}], [{'pid': True}], [{'pid': 0}], [{}]):
            self.assertEqual(recorded_trace_pids({'native_key_trace': trace}), set())


class PartialImportGateTest(unittest.TestCase):
    def log(self, names):
        return ''.join(
            f'INSTRUMENTATION_STATUS: test={name}\nINSTRUMENTATION_STATUS_CODE: 1\n'
            f'INSTRUMENTATION_STATUS: test={name}\nINSTRUMENTATION_STATUS_CODE: 0\n'
            for name in names
        ) + '\nOK (2 tests)\n'

    def test_accepts_both_expected_tests(self):
        verify_partial_import_instrumentation(self.log(PARTIAL_IMPORT_TESTS))

    def test_rejects_an_unrelated_test_with_the_same_count(self):
        with self.assertRaisesRegex(ValueError, 'identities'):
            verify_partial_import_instrumentation(self.log((PARTIAL_IMPORT_TESTS[0], 'unrelated')))


class SearchMenuGateTest(unittest.TestCase):
    def log(self, tests):
        return ''.join(
            f'INSTRUMENTATION_STATUS: class={cls}\nINSTRUMENTATION_STATUS: test={name}\n'
            f'INSTRUMENTATION_STATUS_CODE: {code}\n'
            for cls, name in tests for code in (1, 0)
        ) + f'\nOK ({len(tests)} tests)\n'

    def test_accepts_all_expected_search_menu_tests(self):
        tests = [(cls, name) for cls, names in SEARCH_MENU_TESTS.items() for name in names]
        verify_search_menu_instrumentation(self.log(tests))

    def test_rejects_wrong_class_even_with_same_test_count_and_names(self):
        tests = [(cls, name) for cls, names in SEARCH_MENU_TESTS.items() for name in names]
        tests[-1] = ('unrelated.Class', tests[-1][1])
        with self.assertRaisesRegex(ValueError, 'identities'):
            verify_search_menu_instrumentation(self.log(tests))


class VideoRegressionGateTest(unittest.TestCase):
    def log(self, tests):
        return ''.join(
            f'INSTRUMENTATION_STATUS: class={cls}\nINSTRUMENTATION_STATUS: test={name}\n'
            f'INSTRUMENTATION_STATUS_CODE: {code}\n'
            for cls, name in tests for code in (1, 0)
        ) + f'\nOK ({len(tests)} tests)\n'

    def test_accepts_the_expected_video_regressions(self):
        tests = [(cls, name) for cls, names in VIDEO_REGRESSION_TESTS.items() for name in names]
        verify_video_regression_instrumentation(self.log(tests))

    def test_rejects_replacing_the_delayed_load_test(self):
        tests = [(cls, name) for cls, names in VIDEO_REGRESSION_TESTS.items() for name in names]
        tests[-1] = (tests[-1][0], 'unrelatedTest')
        with self.assertRaisesRegex(ValueError, 'identities'):
            verify_video_regression_instrumentation(self.log(tests))


class DeviceCiTargetTest(unittest.TestCase):
    def apk(self, folder, abi):
        path = Path(folder) / 'app.apk'
        with zipfile.ZipFile(path, 'w') as output:
            output.writestr(f'lib/{abi}/libfixture.so', b'fixture')
        return path

    def test_accepts_matching_x86_split_and_device(self):
        with tempfile.TemporaryDirectory() as folder:
            validate_target(self.apk(folder, 'x86_64'), 36, ['x86_64', 'x86'], 36, 'x86_64')

    def test_rejects_an_arm_apk_for_the_x86_ci_device(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaisesRegex(ValueError, 'split APK'):
                validate_target(self.apk(folder, 'arm64-v8a'), 36, ['x86_64'], 36, 'x86_64')

    def test_rejects_a_different_system_version(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaisesRegex(ValueError, 'Expected API 26'):
                validate_target(self.apk(folder, 'x86_64'), 35, ['x86_64'], 26, 'x86_64')

    def test_rejects_a_device_without_the_expected_abi(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaisesRegex(ValueError, 'does not support'):
                validate_target(self.apk(folder, 'x86_64'), 36, ['arm64-v8a'], 36, 'x86_64')


class DeviceCiEvidenceTest(unittest.TestCase):
    identities = {'com.xiaobaitu.reader.debug': 'app-hash', 'com.xiaobaitu.reader.debug.test': 'test-hash'}

    def child(self, folder, **overrides):
        output = Path(folder)
        report = dict(status='passed', tests=3, checks_passed=True, display_settings_restored=True,
                      test_artifacts_removed=True, installed_apks=[{'package': key, 'sha256': value}
                                                                   for key, value in self.identities.items()])
        report.update(overrides)
        (output / 'evidence.json').write_text(json.dumps(report))
        (output / 'instrumentation.log').write_text(''.join('INSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS_CODE: 0\n'
                                                         for _ in range(3)) + 'OK (3 tests)\n')
        return output

    def test_accepts_complete_matching_child_evidence(self):
        with tempfile.TemporaryDirectory() as folder:
            self.assertEqual('passed', verify_child(self.child(folder), self.identities)['status'])

    def test_accepts_single_source_ime_child_only_with_one_real_execution(self):
        with tempfile.TemporaryDirectory() as folder:
            output = self.child(folder, tests=1)
            (output / 'instrumentation.log').write_text(
                'INSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS_CODE: 0\nOK (1 test)\n')
            self.assertEqual(1, verify_child(output, self.identities, expected_tests=1)['tests'])
            with self.assertRaisesRegex(ValueError, 'Incomplete child'):
                verify_child(output, self.identities)
            self.child(folder, tests=True)
            with self.assertRaisesRegex(ValueError, 'Incomplete child'):
                verify_child(output, self.identities, expected_tests=1)

    def test_duplicate_installed_package_cannot_hide_ambiguous_artifact_identity(self):
        with tempfile.TemporaryDirectory() as folder:
            installed = [{'package': key, 'sha256': value} for key, value in self.identities.items()]
            installed.append(installed[0].copy())
            with self.assertRaisesRegex(ValueError, 'different APKs'):
                verify_child(self.child(folder, installed_apks=installed), self.identities)

    def test_status_passed_cannot_hide_incomplete_cleanup(self):
        with tempfile.TemporaryDirectory() as folder:
            with self.assertRaisesRegex(ValueError, 'Incomplete child'):
                verify_child(self.child(folder, test_artifacts_removed=False), self.identities)

    def test_a_different_installed_artifact_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            output = self.child(folder, installed_apks=[{'package': 'com.xiaobaitu.reader.debug', 'sha256': 'old-app'}])
            with self.assertRaisesRegex(ValueError, 'different APKs'):
                verify_child(output, self.identities)

    def test_a_success_message_cannot_hide_a_skipped_test(self):
        with tempfile.TemporaryDirectory() as folder:
            output = self.child(folder)
            (output / 'instrumentation.log').write_text('INSTRUMENTATION_STATUS_CODE: 1\nINSTRUMENTATION_STATUS_CODE: -3\nOK (3 tests)\n')
            with self.assertRaises(RuntimeError):
                verify_child(output, self.identities)


class DisplaySettingsRestoreTest(unittest.TestCase):
    def test_restores_a_key_recreated_by_a_late_configuration_update(self):
        now = [0.0]
        values = {'font_scale': '2.0'}
        late = [False]
        def pause(seconds):
            now[0] += seconds
            if now[0] >= .3 and not late[0]:
                values['font_scale'] = '1.0'
                late[0] = True
        repairs = restore_settings({'font_scale': 'null'}, values.__getitem__, values.__setitem__,
                                   clock=lambda: now[0], pause=pause)
        self.assertEqual([{'key': 'font_scale', 'observed': '1.0', 'restored': 'null'}], repairs)
        self.assertEqual('null', values['font_scale'])
        self.assertGreaterEqual(now[0], 1.3)

    def test_continuously_overwritten_setting_is_a_failure(self):
        now = [0.0]
        def pause(seconds):
            now[0] += seconds
        with self.assertRaisesRegex(RuntimeError, 'did not stabilize'):
            restore_settings({'font_scale': 'null'}, lambda key: '1.0', lambda key, value: None,
                             clock=lambda: now[0], pause=pause)


class MenuAccessibilityEvidenceTest(unittest.TestCase):
    def samples(self):
        return {name: dict(pointer_clicks=1, menu_closed_after_pointer=True, snapshots=[
            dict(checkable=True, checked=value, **{'class': 'android.widget.CheckBox'})
            if value is not None else dict(checkable=False) for value in values])
            for name, values in [('true', [True, False]), ('false', [False, True]), ('ordinary', [None, None])]}

    def test_accepts_both_native_toggle_directions_and_ordinary_action(self):
        verify_menu_samples(self.samples())

    def test_rejects_missing_case_or_reopened_state(self):
        samples = self.samples()
        del samples['ordinary']
        with self.assertRaisesRegex(ValueError, 'Incomplete'):
            verify_menu_samples(samples)
        samples = self.samples()
        samples['true']['snapshots'].pop()
        with self.assertRaisesRegex(ValueError, 'initial/reopened'):
            verify_menu_samples(samples)

    def test_rejects_visual_only_or_stale_native_state(self):
        for field, value in [('checkable', False), ('checked', False), ('class', '')]:
            samples = self.samples()
            samples['true']['snapshots'][0][field] = value
            with self.assertRaisesRegex(ValueError, 'Native menu state'):
                verify_menu_samples(samples)

    def test_rejects_double_invocation_and_unclosed_menu(self):
        for field, value in [('pointer_clicks', 2), ('pointer_clicks', True), ('menu_closed_after_pointer', False)]:
            samples = self.samples()
            samples['true'][field] = value
            with self.assertRaisesRegex(ValueError, 'invoke once'):
                verify_menu_samples(samples)

    def test_rejects_ordinary_action_accidentally_exposed_as_checkable(self):
        samples = self.samples()
        samples['ordinary']['snapshots'][0] = dict(checkable=True, checked=False)
        with self.assertRaisesRegex(ValueError, 'Ordinary'):
            verify_menu_samples(samples)


class ScreenshotBackendTest(unittest.TestCase):
    def test_api26_uses_the_independent_system_pixels(self):
        validate_screenshot_backends([{'screenshot_backend': 'shell-screencap'}], 26)
        with self.assertRaisesRegex(ValueError, 'Wrong or missing'):
            validate_screenshot_backends([{'screenshot_backend': 'uiautomation'}], 26)

    def test_modern_backend_and_missing_metadata(self):
        validate_screenshot_backends([{'screenshot_backend': 'uiautomation'}], 35)
        validate_screenshot_backends([{'screenshot_backend': 'uiautomation'}], '35')
        with self.assertRaises(ValueError):
            validate_screenshot_backends([{}], 35)
        with self.assertRaises(ValueError):
            validate_screenshot_backends([], 26)

    def test_api_query_failure_is_reported_without_accepting_screenshots(self):
        with self.assertRaisesRegex(ValueError, 'Unable to read Android API level:.*exit status 255'):
            validate_screenshot_backends([{'screenshot_backend': 'shell-screencap'}],
                                         {'error': 'getprop returned exit status 255'})
        for api in (None, {}, '', '26x', 0, -1, True):
            with self.subTest(api=api), self.assertRaisesRegex(ValueError, 'Invalid or missing Android API'):
                validate_screenshot_backends([{'screenshot_backend': 'uiautomation'}], api)


class SourceTargetEvidenceTest(unittest.TestCase):
    def samples(self):
        return {
            'large': dict(status='passed', targets=8000, recreations=2, dialog_argument_bytes=316,
                          activity_and_fragment_saved_state_bytes=5080, restored_state_bytes_0=5912,
                          restored_state_bytes_1=5912, exact_target_sets_preserved=True,
                          owners_removed_when_host_finishes=True),
            'missing': dict(status='passed', keyword_unchanged=True, no_check_session_started=True),
            'legacy': dict(status='passed', targets=8000, recreations=1, argument_bytes=316,
                           saved_state_bytes=5080, exact_targets_preserved=True),
            'fault': dict(status='passed', targets=8000, recreations=1, argument_bytes=100,
                          saved_state_bytes=4696, inline_targets_retained=False, page_failure_reported=True,
                          snapshot_directory_restored=True, keyword_unchanged=True, no_check_session_started=True),
            'inactive': dict(status='passed', api=26, selected_task_id=12345, snapshot_preserved=True),
        }

    def test_accepts_complete_small_state_and_failure_recovery_evidence(self):
        verify_source_target_samples(self.samples(), 26)

    def test_requires_every_case_and_its_terminal_result(self):
        for samples in [None, [], {}]:
            with self.assertRaises(ValueError):
                verify_source_target_samples(samples, 26)
        for case in self.samples():
            with self.subTest(case=case):
                samples = self.samples()
                del samples[case]
                with self.assertRaises(ValueError):
                    verify_source_target_samples(samples, 26)
                samples = self.samples()
                samples[case]['status'] = 'running'
                with self.assertRaises(ValueError):
                    verify_source_target_samples(samples, 26)

    def test_rejects_oversized_or_non_numeric_payloads_even_when_status_passes(self):
        for case, key, maximum in [('large', 'dialog_argument_bytes', 1024),
                                   ('large', 'restored_state_bytes_1', 50 * 1024),
                                   ('legacy', 'saved_state_bytes', 50 * 1024),
                                   ('fault', 'saved_state_bytes', 50 * 1024)]:
            for value in [maximum, maximum + 1, 0, -1, True, '100', None]:
                with self.subTest(case=case, key=key, value=value):
                    samples = self.samples()
                    samples[case][key] = value
                    with self.assertRaises(ValueError):
                        verify_source_target_samples(samples, 26)

    def test_rejects_retained_inline_data_or_unrestored_fault_directory(self):
        for key, value in [('inline_targets_retained', True), ('page_failure_reported', False),
                           ('snapshot_directory_restored', False)]:
            samples = self.samples()
            samples['fault'][key] = value
            with self.assertRaises(ValueError):
                verify_source_target_samples(samples, 26)

    def test_rejects_an_accidental_submission_or_changed_keyword(self):
        for case in ['missing', 'fault']:
            for key in ['keyword_unchanged', 'no_check_session_started']:
                samples = self.samples()
                samples[case][key] = False
                with self.assertRaises(ValueError):
                    verify_source_target_samples(samples, 26)

    def test_rejects_lost_targets_and_leaked_owners(self):
        for case, key in [('large', 'exact_target_sets_preserved'), ('large', 'owners_removed_when_host_finishes'),
                          ('legacy', 'exact_targets_preserved')]:
            samples = self.samples()
            samples[case][key] = 1
            with self.assertRaises(ValueError):
                verify_source_target_samples(samples, 26)

    def test_rejects_a_small_fixture_or_incomplete_recreation(self):
        for case, key, value in [('large', 'targets', 2), ('legacy', 'targets', 7999),
                                 ('fault', 'targets', True), ('large', 'recreations', 1)]:
            samples = self.samples()
            samples[case][key] = value
            with self.assertRaises(ValueError):
                verify_source_target_samples(samples, 26)

    def test_rejects_wrong_platform_or_lost_inactive_task_snapshot(self):
        for key, value in [('api', 35), ('api', True), ('selected_task_id', -1), ('snapshot_preserved', False)]:
            samples = self.samples()
            samples['inactive'][key] = value
            with self.assertRaises(ValueError):
                verify_source_target_samples(samples, 26)


class SourceLayoutEvidenceTest(unittest.TestCase):
    def samples(self):
        records = {}
        for case in ['missing', 'legacy', 'valid']:
            valid = case == 'valid'
            stages = ['opened', 'editor-visible' if valid else 'error-visible', 'recreated-0',
                      'recreated-1', 'before-cancel' if valid else 'before-confirm']
            keys = ['actions_after_recreation_0', 'actions_after_recreation_1',
                    'before_cancel_actions' if valid else 'before_confirm_actions']
            if not valid:
                keys.insert(0, 'initial_actions')
            record = dict(case=case, status='passed', requested_short_landscape=False, recreations=2,
                          keyword_unchanged=True, no_check_session_started=True,
                          profiles=[dict(stage=s, width_px=1080, height_px=2424, width_dp=411,
                                         height_dp=923, density_dpi=420, font_scale=1) for s in stages])
            record.update(dict(draft_preserved=True, exact_target_set_preserved=True, pointer_cancel_closed_dialog=True)
                          if valid else dict(pointer_confirm_closed_dialog=True))
            record['action_measurements'] = []
            for key in keys:
                record[key] = [dict(label=label, width=180, height=80, text_not_clipped=True)
                               for label in ['Cancel', 'OK', 'Check setting']]
                record['action_measurements'].extend([
                    dict(label=label, layouts=[dict(width=160, height=70, last_line_bottom=65,
                                                  overflow_height=False, line_count=1, line_rights=[155])])
                    for label in ['Cancel', 'OK', 'Check setting']])
            records[case] = record
        return records

    def test_accepts_complete_layout_evidence(self):
        verify_source_layout_samples(self.samples(), 1)

    def test_requires_every_case_and_terminal_result(self):
        for samples in [None, {}, []]:
            with self.assertRaises(ValueError):
                verify_source_layout_samples(samples, 1)
        for case in self.samples():
            samples = self.samples()
            samples.pop(case)
            with self.assertRaises(ValueError):
                verify_source_layout_samples(samples, 1)
            samples = self.samples()
            samples[case]['status'] = 'failed'
            with self.assertRaises(ValueError):
                verify_source_layout_samples(samples, 1)

    def test_rejects_lost_drafts_wrong_case_and_submissions(self):
        for key, value in [('draft_preserved', False), ('exact_target_set_preserved', 1),
                           ('keyword_unchanged', False), ('no_check_session_started', False),
                           ('pointer_cancel_closed_dialog', False), ('recreations', True), ('case', 'missing')]:
            samples = self.samples()
            samples['valid'][key] = value
            with self.assertRaises(ValueError):
                verify_source_layout_samples(samples, 1)

    def test_rejects_wrong_window_font_and_missing_recreation_sample(self):
        for key, value in [('font_scale', 2), ('font_scale', float('nan')), ('width_px', True),
                           ('height_px', 0), ('density_dpi', 0), ('stage', 'not-recreated')]:
            samples = self.samples()
            samples['missing']['profiles'][2][key] = value
            with self.assertRaises(ValueError):
                verify_source_layout_samples(samples, 1)
        with self.assertRaises(ValueError):
            verify_source_layout_samples(self.samples(), 1, short_landscape=True)

    def test_rejects_incomplete_actions_and_clipped_buttons(self):
        for value in [[], None, [dict(label='OK')] * 3]:
            samples = self.samples()
            samples['legacy']['initial_actions'] = value
            with self.assertRaises(ValueError):
                verify_source_layout_samples(samples, 1)
        for key, value in [('text_not_clipped', False), ('width', 0), ('height', float('inf'))]:
            samples = self.samples()
            samples['missing']['initial_actions'][0][key] = value
            with self.assertRaises(ValueError):
                verify_source_layout_samples(samples, 1)

    def test_rejects_missing_or_clipped_text_measurements(self):
        for key, value in [('line_rights', [162]), ('last_line_bottom', 72), ('overflow_height', True),
                           ('line_count', True), ('line_rights', [float('nan')]), ('line_rights', [])]:
            samples = self.samples()
            samples['valid']['action_measurements'][0]['layouts'][0][key] = value
            with self.assertRaises(ValueError):
                verify_source_layout_samples(samples, 1)
        samples = self.samples()
        samples['missing']['action_measurements'].pop()
        with self.assertRaises(ValueError):
            verify_source_layout_samples(samples, 1)


class CharsetUnicodeEvidenceTest(unittest.TestCase):
    def sample(self):
        return dict(fixture_encoding='GB18030', unicode_expected=CHARSET_UNICODE_PHRASE,
                    unicode_body_excerpt=CHARSET_UNICODE_PHRASE,
                    wrong_encoding_verified=True, unicode_body_confirmed=True)

    def test_accepts_wrong_correct_unicode_body_evidence(self):
        verify_charset_unicode_sample(self.sample())

    def test_rejects_ascii_missing_supplementary_or_garbled_body(self):
        for value in ['ASCII fixture', CHARSET_UNICODE_PHRASE.replace('𠮷', ''),
                      CHARSET_UNICODE_PHRASE.replace('😀', ''), '\ufffd\ufffd\ufffd']:
            sample = self.sample()
            sample['unicode_body_excerpt'] = value
            with self.assertRaises(ValueError):
                verify_charset_unicode_sample(sample)

    def test_rejects_missing_record_or_fake_encoding_and_flags(self):
        for sample in [None, {}, []]:
            with self.assertRaises(ValueError):
                verify_charset_unicode_sample(sample)
        for key, value in [('fixture_encoding', 'UTF-8'), ('unicode_expected', 'ASCII'),
                           ('wrong_encoding_verified', 1), ('unicode_body_confirmed', False)]:
            sample = self.sample()
            sample[key] = value
            with self.assertRaises(ValueError):
                verify_charset_unicode_sample(sample)


class BookmarkDraftEvidenceTest(unittest.TestCase):
    def sample(self):
        return {
            'cancel': {'case': 'cancel', 'samples': [
                {'text': 'original content', 'note': 'original note'},
                {'text': 'edited content', 'note': 'edited note'},
                {'text': 'edited content', 'note': 'edited note'},
                {'text': 'original content', 'note': 'original note'},
                {'text': '', 'note': ''},
            ]},
            'confirm': {'case': 'confirm', 'samples': [
                {'text': 'original content', 'note': 'original note'},
                {'text': 'new content', 'note': 'new note'},
                {'text': 'new content', 'note': 'new note'},
                {'text': 'new content', 'note': 'new note-fresh'},
            ]},
        }

    def test_accepts_both_saved_drafts_empty_values_and_current_native_input(self):
        verify_bookmark_draft_samples(self.sample())

    def test_rejects_missing_recreation_lost_characters_and_wrong_case(self):
        for mutation in ('missing-case', 'missing-stage', 'lost-note', 'empty-fallback', 'wrong-case'):
            with self.subTest(mutation=mutation):
                value = self.sample()
                if mutation == 'missing-case': value.pop('cancel')
                elif mutation == 'missing-stage': value['confirm']['samples'].pop(1)
                elif mutation == 'lost-note': value['confirm']['samples'][2]['note'] = ''
                elif mutation == 'empty-fallback': value['cancel']['samples'][-1]['text'] = 'original content'
                else: value['confirm']['case'] = 'cancel'
                with self.assertRaises(ValueError): verify_bookmark_draft_samples(value)


if __name__ == '__main__':
    unittest.main()
