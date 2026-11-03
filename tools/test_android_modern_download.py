import copy
import unittest

from check_android_modern_download import validate_trace


class ModernDownloadProofTest(unittest.TestCase):
    def rows(self):
        first = dict(event='running', permission='granted', notifications_enabled=True,
            task_id='task', batch_id='batch', process='process', task_phase='running', batch_phase='running',
            task_owner='first', user_initiated=True, validated_network=True, request_count=1, elapsed_realtime_ms=100)
        ready = dict(first, event='ready', task_owner='', request_count=0)
        waiting = dict(first, event='system_timeout_waiting', task_phase='queued', batch_phase='waiting', task_owner='', elapsed_realtime_ms=200)
        resumed = dict(first, event='resumed', task_owner='second', request_count=2, elapsed_realtime_ms=300)
        done = dict(first, event='completed', task_phase='completed', batch_phase='completed', task_owner='', request_count=2, elapsed_realtime_ms=400)
        return [ready, first, waiting, resumed, done, dict(done, event='cleanup'),
                {'settings_restored': True, 'owned_records_removed': True, 'owned_managers_removed': True}]

    def test_only_queueing_without_real_automatic_reclaim_is_not_success(self):
        rows = self.rows(); self.assertEqual(100, validate_trace(rows, 'timeout-resume')['resume_wait_ms'])
        with self.assertRaises(ValueError): validate_trace(rows[:3] + [rows[-1]], 'timeout-resume')

    def test_reusing_owner_or_changing_batch_is_rejected(self):
        for field, value in [('task_owner', 'first'), ('batch_id', 'other'), ('process', 'other')]:
            rows = self.rows(); rows[3][field] = value
            with self.assertRaises(ValueError): validate_trace(rows, 'timeout-resume')

    def test_permission_and_final_restoration_are_required(self):
        for index, field, value in [(0, 'notifications_enabled', False), (1, 'user_initiated', 1), (-1, 'settings_restored', False)]:
            rows = self.rows(); rows[index][field] = value
            with self.assertRaises(ValueError): validate_trace(rows, 'timeout-resume')


if __name__ == '__main__': unittest.main()
