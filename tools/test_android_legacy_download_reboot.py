import unittest

from check_android_legacy_download_reboot import validate_cancel_reboot


class LegacyDownloadCancelRebootProofTest(unittest.TestCase):
    def values(self):
        before = '11111111-1111-4111-8111-111111111111'
        after = '22222222-2222-4222-8222-222222222222'
        prepared = {
            'id': 'case', 'pid': 100, 'uid': 10100, 'boot_before': before,
            'notification_cancel_action': True,
            'task': 'task', 'batch': 'old',
            'task_phase': 'cancelled', 'task_owner': '',
            'work_state': 'CANCELLED',
            'cached_chapter_preserved': True,
            'uncached_chapter_absent_at_cancel': True,
        }
        observed = {
            'id': 'case', 'boot_before': before, 'boot_after': after,
            'task': 'task', 'old_batch': 'old',
            'task_phase': 'cancelled', 'task_owner': '',
            'work_state': 'CANCELLED',
            'cancelled_task_preserved': True,
            'cached_chapter_preserved': True,
            'uncached_chapter_absent': True,
        }
        requests = [{'path': '/case/chapter/1', 'attempt': 1}]
        proof = {
            'boot_before': before, 'boot_after': after,
            'boot_completed': True, 'user_unlocked': True,
            'network_connected': True, 'internet_validated': True,
            'fixture_reachable': True, 'reverse_reconnected': True,
            'old_work_not_executed': True,
            'post_boot_idle_seconds': 10.1,
            'requests_before_open': 1, 'requests_after_ui': 1,
        }
        return prepared, observed, requests, proof

    def test_valid_evidence_passes(self):
        validate_cancel_reboot(*self.values())

    def test_process_restart_without_a_new_kernel_boot_is_rejected(self):
        values = list(self.values())
        values[3]['boot_after'] = values[3]['boot_before']
        values[1]['boot_after'] = values[3]['boot_after']
        with self.assertRaises(ValueError):
            validate_cancel_reboot(*values)

    def test_revival_or_schedulable_work_is_rejected(self):
        for field, value in [
            ('task_phase', 'running'),
            ('task_owner', 'owner'),
            ('work_state', 'ENQUEUED'),
        ]:
            values = list(self.values())
            values[1][field] = value
            with self.assertRaises(ValueError):
                validate_cancel_reboot(*values)

    def test_missing_cache_or_cancelled_record_is_rejected(self):
        for field in [
            'cancelled_task_preserved', 'cached_chapter_preserved',
            'uncached_chapter_absent',
        ]:
            values = list(self.values())
            values[1][field] = False
            with self.assertRaises(ValueError):
                validate_cancel_reboot(*values)

    def test_short_idle_or_new_request_is_rejected(self):
        values = list(self.values())
        values[3]['post_boot_idle_seconds'] = 9
        with self.assertRaises(ValueError):
            validate_cancel_reboot(*values)

        values = list(self.values())
        values[2].append({'path': '/case/chapter/1', 'attempt': 2})
        with self.assertRaises(ValueError):
            validate_cancel_reboot(*values)

    def test_cached_chapter_request_is_rejected(self):
        values = list(self.values())
        values[2][0]['path'] = '/case/chapter/0'
        with self.assertRaises(ValueError):
            validate_cancel_reboot(*values)


if __name__ == '__main__':
    unittest.main()
