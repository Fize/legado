import unittest
from check_android_restore_recovery_ui import has_active_service


class ActiveRecoveryServiceParsingTest(unittest.TestCase):
    package = 'com.xiaobaitu.reader.debug'
    component = 'io.legado.app.service.AudioPlayService'

    def test_target_active_record_is_recognized(self):
        text = '  * ServiceRecord{abc u0 ' + self.package + '/' + self.component + '}\n'
        self.assertTrue(has_active_service(text, self.package, self.component))

    def test_historical_last_anr_is_not_an_active_service(self):
        text = ('  Last ANR service:\nServiceRecord{abc u0 ' + self.package + '/' + self.component
                + '}\n    destroying=true\n\n  (nothing)\n')
        self.assertFalse(has_active_service(text, self.package, self.component))

    def test_sibling_service_and_other_package_are_not_the_requested_service(self):
        for package, component in [(self.package, 'androidx.work.impl.background.systemjob.SystemJobService'),
                                   (self.package + '.other', self.component)]:
            text = '  * ServiceRecord{abc u0 ' + package + '/' + component + '}\n'
            self.assertFalse(has_active_service(text, self.package, self.component))


if __name__ == '__main__':
    unittest.main()
