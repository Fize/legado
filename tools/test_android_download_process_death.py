import unittest

from check_android_download_process_death import validate_automatic


class AutomaticDownloadRecoveryTest(unittest.TestCase):
    def values(self):
        old=dict(id='case',pid=100,wall_time_ms=1000,task='task',batch='batch',dispatch='dispatch',owner='old',process='old-process')
        running=dict(id='case',pid=200,old_pid=100,exit_reason=2,exit_status=9,exit_time=1001,task='task',batch='batch',dispatch='dispatch',owner='new',process='new-process',phase='running',no_ui_or_manual_recovery=True,saved_chapter_preserved=True)
        done=dict(running,phase='completed',owner='',fresh_chapter_saved=True)
        requests=[dict(path='/case/chapter/1',attempt=i) for i in [1,2]]
        proof=dict(old_pid_gone=True,http_second_before_reattach=True,active_job_before_reattach=True,not_instrumented_at_restart=True,new_pid_before_reattach=200)
        return old,running,done,requests,proof

    def test_detached_new_process_same_batch_and_completed_content_pass(self):
        validate_automatic(*self.values())

    def test_ui_started_or_still_instrumented_process_is_not_natural_recovery(self):
        for field in ['old_pid_gone','http_second_before_reattach','active_job_before_reattach','not_instrumented_at_restart']:
            v=list(self.values());v[4][field]=False
            with self.assertRaises(ValueError): validate_automatic(*v)
        v=list(self.values());v[4]['new_pid_before_reattach']=100
        with self.assertRaises(ValueError): validate_automatic(*v)

    def test_user_stop_or_other_signal_cannot_be_called_sigkill(self):
        for field,value in [('exit_reason',10),('exit_status',15),('exit_time',999)]:
            v=list(self.values());v[1][field]=value
            with self.assertRaises(ValueError): validate_automatic(*v)

    def test_new_batch_stale_owner_or_manual_recovery_is_rejected(self):
        for field,value in [('batch','other'),('dispatch','other'),('owner','old'),('process','old-process'),('no_ui_or_manual_recovery',False)]:
            v=list(self.values());v[1][field]=value
            with self.assertRaises(ValueError): validate_automatic(*v)

    def test_queue_only_missing_second_request_or_cached_chapter_refetch_is_rejected(self):
        v=list(self.values());v[2]['phase']='queued'
        with self.assertRaises(ValueError): validate_automatic(*v)
        v=list(self.values());v[2]['fresh_chapter_saved']=False
        with self.assertRaises(ValueError): validate_automatic(*v)
        v=list(self.values());v[3]=v[3][:1]
        with self.assertRaises(ValueError): validate_automatic(*v)
        v=list(self.values());v[3][1]['path']='/case/chapter/0'
        with self.assertRaises(ValueError): validate_automatic(*v)


if __name__=='__main__': unittest.main()
