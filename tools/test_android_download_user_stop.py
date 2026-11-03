import unittest

from check_android_download_user_stop import validate_result, validate_system_ui_stop


class DownloadUserStopProofTest(unittest.TestCase):
    def proof(self):
        old = dict(id='case', pid=100, user_initiated=True, wall_time_ms=1000,
                   task='task', batch='old-batch', dispatch='old-dispatch', process='old-process')
        observed = dict(id='case', old_pid=100, pid=200, exit_reason=10, exit_time=1001,
                        task='task', old_batch='old-batch', interrupted_owner_empty=True, saved_chapter_preserved=True)
        retried = dict(observed, new_batch='new-batch', new_dispatch='new-dispatch', new_process='new-process',
                       phase='completed', actual_retry_button=True, fresh_chapter_saved=True)
        requests = [dict(path='/case/chapter/1', attempt=i) for i in [1, 2]]
        return old, observed, retried, requests, 10.1

    def test_actual_death_and_completed_retry_can_pass(self):
        validate_result(*self.proof())

    def test_live_old_process_or_other_exit_reason_cannot_prove_user_stop(self):
        for field, value in [('pid', 100), ('exit_reason', 9), ('exit_time', 999), ('old_pid', 101)]:
            values = list(self.proof()); values[1][field] = value
            with self.assertRaises(ValueError): validate_result(*values)

    def test_launch_or_queued_retry_does_not_prove_saved_content(self):
        for field, value in [('phase', 'queued'), ('actual_retry_button', False), ('fresh_chapter_saved', False), ('saved_chapter_preserved', False)]:
            values = list(self.proof()); values[2][field] = value
            with self.assertRaises(ValueError): validate_result(*values)

    def test_old_batch_dispatch_process_or_task_cannot_be_reused(self):
        for field, value in [('new_batch', 'old-batch'), ('new_dispatch', 'old-dispatch'), ('new_process', 'old-process'), ('task', 'other-task')]:
            values = list(self.proof()); values[2][field] = value
            with self.assertRaises(ValueError): validate_result(*values)

    def test_missing_second_request_or_overwriting_cached_chapter_is_rejected(self):
        values = list(self.proof()); values[3] = values[3][:1]
        with self.assertRaises(ValueError): validate_result(*values)
        values = list(self.proof()); values[3][1]['path'] = '/case/chapter/0'
        with self.assertRaises(ValueError): validate_result(*values)

    def test_short_observation_or_non_uidt_job_is_not_enough(self):
        values = list(self.proof()); values[4] = 9.9
        with self.assertRaises(ValueError): validate_result(*values)
        values = list(self.proof()); values[0]['user_initiated'] = 1
        with self.assertRaises(ValueError): validate_result(*values)

    def test_other_app_or_non_system_stop_control_cannot_prove_task_manager(self):
        proof = dict(app_label='owned', button_resource_id='com.android.systemui:id/fgs_manager_app_item_stop_button',
                     bounds=[10,20,100,80], actual_pointer_tap=True)
        validate_system_ui_stop(proof, 'owned')
        for field, value in [('app_label','other'), ('button_resource_id','app:id/stop'), ('bounds',[0,0,0,80]), ('actual_pointer_tap',1)]:
            with self.assertRaises(ValueError): validate_system_ui_stop(dict(proof, **{field:value}), 'owned')


if __name__ == '__main__': unittest.main()
