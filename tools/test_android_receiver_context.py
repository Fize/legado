import unittest
from check_android_receiver_context import context_cast_failure, COMPONENT, optional_file_bytes


def logs(pid, component=COMPONENT):
    prefix = f'10-21 09:25:52.735 {pid} {pid} E AndroidRuntime: '
    return (prefix + 'Unable to instantiate receiver ' + component + '\n'
            + prefix + 'Caused by: java.lang.ClassCastException: android.content.MutableContextWrapper cannot be cast to android.app.ContextImpl\n')


class ReceiverContextFailureParsingTest(unittest.TestCase):
    def test_matching_pid_and_component_are_required(self):
        self.assertTrue(context_cast_failure(logs(123), 123))
        self.assertFalse(context_cast_failure(logs(123), 456))

    def test_unrelated_receiver_does_not_authorize_diagnostic_reset(self):
        self.assertFalse(context_cast_failure(logs(123, 'other.Receiver'), 123))

    def test_incomplete_or_unrelated_exception_is_not_the_failure(self):
        self.assertFalse(context_cast_failure(logs(123).splitlines()[0], 123))
        self.assertFalse(context_cast_failure('10-21 09:25:52.735 123 123 E AndroidRuntime: FATAL EXCEPTION: main', 123))


class OptionalReceiverFileParsingTest(unittest.TestCase):
    def test_missing_file_is_empty_even_when_exec_out_returns_zero(self):
        path = 'files/restore-settings-v1.bin'
        error = ('cat: ' + path + ': No such file or directory\n').encode()
        self.assertEqual(b'', optional_file_bytes(error, 0, path))
        self.assertEqual(b'', optional_file_bytes(error, 1, path))

    def test_other_errors_and_actual_data_are_not_confused(self):
        with self.assertRaises(RuntimeError):
            optional_file_bytes(b'cat: permission denied', 1, 'owned')
        self.assertEqual(b'owned bytes', optional_file_bytes(b'owned bytes', 0, 'owned'))


if __name__ == '__main__':
    unittest.main()
