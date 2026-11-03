import copy
import unittest
from check_android_device_ci import CURRENT_INPUT_MODES, verify_current_input_sample


class CurrentInputEvidenceTest(unittest.TestCase):
    def sample(self, api=37):
        return dict(status='passed', error=None, api=api, samples=[
            dict(mode=mode, before='before-' + mode, expected='after-' + mode + '-fresh',
                 actual='after-' + mode + '-fresh', set_text_accepted=True,
                 confirm_action_accepted=True, same_ui_turn=True, dismiss_count=1)
            for mode in CURRENT_INPUT_MODES])

    def reject(self, mutate):
        data = self.sample()
        mutate(data)
        with self.assertRaises(ValueError):
            verify_current_input_sample(data, 37)

    def test_accepts_each_supported_platform_and_all_field_variants(self):
        for api in [26, 35, 37]:
            verify_current_input_sample(self.sample(api), api)

    def test_requires_current_platform_and_success(self):
        for key, value in [('api', True), ('api', 35), ('status', 'failed'), ('error', 'failure')]:
            self.reject(lambda d: d.update({key: value}))
        with self.assertRaises(ValueError):
            verify_current_input_sample(None, 37)

    def test_requires_every_variant_once_and_in_order(self):
        self.reject(lambda d: d['samples'].pop())
        self.reject(lambda d: d['samples'].append(copy.deepcopy(d['samples'][0])))
        self.reject(lambda d: d['samples'][1].update(mode='below-editor'))

    def test_rejects_previous_frame_input_in_any_mode(self):
        for index in range(len(CURRENT_INPUT_MODES)):
            self.reject(lambda d: d['samples'][index].update(actual=d['samples'][index]['before']))

    def test_rejects_unchanged_or_fabricated_probe_strings(self):
        for key, value in [('before', 'after-plain-fresh'), ('expected', 'before-plain'),
                           ('actual', ''), ('actual', None)]:
            self.reject(lambda d: d['samples'][1].update({key: value}))

    def test_requires_accepted_edit_confirm_and_same_turn(self):
        for key in ['set_text_accepted', 'confirm_action_accepted', 'same_ui_turn']:
            for value in [False, 1, None]:
                self.reject(lambda d: d['samples'][0].update({key: value}))

    def test_requires_one_actual_dismiss_callback(self):
        for value in [0, 2, True, None]:
            self.reject(lambda d: d['samples'][0].update(dismiss_count=value))


if __name__ == '__main__':
    unittest.main()
