import copy
import unittest

from check_android_saf_export_death import validate_death, validate_result


class DeathProofTest(unittest.TestCase):
    def setUp(self):
        self.marker = {'id': 'owned', 'pid': 100, 'provider_pid': 200, 'mode': 'hold', 'operation': 'write',
                       'active_descriptors': 1, 'active_renames': 0, 'journal_records': 0, 'persisted_grant': True}
        self.result = {'id': 'owned', 'pid': 300, 'provider_pid': 200, 'mode': 'hold', 'action': 'restore',
                       'old_dispatch_rejected': True, 'retry_completed': True, 'unrelated_preserved': True,
                       'journal_records_recovered': 0, 'persisted_grant': True, 'fragments_before_retry': {'.pending.owned': {'size': 4}},
                       'fragments_after_retry': {'.pending.owned': {'size': 4}}}

    def test_a_java_failure_is_not_a_process_death(self):
        with self.assertRaises(ValueError): validate_death(self.marker, 'hold', 'FAILURES!!! java.lang.AssertionError', [])

    def test_a_still_live_target_does_not_prove_sigkill(self):
        with self.assertRaises(ValueError): validate_death(self.marker, 'hold', 'Process crashed', ['100'])

    def test_a_provider_in_the_target_process_cannot_prove_external_late_io(self):
        self.marker['provider_pid'] = 100
        with self.assertRaises(ValueError): validate_death(self.marker, 'hold', 'Process crashed', [])

    def test_copy_and_publication_markers_are_not_interchangeable(self):
        validate_death(self.marker, 'hold', 'Process crashed', [])
        with self.assertRaises(ValueError): validate_death(self.marker, 'hold_after_backup', 'Process crashed', [])
        self.marker.update(mode='hold_after_backup', operation='rename_after', active_descriptors=0, active_renames=1, journal_records=1, journal_phase='prepared')
        validate_death(self.marker, 'hold_after_backup', 'Process crashed', [])

    def test_recovery_requires_a_new_target_and_the_same_provider(self):
        for field, value in [('pid', 100), ('pid', 200), ('pid', None), ('pid', '300'), ('provider_pid', 201)]:
            result = copy.deepcopy(self.result); result[field] = value
            with self.assertRaises(ValueError): validate_result(result, self.marker, 'restore')

    def test_recovery_proofs_cannot_be_missing_or_truthy_numbers(self):
        for field in ['old_dispatch_rejected', 'retry_completed', 'unrelated_preserved']:
            result = copy.deepcopy(self.result); result[field] = 1
            with self.assertRaises(ValueError): validate_result(result, self.marker, 'restore')

    def test_unexplained_fragment_mutation_is_rejected(self):
        self.result['fragments_after_retry']['.pending.owned']['size'] = 5
        with self.assertRaises(ValueError): validate_result(self.result, self.marker, 'restore')

    def test_fragments_are_reported_and_only_zero_passes_the_cleanup_gate(self):
        self.assertEqual(1, validate_result(self.result, self.marker, 'restore'))
        self.result['fragments_after_retry'] = {}
        self.assertEqual(0, validate_result(self.result, self.marker, 'restore'))

    def test_selected_tree_authorization_must_survive_death(self):
        marker = copy.deepcopy(self.marker); marker['persisted_grant'] = False
        with self.assertRaises(ValueError): validate_death(marker, 'hold', 'Process crashed', [])
        self.result['persisted_grant'] = False
        with self.assertRaises(ValueError): validate_result(self.result, self.marker, 'restore')

    def creation(self):
        marker = copy.deepcopy(self.marker)
        marker.update(mode='hold_main_create_before', operation='create_before', active_creates=1,
                      active_descriptors=0, active_renames=0, staging_records=1, unbound_intents=1, creation_visible=False)
        return marker

    def test_creation_requires_a_durable_unbound_intent_and_active_creator(self):
        for field, value in [('unbound_intents', 0), ('active_creates', 0), ('journal_records', 1), ('active_descriptors', 1)]:
            marker = self.creation(); marker[field] = value
            with self.assertRaises(ValueError): validate_death(marker, marker['mode'], 'Process crashed', [])

    def test_creation_effect_and_target_cannot_be_misreported(self):
        marker = self.creation(); validate_death(marker, marker['mode'], 'Process crashed', [])
        marker['creation_visible'] = True
        with self.assertRaises(ValueError): validate_death(marker, marker['mode'], 'Process crashed', [])
        marker.update(mode='hold_image_create_after', operation='create_after', staging_records=2)
        validate_death(marker, marker['mode'], 'Process crashed', [])
        marker['staging_records'] = 1
        with self.assertRaises(ValueError): validate_death(marker, marker['mode'], 'Process crashed', [])

    def test_an_already_bound_intent_cannot_prove_the_creation_window(self):
        marker = self.creation(); marker['unbound_intents'] = None
        with self.assertRaises(ValueError): validate_death(marker, marker['mode'], 'Process crashed', [])

    def late_result(self):
        marker = self.creation(); marker['late_create'] = True
        result = copy.deepcopy(self.result)
        result.update(mode=marker['mode'], late_create=True, late_intent_retained=True, late_file_observed=True,
                      provider_active_during_first_retry=True, followup_export_completed=True, fragments_after_retry={})
        return marker, result

    def test_late_creation_requires_retention_and_an_actual_followup(self):
        marker, result = self.late_result()
        self.assertEqual(0, validate_result(result, marker, 'restore', late_create=True))
        for field in ['late_intent_retained', 'late_file_observed', 'provider_active_during_first_retry', 'followup_export_completed']:
            changed = copy.deepcopy(result); changed[field] = False
            with self.assertRaises(ValueError): validate_result(changed, marker, 'restore', late_create=True)

    def test_a_completed_creation_cannot_stand_in_for_a_late_one(self):
        marker, result = self.late_result(); marker['creation_visible'] = True
        with self.assertRaises(ValueError): validate_result(result, marker, 'restore', late_create=True)

    def service_proofs(self):
        marker = copy.deepcopy(self.marker); result = copy.deepcopy(self.result)
        marker.update(foreground_service=True, service={'pid': 100, 'foreground': True,
            'component': 'io.legado.app.service.BookExportForegroundService',
            'batch': '11111111-1111-4111-8111-111111111111', 'dispatch': '22222222-2222-4222-8222-222222222222',
            'task_owner': '33333333-3333-4333-8333-333333333333'})
        result.update(foreground_service=True, cache_entry_recovered=True, service_stopped_after_retry=True,
            recovery_service_used=False, retry_service={'pid': 300, 'foreground': True,
            'component': 'io.legado.app.service.BookExportForegroundService',
            'batch': '44444444-4444-4444-8444-444444444444', 'dispatch': '55555555-5555-4555-8555-555555555555'})
        return marker, result

    def test_runner_proof_cannot_stand_in_for_a_real_foreground_service(self):
        with self.assertRaises(ValueError): validate_death(self.marker, 'hold', 'Process crashed', [], foreground_service=True)
        marker, result = self.service_proofs()
        validate_death(marker, 'hold', 'Process crashed', [], foreground_service=True)
        for field, value in [('foreground', 1), ('pid', 200), ('component', 'OtherService'), ('task_owner', ''), ('batch', 'old')]:
            changed = copy.deepcopy(marker); changed['service'][field] = value
            with self.assertRaises(ValueError): validate_death(changed, 'hold', 'Process crashed', [], foreground_service=True)

    def test_service_retry_requires_real_cache_recovery_and_a_new_dispatch(self):
        marker, result = self.service_proofs()
        self.assertEqual(1, validate_result(result, marker, 'restore', foreground_service=True))
        for field in ['cache_entry_recovered', 'service_stopped_after_retry']:
            changed = copy.deepcopy(result); changed[field] = False
            with self.assertRaises(ValueError): validate_result(changed, marker, 'restore', foreground_service=True)
        for field in ['batch', 'dispatch']:
            changed = copy.deepcopy(result); changed['retry_service'][field] = marker['service'][field]
            with self.assertRaises(ValueError): validate_result(changed, marker, 'restore', foreground_service=True)

    def test_service_journal_recovery_cannot_use_the_direct_runner_path(self):
        marker, result = self.service_proofs(); marker['journal_records'] = result['journal_records_recovered'] = 1
        with self.assertRaises(ValueError): validate_result(result, marker, 'restore', foreground_service=True)
        result['recovery_service_used'] = True
        self.assertEqual(1, validate_result(result, marker, 'restore', foreground_service=True))

    def user_stop_proofs(self):
        marker, result = self.service_proofs()
        marker.update(termination='user-stop', wall_time_ms=1000)
        result.update(termination='user-stop', exit_info={'pid': 100, 'reason': 10,
            'process': 'com.xiaobaitu.reader.debug', 'timestamp': 1001})
        return marker, result

    def test_self_sigkill_cannot_prove_a_system_user_stop(self):
        marker, result = self.service_proofs()
        with self.assertRaises(ValueError): validate_death(marker, 'hold', 'Process crashed', [], foreground_service=True, termination='user-stop')
        with self.assertRaises(ValueError): validate_result(result, marker, 'restore', foreground_service=True, termination='user-stop')
        marker, result = self.user_stop_proofs()
        validate_death(marker, 'hold', 'Process crashed', [], foreground_service=True, termination='user-stop')
        self.assertEqual(1, validate_result(result, marker, 'restore', foreground_service=True, termination='user-stop'))

    def test_user_stop_requires_exact_system_exit_reason_and_process(self):
        marker, result = self.user_stop_proofs()
        for field, value in [('pid', 200), ('reason', 9), ('reason', '10'), ('process', 'other'), ('timestamp', 999)]:
            changed = copy.deepcopy(result); changed['exit_info'][field] = value
            with self.assertRaises(ValueError): validate_result(changed, marker, 'restore', foreground_service=True, termination='user-stop')

    def test_user_stop_rejects_missing_exit_history(self):
        marker, result = self.user_stop_proofs(); del result['exit_info']
        with self.assertRaises(ValueError): validate_result(result, marker, 'restore', foreground_service=True, termination='user-stop')


if __name__ == '__main__': unittest.main()
