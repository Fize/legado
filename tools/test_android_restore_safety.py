import unittest
from check_android_restore_safety import validate_instrumentation


def result(codes, summary='OK (1 test)'):
    return '\n'.join('INSTRUMENTATION_STATUS_CODE: ' + str(code) for code in codes) + '\n' + summary


class RestoreResultParsingTest(unittest.TestCase):
    def test_completed_test_without_diagnostics(self):
        self.assertEqual(0, validate_instrumentation(result([1, 0]), 1))

    def test_existing_database_fixture_diagnostic_is_not_a_failure(self):
        log = ('INSTRUMENTATION_STATUS: rollback_fixture_state={"success":false}\n'
               + result([1, 2, 0]))
        self.assertEqual(1, validate_instrumentation(log, 1))

    def test_failure_cannot_be_hidden_by_success_summary(self):
        with self.assertRaises(RuntimeError):
            validate_instrumentation(result([1, -2, 0]), 1)

    def test_skipped_test_cannot_be_hidden_by_success_summary(self):
        with self.assertRaises(RuntimeError):
            validate_instrumentation(result([1, -3, 0]), 1)

    def test_summary_without_all_completions_is_rejected(self):
        with self.assertRaises(RuntimeError):
            validate_instrumentation(result([1]), 1)

    def test_unknown_progress_is_rejected(self):
        with self.assertRaises(RuntimeError):
            validate_instrumentation(result([1, 2, 0]), 1)

    def test_multiple_tests_use_the_plural_summary(self):
        self.assertEqual(0, validate_instrumentation(result([1, 0, 1, 0], 'OK (2 tests)'), 2))

    def test_wrong_grammar_or_count_cannot_match(self):
        for summary in ['OK (1 tests)', 'OK (2 tests)', 'OK (2 test)']:
            with self.subTest(summary=summary), self.assertRaises(RuntimeError):
                validate_instrumentation(result([1, 0], summary), 1)

    def test_embedded_or_duplicate_success_summary_is_rejected(self):
        for summary in ['diagnostic: OK (1 test)', 'OK (1 test) trailing', 'OK (1 test)\nOK (1 test)']:
            with self.subTest(summary=summary), self.assertRaises(RuntimeError):
                validate_instrumentation(result([1, 0], summary), 1)

    def test_no_tests_is_not_a_successful_gate(self):
        with self.assertRaises(RuntimeError):
            validate_instrumentation(result([], 'OK (0 tests)'), 0)


if __name__ == '__main__':
    unittest.main()
