import json
from pathlib import Path
import subprocess
import tempfile
import unittest

from check_android_settings_inputs import complete_report, collect_failure_evidence, validate_editor_touches, validate_editor_profile, validate_caret_probe


class SettingsReportPostconditionsTest(unittest.TestCase):
    def test_caret_probe_requires_the_observed_line_and_complete_glyph_bounds(self):
        row = dict(stage='selected-middle-line', caret_line_index=1, editor_line_top_px=46,
                   editor_line_bottom_px=165, editor_height_px=249, editor_scroll_y_px=119)
        validate_caret_probe([row])
        # Actual failure: the selected second line extended below the viewport.
        for change in [dict(editor_line_top_px=165, editor_line_bottom_px=284, editor_scroll_y_px=0),
                       dict(caret_line_index=0), dict(editor_height_px=0), dict(editor_scroll_y_px='0')]:
            with self.assertRaisesRegex(ValueError, 'clipped or unverified'):
                validate_caret_probe([dict(row, **change)])

    def test_caret_probe_rejects_missing_duplicate_or_untyped_evidence(self):
        for checks in [[], [dict(stage='selected-middle-line')],
                       [dict(stage='selected-middle-line'), dict(stage='selected-middle-line')]]:
            with self.assertRaises(ValueError):
                validate_caret_probe(checks)

    def profile_rows(self):
        return [dict(font_scale=2.0, density_dpi=540, width_dp=539, height_dp=220,
                     task_id=7, in_multi_window=True,
                     geometry=dict(display_width_px=2424, display_height_px=1080)) for _ in range(6)]

    def test_profile_requires_real_display_and_window_orientation(self):
        rows = self.profile_rows()
        validate_editor_profile(rows, 2.0, 540, '1')
        # Observed counterexample: a landscape freeform window on a portrait display.
        rows[0]['geometry'] = dict(display_width_px=1080, display_height_px=2424)
        with self.assertRaisesRegex(ValueError, 'both take effect'):
            validate_editor_profile(rows, 2.0, 540, '1')
        rows = self.profile_rows()
        rows[0].update(width_dp=220, height_dp=539)
        with self.assertRaisesRegex(ValueError, 'both take effect'):
            validate_editor_profile(rows, 2.0, 540, '1')

    def test_profile_rejects_missing_or_invalid_native_identity(self):
        for field, value in [('in_multi_window', None), ('in_multi_window', 'false'), ('task_id', 0)]:
            rows = self.profile_rows()
            rows[0][field] = value
            with self.assertRaisesRegex(ValueError, 'actual display and window'):
                validate_editor_profile(rows, 2.0, 540, '1')
        rows = self.profile_rows()
        rows[0]['geometry'] = {}
        with self.assertRaisesRegex(ValueError, 'actual display and window'):
            validate_editor_profile(rows, 2.0, 540, '1')

    def test_profile_retains_font_density_and_complete_sequence_guards(self):
        for field, value in [('font_scale', 1.0), ('density_dpi', 420)]:
            rows = self.profile_rows()
            rows[0][field] = value
            with self.assertRaises(ValueError):
                validate_editor_profile(rows, 2.0, 540, '1')
        with self.assertRaisesRegex(ValueError, 'incomplete trace'):
            validate_editor_profile(self.profile_rows()[:5], 2.0, 540, '1')

    def test_rejects_actual_observed_ime_coordinate_shift_and_window_transition(self):
        fixture = dict(raw_x=799.5, x=799.5, raw_y=823, y=823,
                       decor_bounds='[0,0][1600,2560]', editor_bounds='[170,569][1430,1078]',
                       editor_window_bounds='[170,569][1430,1078]',
                       ime_visible=True, editor_attached=True)
        rows = [dict(fixture, action=action) for action in [0, 1, 0, 1, 0, 1]]
        validate_editor_touches(rows)
        rows[2]['y'] = 134.625  # Recorded failure: raw tap was inside the visible editor.
        with self.assertRaisesRegex(ValueError, 'coordinates differ'):
            validate_editor_touches(rows)
        rows[2]['y'] = 823
        rows[0]['decor_bounds'] = '[0,731][1600,1829]'
        with self.assertRaisesRegex(ValueError, 'window moved'):
            validate_editor_touches(rows)

    def test_ime_can_move_captured_editor_before_up_but_down_must_hit(self):
        fixture = dict(raw_x=799.5, x=799.5, raw_y=1264, y=1264,
                       decor_bounds='[0,0][1600,2560]', editor_bounds='[105,1010][1495,1519]',
                       editor_window_bounds='[105,1010][1495,1519]',
                       ime_visible=True, editor_attached=True)
        rows = [dict(fixture, action=action) for action in [0, 1, 0, 1, 0, 1]]
        rows[3]['editor_bounds'] = '[105,569][1495,1078]'
        for row in rows[4:]:
            row.update(raw_y=823, y=823, editor_bounds='[105,569][1495,1078]',
                       editor_window_bounds='[105,569][1495,1078]')
        validate_editor_touches(rows)
        rows[2]['editor_bounds'] = '[105,569][1495,1078]'
        with self.assertRaisesRegex(ValueError, 'missed the visible editor'):
            validate_editor_touches(rows)

    def test_requires_confirmed_ime_tap_without_assuming_rapid_tap_ime_timing(self):
        fixture = dict(raw_x=100, x=100, raw_y=200, y=200,
                       decor_bounds='[0,0][1080,1920]', editor_bounds='[0,100][800,500]',
                       editor_window_bounds='[0,100][800,500]',
                       ime_visible=False, editor_attached=True)
        rows = [dict(fixture, action=action) for action in [0, 1, 0, 1, 0, 1]]
        for row in rows[4:]:
            row['ime_visible'] = True
        validate_editor_touches(rows)
        rows[4]['ime_visible'] = False
        with self.assertRaisesRegex(ValueError, 'confirmed IME'):
            validate_editor_touches(rows)
        with self.assertRaisesRegex(ValueError, 'Missing native editor'):
            validate_editor_touches(rows[:4])

    def opening_rows(self):
        fixture = dict(raw_x=539.5, x=539.5, raw_y=1231, y=1231,
                       decor_bounds='[0,0][1080,2424]', editor_bounds='[105,977][975,1486]',
                       editor_window_bounds='[105,977][975,1486]',
                       ime_visible=False, editor_attached=True)
        rows = [dict(fixture, action=action) for action in [0, 1, 0, 1, 0, 1]]
        rows[0]['y'] = 1228.794  # Actual first-down popup entrance translation.
        rows[1]['y'] = 1230.4404
        for row in rows[4:]:
            row['ime_visible'] = True
        return rows

    def test_popup_entrance_accepts_actual_hit_without_weakening_later_2px_guard(self):
        rows = self.opening_rows()
        validate_editor_touches(rows)
        for index in [2, 3, 4, 5]:
            with self.subTest(index=index):
                bad = [dict(row) for row in rows]
                bad[index]['y'] = 1228.794
                with self.assertRaisesRegex(ValueError, 'coordinates differ'):
                    validate_editor_touches(bad)

    def test_first_pointer_still_requires_both_visible_and_native_editor_hits(self):
        for field, value, message in [('y', 134.625, 'in window coordinates'),
                                      ('raw_y', 100, 'visible editor')]:
            with self.subTest(field=field):
                rows = self.opening_rows()
                rows[0][field] = value
                with self.assertRaisesRegex(ValueError, message):
                    validate_editor_touches(rows)

    def test_initial_pair_cannot_bypass_mapping_after_ime_is_already_visible(self):
        rows = self.opening_rows()
        rows[0]['ime_visible'] = True
        with self.assertRaisesRegex(ValueError, 'coordinates differ'):
            validate_editor_touches(rows)

    def test_missing_native_bounds_and_nonfinite_coordinates_are_not_evidence(self):
        rows = self.opening_rows()
        del rows[0]['editor_window_bounds']
        with self.assertRaisesRegex(ValueError, 'Incomplete editor window'):
            validate_editor_touches(rows)
        for value in [float('nan'), float('inf'), True]:
            with self.subTest(value=value):
                rows = self.opening_rows()
                rows[0]['x'] = value
                with self.assertRaisesRegex(ValueError, 'Incomplete editor window'):
                    validate_editor_touches(rows)

    def test_failed_check_exports_only_owned_artifacts_and_keeps_read_errors(self):
        with tempfile.TemporaryDirectory() as folder:
            name = 'settings-input-custom-hosts-failure.json'
            image = 'settings-input-custom-hosts-failure.png'
            def run(*args):
                if args[-2:] == ('ls', 'cache'):
                    return ('\n'.join([name, image, 'unrelated-private.json']) + '\n').encode()
                if args[-1] == 'cache/' + name:
                    return b'{"step":"editing-snapshot"}'
                if args[-1] == 'cache/' + image:
                    raise subprocess.CalledProcessError(1, args, output=b'missing')
                self.fail('Unexpected read: ' + repr(args))
            result = collect_failure_evidence(run, Path(folder))
            self.assertEqual([name], result['copied'])
            self.assertEqual([name], [p.name for p in Path(folder).iterdir()])
            self.assertEqual(image, result['errors'][0]['operation'])
            self.assertEqual('editing-snapshot', json.loads((Path(folder) / name).read_text())['step'])

    def test_unavailable_failure_artifacts_do_not_replace_original_assertion(self):
        with tempfile.TemporaryDirectory() as folder:
            original = AssertionError('device check failed')
            def run(*args):
                raise subprocess.CalledProcessError(1, args, output=b'process ended')
            with self.assertRaises(AssertionError) as caught:
                try:
                    raise original
                except AssertionError:
                    result = collect_failure_evidence(run, Path(folder))
                    raise
            self.assertIs(original, caught.exception)
            self.assertEqual([], result['copied'])
            self.assertEqual('list', result['errors'][0]['operation'])

    def test_cleanup_failure_cannot_publish_success_and_preserves_command_output(self):
        with tempfile.TemporaryDirectory() as folder:
            report = {'status': 'checks_passed', 'checks_passed': True, 'display_settings_restored': True}
            failure = subprocess.CalledProcessError(255, ['adb', 'shell'], output=b'error: closed')
            def cleanup():
                self.assertNotEqual('passed', report['status'])
                raise failure
            with self.assertRaises(subprocess.CalledProcessError) as caught:
                complete_report(report, Path(folder), cleanup)
            self.assertIs(failure, caught.exception)
            saved = json.loads((Path(folder) / 'evidence.json').read_text())
            self.assertEqual('failed', saved['status'])
            self.assertFalse(saved['test_artifacts_removed'])
            self.assertEqual(255, saved['postcondition_exit_code'])
            self.assertEqual('error: closed', saved['postcondition_error_output'])

    def test_success_is_published_only_after_cleanup_returns(self):
        with tempfile.TemporaryDirectory() as folder:
            report = {'status': 'checks_passed', 'checks_passed': True, 'display_settings_restored': True}
            order = []
            def cleanup():
                self.assertNotEqual('passed', report['status'])
                order.append('verified cleanup')
            complete_report(report, Path(folder), cleanup)
            self.assertEqual(['verified cleanup'], order)
            saved = json.loads((Path(folder) / 'evidence.json').read_text())
            self.assertEqual('passed', saved['status'])
            self.assertTrue(saved['test_artifacts_removed'])

    def test_unrestored_settings_prevent_success_without_attempting_cleanup(self):
        self.assert_guarded_failure(checks=True, restored=False)

    def test_failed_device_checks_cannot_be_promoted_by_cleanup(self):
        self.assert_guarded_failure(checks=False, restored=True)

    def assert_guarded_failure(self, checks, restored):
        with tempfile.TemporaryDirectory() as folder:
            report = {'checks_passed': checks, 'display_settings_restored': restored}
            def cleanup():
                self.fail('Failed prerequisites must not reach cleanup')
            with self.assertRaises(RuntimeError):
                complete_report(report, Path(folder), cleanup)
            saved = json.loads((Path(folder) / 'evidence.json').read_text())
            self.assertEqual('failed', saved['status'])
            self.assertFalse(saved['test_artifacts_removed'])


if __name__ == '__main__':
    unittest.main()
