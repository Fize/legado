import copy
import unittest

from check_android_editor_recovery import validate_case, ORDER_SUFFIX


def fixture(case='code'):
    suffix = '   ' if case == 'code' else ORDER_SUFFIX if case in ('code-order', 'code-burst') else 'queued'
    text = ('{}' if case == 'code' else 'DRAFT-') + suffix
    row = dict(case=case, status='passed', font_scale=2, density=3.375, display_width_px=2424,
               display_height_px=1080, original_activity_identity=10, recreated_activity_identity=20,
               task_id=3, completion='confirmed' if case == 'code' else 'cancelled',
               recovery_action_heights_px=[189, 189], key_injection_started_ms=100,
               key_injection_completed_ms=300)
    row['queued_suffix'] = suffix
    row['injection_method'] = 'shell-input-text' if case == 'code-burst' else 'uiautomation-synchronous-key-events'
    for key in ['recovery_message_visible', 'recovery_live_region_polite', 'draft_not_committed_during_recovery',
                'same_editor_during_recovery', 'retry_ime_visible', 'preferences_restored',
                'recovery_hint_not_clipped', 'recovery_ime_animation_finished']:
        row[key] = True
    for key in ['recovered_exact_text', 'recreated_exact_text', 'retry_exact_text']:
        row[key] = text
    base = dict(phase='await-recovery', root_bounds='[303,196][2121,938]', root_identity=1, editor_height_px=189)
    row['frames'] = [dict(base, ime_visible=True, ime_bottom_px=580, usable_height_px=27),
                     dict(base, ime_visible=False, ime_bottom_px=0, usable_height_px=607),
                     dict(base, phase='fullscreen-retry', root_bounds='[0,0][2424,1080]', ime_visible=True,
                          ime_bottom_px=722, usable_height_px=236)]
    row['key_events'] = [dict(key_code=code, action=action, root_identity=1, is_text_editor=True,
                              handled=action == 0) for code in ([62, 62, 62] if case == 'code' else
                                  [29 + ord(c) - ord('a') if c.isalpha() else 7 + int(c) for c in suffix])
                         for action in (0, 1)]
    return row


class RecoveryEvidenceTest(unittest.TestCase):
    def test_accepts_complete_native_and_compose_evidence(self):
        for case in ['code', 'compose', 'code-order', 'code-burst']:
            validate_case(fixture(case), case)

    def test_accepts_unhandled_ups_returned_before_relayed_downs(self):
        row = fixture('code-order')
        keys = row['key_events']
        row['key_events'] = [event for index in range(0, len(keys), 2) for event in [keys[index + 1], keys[index]]]
        validate_case(row, 'code-order')

    def test_rejects_adjacent_distinct_key_downs_swapped_by_ime(self):
        row = fixture('code-burst')
        row['key_events'][0], row['key_events'][2] = row['key_events'][2], row['key_events'][0]
        with self.assertRaises(ValueError): validate_case(row, 'code-burst')

    def test_requires_actual_burst_input_evidence(self):
        row = fixture('code-burst'); row['injection_method'] = 'uiautomation-synchronous-key-events'
        with self.assertRaises(ValueError): validate_case(row, 'code-burst')

    def test_rejects_changed_or_unverified_draft(self):
        for case in ['code', 'compose']:
            for key in ['recovered_exact_text', 'recreated_exact_text', 'retry_exact_text']:
                row = fixture(case); row[key] = 'truncated'
                with self.subTest(case=case, key=key), self.assertRaises(ValueError):
                    validate_case(row, case)

    def test_rejects_missing_boolean_and_skips(self):
        base = fixture()
        for key, value in base.items():
            if value is True:
                row = copy.deepcopy(base); row.pop(key)
                with self.subTest(key=key), self.assertRaises(ValueError):
                    validate_case(row, 'code')
        row = fixture(); row['status'] = 'skipped'
        with self.assertRaises(ValueError): validate_case(row, 'code')

    def test_rejects_zero_native_editor_even_for_one_frame(self):
        row = fixture(); row['frames'][0]['editor_height_px'] = 0
        with self.assertRaises(ValueError): validate_case(row, 'code')

    def test_rejects_root_movement_or_replacement(self):
        for key, value in [('root_identity', 2), ('root_bounds', '[303,197][2121,939]')]:
            row = fixture(); row['frames'][1][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError): validate_case(row, 'code')

    def test_rejects_underflow_without_keyboard_recovery(self):
        for change in ['no-underflow', 'no-hidden']:
            row = fixture()
            if change == 'no-underflow': row['frames'][0]['usable_height_px'] = 500
            else: row['frames'].pop(1)
            with self.subTest(change=change), self.assertRaises(ValueError): validate_case(row, 'code')

    def test_rejects_nominal_fullscreen_without_actual_full_bounds(self):
        row = fixture(); row['frames'][-1]['root_bounds'] = '[303,196][2121,938]'
        with self.assertRaises(ValueError): validate_case(row, 'code')

    def test_rejects_missing_or_unhandled_actual_keys(self):
        for change in ['missing', 'unhandled', 'wrong-editor']:
            row = fixture()
            if change == 'missing': row['key_events'].pop()
            elif change == 'unhandled': row['key_events'][0]['handled'] = False
            else: row['key_events'][0]['root_identity'] = 2
            with self.subTest(change=change), self.assertRaises(ValueError): validate_case(row, 'code')

    def test_rejects_fake_recreation_and_small_actions(self):
        for key, value in [('recreated_activity_identity', 10), ('recovery_action_heights_px', [100, 189])]:
            row = fixture(); row[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError): validate_case(row, 'code')

    def test_rejects_wrong_actual_profile(self):
        row = fixture(); row['display_width_px'] = 1080
        with self.assertRaises(ValueError): validate_case(row, 'code')


if __name__ == '__main__':
    unittest.main()
