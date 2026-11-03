import unittest

from check_android_download_reboot import CAPTIVE_PORTAL_SETTINGS, validate_reboot


class DownloadRebootProofTest(unittest.TestCase):
    def test_validation_harness_restores_all_captive_portal_keys(self):
        self.assertEqual(('captive_portal_server','captive_portal_http_url','captive_portal_https_url','captive_portal_fallback_url'),CAPTIVE_PORTAL_SETTINGS)

    def values(self):
        before='11111111-1111-4111-8111-111111111111';after='22222222-2222-4222-8222-222222222222'
        old=dict(id='case',pid=100,task='task',batch='old',dispatch='dispatch',process='process',job_persisted=False,boot_before=before)
        seen=dict(id='case',pid=100,old_pid=100,task='task',old_batch='old',boot_before=before,boot_after=after,old_job_absent=True,interrupted_owner_empty=True,saved_chapter_preserved=True)
        done=dict(seen,new_batch='new',new_dispatch='new-dispatch',new_process='new-process',phase='completed',actual_retry_button=True,fresh_chapter_saved=True)
        requests=[dict(path='/case/chapter/1',attempt=i) for i in [1,2]]
        proof=dict(boot_before=before,boot_after=after,boot_completed=True,user_unlocked=True,network_connected=True,fixture_reachable=True,reverse_reconnected=True,old_job_missing_before_ui=True,post_boot_idle_seconds=10.1,requests_before_open=1,requests_before_retry=1,internet_validated=True)
        return old,seen,done,requests,proof

    def test_real_boot_change_can_pass_even_when_pid_number_is_reused(self):
        validate_reboot(*self.values())

    def test_process_restart_without_new_kernel_boot_is_rejected(self):
        v=list(self.values());v[4]['boot_after']=v[4]['boot_before'];v[1]['pid']=200;v[2]['pid']=200
        with self.assertRaises(ValueError):validate_reboot(*v)

    def test_missing_readiness_or_network_mapping_cannot_prove_no_automatic_request(self):
        for field in ['boot_completed','user_unlocked','network_connected','internet_validated','fixture_reachable','reverse_reconnected','old_job_missing_before_ui']:
            v=list(self.values());v[4][field]=False
            with self.assertRaises(ValueError):validate_reboot(*v)

    def test_policy_change_to_persisted_job_must_be_explicit(self):
        v=list(self.values());v[0]['job_persisted']=True
        with self.assertRaises(ValueError):validate_reboot(*v)

    def test_stale_or_incomplete_ui_retry_and_lost_cache_are_rejected(self):
        for field,value in [('new_batch','old'),('new_dispatch','dispatch'),('new_process','process'),('phase','queued'),('actual_retry_button',False),('fresh_chapter_saved',False),('saved_chapter_preserved',False)]:
            v=list(self.values());v[2][field]=value
            with self.assertRaises(ValueError):validate_reboot(*v)

    def test_short_idle_or_silent_resume_or_refetching_cached_chapter_is_rejected(self):
        for field,value in [('post_boot_idle_seconds',9),('requests_before_open',2),('requests_before_retry',2)]:
            v=list(self.values());v[4][field]=value
            with self.assertRaises(ValueError):validate_reboot(*v)
        v=list(self.values());v[3][1]['path']='/case/chapter/0'
        with self.assertRaises(ValueError):validate_reboot(*v)


if __name__=='__main__':unittest.main()
