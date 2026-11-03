from pathlib import Path
import subprocess
import tempfile
import unittest

from check_android_source_ime import (PROFILES, collect_source_ime_diagnostics,
                                      source_ime_profile, verify_source_ime_sample)


class SourceImeEvidenceTest(unittest.TestCase):
    def sample(self, profile='short-200', api=35):
        width, height, density, scale = source_ime_profile(api, profile)
        compact = profile == 'short-200'
        rows = []
        for index in range(3):
            row = dict(stage='opened' if index == 0 else f'recreated-{index}', api=api,
                       screenshot_backend='shell-screencap' if api == 26 else 'uiautomation',
                       screenshot_width_px=1080 if api == 26 else width,
                       screenshot_height_px=1920 if api == 26 else height,
                       window=f'[0,0][{width},{height}]', ime_visible=True, ime_bottom_px=300,
                       keyboard_top_px=height - 300, density_dpi=density, font_scale=scale,
                       query='ime0123abcd', editor_bounds=f'[0,0][{width - 200},100]',
                       compact_actions=compact, controls={'menu': f'[{width - 150},0][{width - 50},100]'},
                       native_menu_and_group_pointer_used=True, untouched_source_unchanged=True,
                       selected_group='keep' + ''.join(f',added-0123abcd-{n}' for n in range(index + 1)),
                       group_menu_bounds='[0,200][300,250]', group_menu_ime_visible=False,
                       group_menu_keyboard_top_px=height)
            if compact:
                row.update(compact_inverse_round_trip_verified=True, compact_delete_cancel_verified=True)
            else:
                row['controls'].update(delete='[0,110][100,160]', inverse='[110,110][210,160]')
            rows.append(row)
        return dict(status='passed', profile=profile, samples=rows,
                    fixtures_removed=True, hardware_ime_setting_restored=True)

    def test_accepts_each_requested_profile_and_three_recreated_stages(self):
        for api in [26, 35, 37]:
            for profile in PROFILES:
                verify_source_ime_sample(self.sample(profile, api), api, profile, 'Physical size: 1080x1920')

    def test_android8_uses_explicit_supported_width_and_real_screenshot_backend(self):
        sample = self.sample('wide', 26)
        self.assertEqual('[0,0][2160,2400]', sample['samples'][0]['window'])
        for key, value in [('window', '[0,0][3000,2400]'), ('screenshot_backend', 'uiautomation'),
                           ('screenshot_backend', None)]:
            record = self.sample('wide', 26)
            record['samples'][0][key] = value
            with self.assertRaises(ValueError):
                verify_source_ime_sample(record, 26, 'wide', 'Physical size: 1080x1920')

    def test_native_frame_and_logical_window_cannot_be_confused_on_android8(self):
        for key, value in [('screenshot_width_px', 2160), ('screenshot_height_px', True),
                           ('screenshot_height_px', None)]:
            sample = self.sample('wide', 26)
            sample['samples'][0][key] = value
            with self.assertRaises(ValueError):
                verify_source_ime_sample(sample, 26, 'wide', 'Physical size: 1080x1920')
        with self.assertRaises(ValueError):
            verify_source_ime_sample(self.sample('wide', 26), 26, 'wide')

    def test_passed_cannot_hide_missing_fixture_or_setting_cleanup(self):
        for key in ['fixtures_removed', 'hardware_ime_setting_restored']:
            for value in [None, False, 1]:
                sample = self.sample()
                sample[key] = value
                with self.assertRaises(ValueError):
                    verify_source_ime_sample(sample, 35, 'short-200')

    def test_rejects_missing_reordered_or_wrong_recreation_stages(self):
        for rows in [[], self.sample()['samples'][:2], list(reversed(self.sample()['samples']))]:
            sample = self.sample()
            sample['samples'] = rows
            with self.assertRaises(ValueError):
                verify_source_ime_sample(sample, 35, 'short-200')

    def test_rejects_fake_platform_density_or_nonfinite_font(self):
        for key, value in [('api', True), ('api', 26), ('density_dpi', True),
                           ('density_dpi', 420), ('font_scale', True),
                           ('font_scale', float('nan')), ('font_scale', float('inf')),
                           ('font_scale', 1.0), ('window', '[0,0][1080,2424]')]:
            sample = self.sample()
            sample['samples'][1][key] = value
            with self.assertRaises(ValueError):
                verify_source_ime_sample(sample, 35, 'short-200')

    def test_rejects_hidden_fake_or_inconsistent_ime(self):
        for key, value in [('ime_visible', False), ('ime_visible', 1), ('ime_bottom_px', True),
                           ('ime_bottom_px', 0), ('ime_bottom_px', 1080),
                           ('keyboard_top_px', True), ('keyboard_top_px', 1000)]:
            sample = self.sample()
            sample['samples'][0][key] = value
            with self.assertRaises(ValueError):
                verify_source_ime_sample(sample, 35, 'short-200')

    def test_rejects_covered_clipped_empty_or_overlapping_geometry(self):
        for bounds in ['[0,0][0,100]', '[-1,0][100,100]', '[0,700][100,900]',
                       '[0,0][9999,100]', 'Rect(0,0,100,100)', None]:
            for key in ['editor_bounds', 'menu']:
                sample = self.sample()
                if key == 'menu':
                    sample['samples'][0]['controls'][key] = bounds
                else:
                    sample['samples'][0][key] = bounds
                with self.assertRaises(ValueError):
                    verify_source_ime_sample(sample, 35, 'short-200')
        sample = self.sample()
        sample['samples'][0]['controls']['menu'] = '[10,10][100,50]'
        with self.assertRaisesRegex(ValueError, 'overlaps'):
            verify_source_ime_sample(sample, 35, 'short-200')

    def test_rejects_missing_normal_or_compact_actions(self):
        for profile in PROFILES:
            sample = self.sample(profile)
            sample['samples'][0]['controls'].pop('menu')
            with self.assertRaises(ValueError):
                verify_source_ime_sample(sample, 35, profile)
        for key in ['compact_actions', 'compact_inverse_round_trip_verified',
                    'compact_delete_cancel_verified', 'native_menu_and_group_pointer_used',
                    'untouched_source_unchanged']:
            sample = self.sample()
            sample['samples'][2][key] = False
            with self.assertRaises(ValueError):
                verify_source_ime_sample(sample, 35, 'short-200')

    def test_rejects_query_or_selected_scope_changes(self):
        for key, value in [('query', 'ime89abcdef'), ('query', 'other-query'),
                           ('selected_group', 'keep'), ('selected_group', 'keep,added-other-1')]:
            sample = self.sample()
            sample['samples'][1][key] = value
            with self.assertRaises(ValueError):
                verify_source_ime_sample(sample, 35, 'short-200')

    def test_popup_cannot_claim_hidden_keyboard_then_use_unsafe_bounds(self):
        for key, value in [('group_menu_ime_visible', 0), ('group_menu_keyboard_top_px', True),
                           ('group_menu_keyboard_top_px', 780), ('group_menu_bounds', '[0,1000][300,1200]')]:
            sample = self.sample()
            sample['samples'][1][key] = value
            with self.assertRaises(ValueError):
                verify_source_ime_sample(sample, 35, 'short-200')
        sample = self.sample()
        sample['samples'][1].update(group_menu_ime_visible=True, group_menu_keyboard_top_px=780,
                                    group_menu_bounds='[0,700][300,800]')
        with self.assertRaises(ValueError):
            verify_source_ime_sample(sample, 35, 'short-200')

    def test_rejects_wrong_root_profile_status_or_contradictory_error(self):
        for key, value in [('profile', 'wide'), ('status', 'failed'), ('error', 'native click failed')]:
            sample = self.sample()
            sample[key] = value
            with self.assertRaises(ValueError):
                verify_source_ime_sample(sample, 35, 'short-200')


class SourceImeDiagnosticsTest(unittest.TestCase):
    def test_failed_listing_does_not_replace_instrumentation_failure(self):
        def run(*args):
            raise subprocess.CalledProcessError(1, list(args))
        with tempfile.TemporaryDirectory() as folder:
            result = collect_source_ime_diagnostics(run, Path(folder), ['owned.json'])
        self.assertEqual([], result['copied'])
        self.assertEqual('list', result['errors'][0]['operation'])

    def test_collects_only_owned_files_and_preserves_partial_evidence(self):
        def run(*args):
            if 'ls' in args:
                return b'owned.json\nother.json\nowned.png\n'
            if args[-1] == 'cache/owned.png':
                raise subprocess.CalledProcessError(1, list(args))
            return b'{"status":"failed"}'
        with tempfile.TemporaryDirectory() as folder:
            result = collect_source_ime_diagnostics(run, Path(folder), ['owned.json', 'owned.png'])
            self.assertEqual(['owned.json'], result['copied'])
            self.assertEqual(['owned.json'], [p.name for p in Path(folder).iterdir()])
            self.assertEqual('owned.png', result['errors'][0]['operation'])


if __name__ == '__main__':
    unittest.main()
