import unittest
from capsule_security_audit import guarded_evidence

class AuditGuardTests(unittest.TestCase):
    def test_checksum_must_be_an_active_complete_property(self):
        checksum = "a" * 64
        self.assertIsNotNone(guarded_evidence("BUILD-003", "distributionSha256Sum=" + checksum))
        for value in ("# distributionSha256Sum=" + checksum, "distributionSha256Sum=short", "distributionUrl=https://example"):
            self.assertIsNone(guarded_evidence("BUILD-003", value))

    def test_both_backup_paths_require_both_credential_exclusions(self):
        exclusions = '<exclude domain="file" path="datastore/"/><exclude domain="sharedpref" path="."/>'
        xml = f'<data-extraction-rules><cloud-backup>{exclusions}</cloud-backup><device-transfer>{exclusions}</device-transfer></data-extraction-rules>'
        self.assertIsNotNone(guarded_evidence("DATA-003", xml))
        self.assertIsNone(guarded_evidence("DATA-003", xml.replace(exclusions, "", 1)))
        self.assertIsNone(guarded_evidence("DATA-003", xml.replace('domain="sharedpref"', 'domain="database"')))
        self.assertIsNone(guarded_evidence("DATA-003", "invalid XML"))

    def test_export_filter_must_be_inside_the_preference_loop(self):
        loop = 'for ((key, value) in prefs) {\n if (!BackupSecurity.exportablePreference(key.name)) continue\n'
        self.assertIsNotNone(guarded_evidence("DATA-002", loop))
        self.assertIsNone(guarded_evidence("DATA-002", loop.replace('if (!BackupSecurity.exportablePreference(key.name)) continue', 'serialize(key, value)')))

if __name__ == '__main__':
    unittest.main()
