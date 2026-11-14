import unittest
from check_versions import check, version, shipped, CONFIG

def source(name="1.3.0", code=3):
    return f'versionName "{name}"\nversionCode {code}\n'

class VersionPolicyTests(unittest.TestCase):
    def test_semantic_versions(self):
        for bad in ("1.3", "01.3.0", "1.3.0-beta", "v1.3.0"):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                version(source(bad))
    def test_legacy_migration(self):
        self.assertEqual((1, 2, 0), version(source("1.2 (MJ PDF 3.1.0)"), False)[1])
    def test_source_requires_bump(self):
        self.assertTrue(check(["app/src/main/Main.java"], source(), source()))
    def test_increase_passes(self):
        self.assertEqual([], check(["app/src/main/Main.java"], source("1.2", 2), source()))
    def test_decrease_fails(self):
        self.assertTrue(check(["app/build.gradle"], source("2.0.0"), source()))
    def test_test_and_docs_changes(self):
        for path in ("README.md", "docs/setup.md", "app/src/test/Test.java", "app/src/androidTest/Test.java"):
            self.assertFalse(shipped(path), path)
            self.assertEqual([], check([path], source(), source()))
    def test_shared_inputs(self):
        for path in CONFIG["files"] + [p + "input" for p in CONFIG["prefixes"]]:
            self.assertTrue(shipped(path), path)
    def test_literal_android_code(self):
        if CONFIG["manual_code"]:
            self.assertTrue(check(["app/build.gradle"], source("1.2.0", 3), source("1.3.0", 3)))
    def test_policy_only_change(self):
        self.assertEqual([], check(["tools/check_versions.py"], source(), source()))
    def test_release_version_always_valid(self):
        self.assertTrue(check(["README.md"], source(), source("1.3")))

if __name__ == "__main__":
    unittest.main()
