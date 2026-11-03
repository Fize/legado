import unittest
from check_android_video_handoff import overlay_mode


class OverlayAppOpParsingTest(unittest.TestCase):
    def test_all_known_modes(self):
        for mode in ['allow', 'deny', 'ignore', 'default', 'foreground']:
            with self.subTest(mode=mode):
                self.assertEqual(mode, overlay_mode('SYSTEM_ALERT_WINDOW: ' + mode))

    def test_runtime_timestamps_do_not_change_the_mode(self):
        self.assertEqual('default', overlay_mode('SYSTEM_ALERT_WINDOW: default; rejectTime=+1m0s ago\n'))

    def test_no_record_means_default(self):
        self.assertEqual('default', overlay_mode('No operations.'))

    def test_missing_response_is_not_a_default(self):
        with self.assertRaises(ValueError):
            overlay_mode('')

    def test_unknown_mode_prefix_cannot_be_mistaken_for_allow(self):
        with self.assertRaises(ValueError):
            overlay_mode('SYSTEM_ALERT_WINDOW: allowed_but_unknown')


if __name__ == '__main__':
    unittest.main()
