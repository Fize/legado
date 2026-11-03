import unittest

from check_android_login_ime_evidence import verify_login_ime_evidence


class LoginImeEvidenceTest(unittest.TestCase):
    def sample(self):
        return dict(width_px=2424, height_px=1080, system_top_px=96, keyboard_top_px=450,
                    ime_visible=True, keyboard_screenshot_verified=True,
                    label_bounds='[150,175][530,275]', unclipped_label_bounds='[150,175][530,275]',
                    editor_bounds='[95,150][2300,430]', confirm_bounds='[2073,100][2154,181]',
                    min_action_px=70, draft='owned123z', reopened_draft='owned123z',
                    confirm_tapped_with_ime=True)

    def test_valid_record(self):
        verify_login_ime_evidence(self.sample())

    def test_rejects_unverified_keyboard_and_unconfirmed_draft(self):
        for key, value in [('ime_visible', False), ('keyboard_screenshot_verified', False),
                           ('confirm_tapped_with_ime', False), ('reopened_draft', 'owned123')]:
            sample = self.sample()
            sample[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                verify_login_ime_evidence(sample)

    def test_rejects_real_failure_geometries(self):
        for key, value in [('confirm_bounds', '[2073,0][2154,68]'),
                           ('confirm_bounds', '[2073,14][2154,95]'),
                           ('label_bounds', '[149,243][529,277]'),
                           ('editor_bounds', '[95,176][2329,473]')]:
            sample = self.sample()
            sample[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                verify_login_ime_evidence(sample)

    def test_rejects_wrong_geometry_and_undersized_controls(self):
        for key, value in [('width_px', True), ('keyboard_top_px', 1080),
                           ('system_top_px', -1), ('min_action_px', 0), ('min_action_px', '70'),
                           ('confirm_bounds', '[100,100][101,101]'),
                           ('label_bounds', 'invalid')]:
            sample = self.sample()
            sample[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                verify_login_ime_evidence(sample)


if __name__ == '__main__':
    unittest.main()
