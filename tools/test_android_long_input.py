import copy
import re
import unittest

from android_device_checks import ROOT
from check_android_device_ci import verify_long_input_sample


class LongNativeInputEvidenceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        source = (ROOT / 'app/src/androidTest/java/io/legado/app/ui/config/LongNativeInputUiTest.kt').read_text()
        match = re.search(r'private val payload = """(.*?)"""', source, re.S)
        if not match:
            raise AssertionError('Missing Android test fixture')
        cls.payload = match.group(1)

    def sample(self, api=35):
        payload = self.payload
        return dict(status='passed', error=None, api=api, payload=payload,
                    keyboard_visible_before_input=True,
                    native_input_method='instrumentation_send_string_sync',
                    confirmed=payload, dismissed=True,
                    samples=[dict(offset=i, expected=payload[:i + 96], observed=payload[:i + 96])
                             for i in range(0, len(payload), 96)])

    def reject(self, mutate):
        record = self.sample()
        mutate(record)
        with self.assertRaises(ValueError):
            verify_long_input_sample(record, 35)

    def test_accepts_each_supported_api(self):
        for api in (26, 35, 37):
            verify_long_input_sample(self.sample(api), api)

    def test_requires_platform_success_and_real_keyboard(self):
        for key, value in [('api', True), ('api', 26), ('status', 'failed'), ('error', 'failure'),
                           ('keyboard_visible_before_input', False), ('keyboard_visible_before_input', 1),
                           ('native_input_method', 'shell_batch'), ('dismissed', False), ('dismissed', 1)]:
            self.reject(lambda data: data.update({key: value}))

    def test_requires_original_fixture_and_exact_confirmation(self):
        self.reject(lambda data: data.update(payload=data['payload'][:-1] + 'x'))
        self.reject(lambda data: data.update(confirmed=data['payload'][:-1]))
        self.reject(lambda data: data.update(confirmed=None))

    def test_requires_all_ordered_exact_segments(self):
        self.reject(lambda data: data['samples'].pop())
        self.reject(lambda data: data['samples'].append(copy.deepcopy(data['samples'][0])))
        for index in range(6):
            self.reject(lambda data: data['samples'][index].update(observed=data['samples'][index]['observed'][:-1]))
            self.reject(lambda data: data['samples'][index].update(expected=''))
            self.reject(lambda data: data['samples'][index].update(offset=True))


if __name__ == '__main__':
    unittest.main()
