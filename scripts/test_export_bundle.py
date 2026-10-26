"""Synthetic export security regressions; no Android SDK, network or signing required."""
from pathlib import Path
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location("export_bundle", Path(__file__).with_name("export_bundle.py"))
exporter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(exporter)

FAKE_GRADLE = '''#!/usr/bin/env python3
from pathlib import Path
import json, zipfile
root = Path.cwd()
p = root/'build/maven/io/github/phillip9933/android-updater/0.2.1'
p.mkdir(parents=True)
for suffix in ('.aar', '-sources.jar'):
 with zipfile.ZipFile(p/('android-updater-0.2.1'+suffix),'w') as z:z.writestr('fresh.txt','built from clean snapshot')
(p/'android-updater-0.2.1.pom').write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><groupId>io.github.phillip9933</groupId><artifactId>android-updater</artifactId><version>0.2.1</version></project>')
(p/'android-updater-0.2.1.module').write_text(json.dumps({'component':{'group':'io.github.phillip9933','module':'android-updater','version':'0.2.1'}}))
'''


class ExportSecurityTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="updater-export-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name) / "repo"
        self.root.mkdir()
        (self.root / "updater").mkdir()
        (self.root / "updater/build.gradle.kts").write_text('group = "io.github.phillip9933"\nversion = "0.2.1"\n')
        (self.root / ".gitignore").write_text('build/\nignored-private-note.txt\n')
        (self.root / "LICENSE").write_text("Synthetic license")
        (self.root / "gradlew").write_text(FAKE_GRADLE)
        (self.root / "gradlew").chmod(0o755)
        (self.root / "gradlew.bat").write_text("Synthetic unused Windows wrapper")
        self.git("init", "-q")
        self.commit()
        self.destination = Path(self.temp.name) / "export"
        self.root_patch = patch.object(exporter, "ROOT", self.root)
        self.root_patch.start()
        self.addCleanup(self.root_patch.stop)

    def git(self, *args):
        return subprocess.run(["git", "-c", "core.hooksPath=/dev/null", "-c", "user.name=Synthetic",
                               "-c", "user.email=test@example.invalid", *args],
                              cwd=self.root, check=True, capture_output=True).stdout.decode().strip()

    def commit(self):
        self.git("add", ".")
        self.git("commit", "-qm", "synthetic fixture")

    def export(self):
        with patch.object(sys, "argv", ["export_bundle.py", str(self.destination)]):
            exporter.main()

    def test_untracked_file_rejected_without_output(self):
        (self.root / "private-note.txt").write_text("synthetic private text")
        with self.assertRaisesRegex(SystemExit, "clean committed"):
            self.export()
        self.assertFalse(self.destination.exists())

    def test_dirty_and_staged_sources_rejected(self):
        (self.root / "LICENSE").write_text("modified")
        with self.assertRaisesRegex(SystemExit, "clean committed"):
            self.export()
        self.git("add", "LICENSE")
        with self.assertRaisesRegex(SystemExit, "clean committed"):
            self.export()

    def test_ignored_file_excluded_and_stale_artifact_never_used(self):
        (self.root / "ignored-private-note.txt").write_text("synthetic ignored private text")
        stale = self.root / "build/maven" / exporter.COORDINATE
        stale.mkdir(parents=True)
        (stale / "android-updater-0.2.1.aar").write_text("synthetic stale artifact")
        self.export()
        with zipfile.ZipFile(self.destination / "android-app-updater-0.2.1-source.zip") as z:
            self.assertFalse(any("private-note" in n or "/build/" in n for n in z.namelist()))
            self.assertEqual(0o644, z.getinfo("android-app-updater-0.2.1/LICENSE").external_attr >> 16)
            self.assertEqual(0o755, z.getinfo("android-app-updater-0.2.1/gradlew").external_attr >> 16)
        with zipfile.ZipFile(self.destination / "maven" / exporter.COORDINATE / "android-updater-0.2.1.aar") as z:
            self.assertEqual(b"built from clean snapshot", z.read("fresh.txt"))
        self.assertEqual(self.git("rev-parse", "HEAD"), (self.destination / "SOURCE_REVISION.txt").read_text().strip())

    def test_symlink_source_rejected(self):
        (self.root / "link").symlink_to("LICENSE")
        self.commit()
        with self.assertRaisesRegex(SystemExit, "regular files"):
            self.export()

    def test_wrong_committed_version_rejected(self):
        p = self.root / "updater/build.gradle.kts"
        p.write_text(p.read_text().replace("0.2.1", "0.1.3"))
        self.commit()
        with self.assertRaisesRegex(SystemExit, "publication identity"):
            self.export()

    def test_wrong_built_maven_identity_rejected(self):
        p = self.root / "gradlew"
        p.write_text(FAKE_GRADLE.replace("<groupId>io.github.phillip9933", "<groupId>attacker"))
        self.commit()
        with self.assertRaisesRegex(SystemExit, "Maven identity"):
            self.export()
        self.assertFalse(self.destination.exists())

    def test_failed_build_leaves_destination_absent(self):
        (self.root / "gradlew").write_text("#!/usr/bin/env python3\nraise SystemExit(1)\n")
        self.commit()
        with self.assertRaises(subprocess.CalledProcessError):
            self.export()
        self.assertFalse(self.destination.exists())

    def test_existing_output_and_source_destination_rejected(self):
        self.destination.mkdir()
        (self.destination / "keep.txt").write_text("preserve")
        with self.assertRaisesRegex(SystemExit, "do not overwrite"):
            self.export()
        self.assertEqual("preserve", (self.destination / "keep.txt").read_text())
        self.destination = self.root / "source-output"
        with self.assertRaisesRegex(SystemExit, "outside the source"):
            self.export()

    def test_source_change_during_build_rejected(self):
        p = self.root / "gradlew"
        p.write_text(FAKE_GRADLE + f"\nPath({str(self.root / 'LICENSE')!r}).write_text('changed during build')\n")
        self.commit()
        with self.assertRaisesRegex(SystemExit, "clean committed"):
            self.export()
        self.assertFalse(self.destination.exists())


if __name__ == "__main__":
    unittest.main()
