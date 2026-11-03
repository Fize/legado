import unittest

from check_android_public_download import storage_permission, default_network


class PublicDownloadPreconditionsTest(unittest.TestCase):
    def test_granted_runtime_permission_among_requested_permissions(self):
        text = '  requested permissions:\n    android.permission.WRITE_EXTERNAL_STORAGE\n  runtime permissions:\n    android.permission.WRITE_EXTERNAL_STORAGE: granted=true, flags=[ USER_SET ]\n'
        self.assertTrue(storage_permission(text))

    def test_denied_runtime_permission_is_not_treated_as_missing(self):
        self.assertFalse(storage_permission('    android.permission.WRITE_EXTERNAL_STORAGE: granted=false, flags=[]\n'))

    def test_requested_permission_alone_cannot_authorize_mutation(self):
        with self.assertRaises(ValueError):
            storage_permission('    android.permission.WRITE_EXTERNAL_STORAGE\n')

    def test_multiple_user_states_cannot_be_silently_selected(self):
        with self.assertRaises(ValueError):
            storage_permission(' android.permission.WRITE_EXTERNAL_STORAGE: granted=true\n android.permission.WRITE_EXTERNAL_STORAGE: granted=false\n')

    def test_android8_never_granted_permission_requires_complete_single_user_dump(self):
        text = ' requested permissions:\n  android.permission.WRITE_EXTERNAL_STORAGE\n User 0: installed=true hidden=false\n  runtime permissions:\n  enabledComponents:\n'
        self.assertFalse(storage_permission(text))
        with self.assertRaises(ValueError):
            storage_permission(text.replace('User 0:', 'User 10:'))

    def test_missing_runtime_section_cannot_imply_denial(self):
        with self.assertRaises(ValueError):
            storage_permission(' android.permission.WRITE_EXTERNAL_STORAGE\n User 0: installed=true\n')

    def test_connected_network_is_selected_from_current_state(self):
        self.assertEqual(101, default_network('NetworkProviders for:\nActive default network: 101\nCurrent Networks:\n'))

    def test_disconnected_and_missing_network_are_rejected(self):
        for text in ['Active default network: none\n', 'No network dump']:
            with self.assertRaises(ValueError):
                default_network(text)

    def test_ambiguous_network_state_is_rejected(self):
        with self.assertRaises(ValueError):
            default_network('Active default network: 100\nActive default network: 101\n')
