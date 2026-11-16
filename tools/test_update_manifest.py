import unittest
from update_manifest import apk_info


class UpdateManifestTest(unittest.TestCase):
    def test_actual_abi_code_is_preserved(self):
        for code in (1030, 2030, 3030, 4030, 5030):
            text = f"package: name='com.aly.sahlpdf' versionCode='{code}' versionName='1.5.0' platformBuildVersionName='16'\n"
            self.assertEqual({"code": code}, apk_info(text, "1.5.0"))

    def test_other_app_old_version_and_malformed_metadata_are_rejected(self):
        for text in (
            "package: name='other.app' versionCode='1030' versionName='1.5.0'",
            "package: name='com.aly.sahlpdf' versionCode='1030' versionName='1.4.0'",
            "package: name='com.aly.sahlpdf' versionCode='0' versionName='1.5.0'",
            "missing manifest",
        ):
            with self.assertRaises(ValueError):
                apk_info(text, "1.5.0")


if __name__ == "__main__":
    unittest.main()
