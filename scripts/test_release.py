import unittest
from release_version import release_version
from verify_release import verify_metadata


class ReleaseVersionTests(unittest.TestCase):
    def test_versions_increase_across_patch_minor_and_major(self):
        codes = [release_version(tag)[1] for tag in ["v1.2.0", "v1.2.1", "v1.3.0", "v2.0.0"]]
        self.assertEqual(codes, sorted(set(codes)))
        self.assertEqual(release_version("v1.2.0"), ("1.2.0", 1002000))

    def test_invalid_tags_and_overflow_are_rejected(self):
        for tag in ["main", "v1.2", "v01.2.3", "v1.2.3; echo unsafe", "v1.1000.0", "v9999.0.0", "v0.0.0"]:
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                release_version(tag)


class ReleaseVerificationTests(unittest.TestCase):
    certificate = "ab" * 32
    badging = "package: name='com.runzone' versionCode='1002000' versionName='1.2.0'\n"
    signature = f"Signer #1 certificate SHA-256 digest: {certificate}\n"

    def test_matching_production_apk_is_accepted(self):
        verify_metadata(self.badging, self.signature, "1.2.0", 1002000, self.certificate)

    def test_debug_wrong_version_and_wrong_signer_are_rejected(self):
        for badging, signature in [
            (self.badging + "application-debuggable\n", self.signature),
            (self.badging.replace("1.2.0", "1.2.1"), self.signature),
            (self.badging, self.signature.replace(self.certificate, "cd" * 32)),
            (self.badging, "unsigned"),
        ]:
            with self.subTest(badging=badging, signature=signature), self.assertRaises(ValueError):
                verify_metadata(badging, signature, "1.2.0", 1002000, self.certificate)


if __name__ == "__main__":
    unittest.main()
