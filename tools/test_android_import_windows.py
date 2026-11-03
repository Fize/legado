import unittest

from check_android_import_windows import MATRIX, validate_windows


class ImportWindowEvidenceTest(unittest.TestCase):
    def samples(self):
        values = [dict(fixture='11111111-1111-4111-8111-111111111111', task_id=123, activity_identity=456,
                       width_dp=w, height_dp=h, actual_width_px=w * 2, actual_height_px=h * 2, density=2,
                       font_scale=2, list_height_px=150, selection_kept=True, last_and_first_reachable=True,
                       reverse_round_trip=True, main_prompt_cancelled=True, delete_menu_reachable=True,
                       directory_entries=120, quick_actions_available=True, fixed_actions_visible=False,
                       row_shortcut_used=True, shortcut_main_without_list_scroll=True, shortcut_select_all_round_trip=True, shortcut_reverse_round_trip=True,
                       platform_api=35, row_menu_native_samples=[dict(checkable=True, checked=checked,
                           caption_inset_px=80, safe_top_px=80, window_bottom_px=h * 2,
                           bounds='[0,80][560,176]') for checked in [False, True]]) for w, h in MATRIX]
        values[-1].update(search_draft_kept=True, actual_import_completed=True, imported_body_kept=True,
                          completed_import_window_width_dp=320, completed_import_window_height_dp=240,
                          row_shortcut_delete_completed=True, unselected_files_preserved=True)
        return values

    def test_complete_actual_window_and_import_sequence_passes(self):
        validate_windows(self.samples(), 2)

    def test_requested_size_without_actual_window_change_is_rejected(self):
        values = self.samples(); values[2]['actual_height_px'] = 960
        with self.assertRaises(ValueError): validate_windows(values, 2)

    def test_recreated_activity_or_mixed_task_is_rejected(self):
        for key in ['activity_identity', 'task_id', 'fixture']:
            values = self.samples(); values[2][key] = 789 if key != 'fixture' else '22222222-2222-4222-8222-222222222222'
            with self.assertRaises(ValueError): validate_windows(values, 2)

    def test_selection_loss_starved_list_or_wrong_font_is_rejected(self):
        for key, value in [('selection_kept', False), ('list_height_px', 0), ('font_scale', 1)]:
            values = self.samples(); values[2][key] = value
            with self.assertRaises(ValueError): validate_windows(values, 2)

    def test_visible_layout_without_actual_actions_does_not_pass(self):
        for key in ['last_and_first_reachable', 'reverse_round_trip', 'main_prompt_cancelled', 'delete_menu_reachable']:
            values = self.samples(); values[2][key] = False
            with self.assertRaises(ValueError): validate_windows(values, 2)

    def test_draft_loss_or_queue_without_imported_content_is_rejected(self):
        for key in ['search_draft_kept', 'actual_import_completed', 'imported_body_kept']:
            values = self.samples(); values[-1][key] = False
            with self.assertRaises(ValueError): validate_windows(values, 2)
        with self.assertRaises(ValueError): validate_windows(self.samples()[:-1], 2)
        values = self.samples(); values[-1]['completed_import_window_height_dp'] = 600
        with self.assertRaises(ValueError): validate_windows(values, 2)

    def test_footer_only_access_or_small_fixture_cannot_prove_long_folder_usability(self):
        for key, value in [('directory_entries', 12), ('quick_actions_available', False), ('row_shortcut_used', False), ('shortcut_main_without_list_scroll', False), ('shortcut_select_all_round_trip', False), ('shortcut_reverse_round_trip', False)]:
            values = self.samples(); values[2][key] = value
            with self.assertRaises(ValueError): validate_windows(values, 2)
        values = self.samples(); values[0]['fixed_actions_visible'] = True; values[0]['row_shortcut_used'] = False
        validate_windows(values, 2)

    def test_delete_callback_without_preserving_other_files_is_rejected(self):
        for key in ['row_shortcut_delete_completed', 'unselected_files_preserved']:
            values = self.samples(); values[-1][key] = False
            with self.assertRaises(ValueError): validate_windows(values, 2)

    def test_accessibility_label_without_toggle_state_is_rejected(self):
        for key, value in [('checkable', False), ('checked', True)]:
            values = self.samples(); values[2]['row_menu_native_samples'][0][key] = value
            with self.assertRaises(ValueError): validate_windows(values, 2)
        values = self.samples(); del values[2]['row_menu_native_samples']
        with self.assertRaises(ValueError): validate_windows(values, 2)

    def test_label_found_under_caption_does_not_prove_visible_menu(self):
        values = self.samples(); values[2]['row_menu_native_samples'][0]['bounds'] = '[291,87][570,141]'
        values[2]['row_menu_native_samples'][0]['safe_top_px'] = 130
        with self.assertRaises(ValueError): validate_windows(values, 2)
        values = self.samples(); values[2]['row_menu_native_samples'][0]['bounds'] = '[0,80][560,999]'
        with self.assertRaises(ValueError): validate_windows(values, 2)

    def test_modern_freeform_without_observed_caption_cannot_pass(self):
        values = self.samples(); values[2]['row_menu_native_samples'][0]['caption_inset_px'] = 0
        with self.assertRaises(ValueError): validate_windows(values, 2)


if __name__ == '__main__':
    unittest.main()
