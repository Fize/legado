import copy
import unittest

from check_android_source_ime import (IMPORT_STAGES, PROFILES, source_ime_profile,
                                      verify_source_import_ime_sample)


class SourceImportImeEvidenceTest(unittest.TestCase):
    def record(self, api=37, profile='short-200'):
        width, height, density, scale = source_ime_profile(api, profile)
        controls = {'selectAll': '[400,10][500,80]', 'cancel': '[510,10][610,80]',
                    'ok': '[620,10][720,80]'}
        return dict(status='passed', error=None, profile=profile, fixtures_removed=True,
                    preferences_restored=True, hardware_ime_setting_restored=True,
                    cancel_did_not_import=True, confirm_imported_selected_only=True,
                    native_query_entries=[dict(attempt=i, query='needle0123abcd', current_window_input_connection_ready=True) for i in range(2)],
                    samples=[dict(stage=stage, api=api, font_scale=scale, density_dpi=density,
                                  window=f'[0,0][{width},{height}]', query='needle0123abcd',
                                  screenshot_backend='shell-screencap' if api == 26 else 'uiautomation',
                                  screenshot_width_px=1080 if api == 26 else width,
                                  screenshot_height_px=1920 if api == 26 else height,
                                  ime_visible=True, ime_bottom_px=300, keyboard_top_px=height - 300,
                                  controls=copy.deepcopy(controls), native_controls=copy.deepcopy(controls),
                                  editor_bounds='[10,10][390,80]', selection=[True, False, False],
                                  native_edit_after_recreate=stage.startswith('confirm-recreated'))
                             for stage in IMPORT_STAGES])

    def test_accepts_all_systems_profiles_and_eight_native_samples(self):
        for api in [26, 35, 37]:
            for profile in PROFILES:
                verify_source_import_ime_sample(self.record(api, profile), api, profile, 'Physical size: 1080x1920')

    def reject(self, change):
        data = self.record()
        change(data)
        with self.assertRaises(ValueError):
            verify_source_import_ime_sample(data, 37, 'short-200')

    def test_requires_actual_cancel_confirm_and_cleanup(self):
        for key in ['cancel_did_not_import', 'confirm_imported_selected_only', 'fixtures_removed',
                    'preferences_restored', 'hardware_ime_setting_restored']:
            for value in [False, 1, None]:
                self.reject(lambda d: d.update({key: value}))

    def test_rejects_hidden_keyboard_or_wrong_platform_and_dimensions(self):
        for key, value in [('ime_visible', False), ('ime_bottom_px', True), ('keyboard_top_px', 1080),
                           ('api', True), ('font_scale', float('nan')), ('font_scale', 1.0),
                           ('density_dpi', 320), ('screenshot_backend', 'shell-screencap'),
                           ('screenshot_width_px', 1080), ('window', '[0,0][1080,2400]')]:
            self.reject(lambda d: d['samples'][0].update({key: value}))

    def test_requires_every_stage_and_persistent_query_and_selection(self):
        self.reject(lambda d: d['samples'].pop())
        self.reject(lambda d: d['samples'][2].update(stage='cancel'))
        self.reject(lambda d: d['samples'][2].update(query='needleffffffff'))
        self.reject(lambda d: d['samples'][7].update(selection=[True, True, False]))
        self.reject(lambda d: d['samples'][7].update(selection=[1, 0, 0]))

    def test_rejects_occluded_empty_and_overlapping_action_bounds(self):
        for key in ['controls', 'native_controls']:
            for rect in ['[0,0][0,0]', '[620,800][720,900]', '[510,10][610,80]', 'garbage']:
                self.reject(lambda d: d['samples'][0][key].update(ok=rect))

    def test_rejects_editor_action_overlap_and_wrong_native_target(self):
        self.reject(lambda d: d['samples'][0].update(editor_bounds='[10,10][450,80]'))
        self.reject(lambda d: d['samples'][0]['native_controls'].update(ok='[730,10][830,80]'))
        self.reject(lambda d: d['samples'][0]['native_controls'].pop('cancel'))

    def test_requires_current_input_connection_before_both_native_entries(self):
        self.reject(lambda d: d.pop('native_query_entries'))
        self.reject(lambda d: d['native_query_entries'].pop())
        for key, value in [('attempt', True), ('attempt', 1), ('query', 'needleffffffff'),
                           ('current_window_input_connection_ready', False),
                           ('current_window_input_connection_ready', 1)]:
            self.reject(lambda d: d['native_query_entries'][0].update({key: value}))

    def test_requires_actual_native_edit_after_each_recreation(self):
        for index in (6, 7):
            for value in (None, False, 1):
                self.reject(lambda d: d['samples'][index].update(native_edit_after_recreate=value))

    def test_android8_requires_physical_image_identity(self):
        with self.assertRaises(ValueError):
            verify_source_import_ime_sample(self.record(26), 26, 'short-200')


if __name__ == '__main__':
    unittest.main()
