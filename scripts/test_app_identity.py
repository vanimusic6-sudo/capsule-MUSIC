import unittest
from verify_app_identity import parse_xmltree, validate_manifest


def manifest(package):
    return parse_xmltree(f'''N: android=http://schemas.android.com/apk/res/android
  E: manifest (line=1)
    A: package="{package}" (Raw: "{package}")
    E: application (line=2)
      A: android:name(0x01010003)="com.nikhil.yt.App"
      E: activity (line=3)
        A: android:name(0x01010003)="com.nikhil.yt.MainActivity"
      E: service (line=4)
        A: android:name(0x01010003)="com.nikhil.yt.playback.MusicService"
      E: provider (line=5)
        A: android:authorities(0x01010018)="{package}.FileProvider"
      E: provider (line=6)
        A: android:authorities(0x01010018)="{package}.capsule.video.extractor"
''')


class AppIdentityTest(unittest.TestCase):
    def test_different_install_ids_can_keep_the_original_class_namespace(self):
        for suffix in ["", ".dev", ".debug"]:
            package = "com.vanimusic.capsule" + suffix
            self.assertEqual(2, len(validate_manifest(manifest(package), package)))

    def test_rejects_an_upstream_or_cross_variant_authority(self):
        for authority in ["com.nikhil.yt.FileProvider", "com.vanimusic.capsule.FileProvider"]:
            root = manifest("com.vanimusic.capsule.dev")
            root.find("application/provider").set("authorities", authority)
            with self.assertRaises(ValueError):
                validate_manifest(root, "com.vanimusic.capsule.dev")

    def test_rejects_broken_class_resolution_and_wrong_package(self):
        root = manifest("com.vanimusic.capsule.dev")
        with self.assertRaises(ValueError):
            validate_manifest(root, "com.vanimusic.capsule")
        root.find("application").set("name", "com.vanimusic.capsule.App")
        with self.assertRaises(ValueError):
            validate_manifest(root, "com.vanimusic.capsule.dev")


if __name__ == "__main__":
    unittest.main()
