import copy
import unittest

from check_android_export_timeout import validate_detached, validate_timeout


class TimeoutProofTest(unittest.TestCase):
    def setUp(self):
        self.result = dict(id='owned', mode='hold', pid=100, provider_pid=200, service_token='abc',
            service_foreground_before=True, service_gone_while_io_blocked=True, old_task_interrupted=True,
            old_owner_cleared=True, original_preserved_before_retry=True, foreground_return_before_retry=True,
            retry_completed=True, service_stopped_after_retry=True, unrelated_preserved=True,
            fragments_after_retry=0, background_elapsed_ms=1000, timeout_observed_elapsed_ms=6000,
            old_batch='old', new_batch='new', old_dispatch='old-dispatch', new_dispatch='new-dispatch')
        self.log = 'BEGIN:owned:100\nFGS (dataSync) timed out: ServiceRecord{abc u0 pkg/BookExportForegroundService}\nEND:owned:100'

    def test_plain_stop_wrong_instance_or_missing_log_cannot_prove_timeout(self):
        validate_timeout(self.result, self.log, 'owned', 'hold')
        for value in [self.log.replace('timed out', 'stopped'), self.log.replace('{abc ', '{def '), self.log.replace('BEGIN:owned:100', 'BEGIN:other:100')]:
            with self.assertRaises(ValueError): validate_timeout(self.result, value, 'owned', 'hold')

    def test_timeout_requires_safe_interruption_live_io_and_fresh_retry(self):
        for field, value in [('service_gone_while_io_blocked', False), ('retry_completed', 1), ('fragments_after_retry', 1), ('new_batch', 'old'), ('timeout_observed_elapsed_ms', 25000)]:
            changed = copy.deepcopy(self.result); changed[field] = value
            with self.assertRaises(ValueError): validate_timeout(changed, self.log, 'owned', 'hold')

    def test_an_unsupported_or_still_instrumented_dump_is_not_detachment(self):
        before = 'ACTIVITY MANAGER\nActive instrumentation:\n100:pkg'
        after = 'ACTIVITY MANAGER\nProcessRecord{100:pkg}'
        validate_detached(before, after, 100)
        for value in ['Unknown command: instrumentation', before, 'ACTIVITY MANAGER\n200:pkg']:
            with self.assertRaises(ValueError): validate_detached(before, value, 100)


if __name__ == '__main__': unittest.main()
