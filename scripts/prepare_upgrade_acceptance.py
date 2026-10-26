#!/usr/bin/env python3
"""Stage a separate synthetic host against two immutable, checksum-pinned published AARs."""
from pathlib import Path
import argparse
import hashlib
import json
import shutil
import zipfile

ROOT = Path(__file__).resolve().parent.parent
TEMPLATES = ROOT / "scripts/upgrade_acceptance"
PINS = {
    "legacy": ("69cb93078aed03a03257b7c3837a580028daec06a3d97faaf5bab6bc88c40e26", "dev.rogers.appupdater"),
    "current": ("ede8c4b3660654918b15004b17d0633bc8c7e1989c1548786671b98be9ea4854", "io.github.phillip9933.appupdater"),
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--legacy-aar", type=Path, required=True)
    parser.add_argument("--current-aar", type=Path, required=True)
    parser.add_argument("--candidate-sha256", help="Explicit local candidate hash; default verifies published 0.2.0")
    parser.add_argument("--destination", type=Path, default=ROOT / "build/upgrade-acceptance")
    args = parser.parse_args()
    destination = args.destination.resolve()
    if not destination.is_relative_to(ROOT / "build"):
        raise SystemExit("Stage generated projects only under repo/build")
    if destination.exists():
        raise SystemExit("Staging directory exists; preserve evidence and choose a fresh build before retrying")
    inputs = {}
    for name, source in (("legacy", args.legacy_aar), ("current", args.current_aar)):
        data = source.read_bytes()
        expected = args.candidate_sha256 if name == "current" and args.candidate_sha256 else PINS[name][0]
        if hashlib.sha256(data).hexdigest() != expected:
            raise SystemExit(f"Unexpected published {name} AAR bytes")
        with zipfile.ZipFile(source) as archive:
            assert "classes.jar" in archive.namelist()
        inputs[name] = data
    destination.mkdir(parents=True)
    (destination / "SDK_INPUTS.json").write_text(json.dumps({
        "current_is_local_candidate": bool(args.candidate_sha256),
        "sha256": {name: hashlib.sha256(data).hexdigest() for name, data in inputs.items()},
    }, indent=2) + "\n")
    for name in ("build.gradle.kts", "gradle.properties"):
        shutil.copyfile(ROOT / name, destination / name)
    settings = (ROOT / "settings.gradle.kts").read_text()
    settings = settings[:settings.index('include(":updater")')] + 'include(":app")\n'
    (destination / "settings.gradle.kts").write_text(settings)
    (destination / "gradle").mkdir()
    shutil.copyfile(ROOT / "gradle/verification-metadata.xml", destination / "gradle/verification-metadata.xml")
    shutil.copyfile(ROOT / "buildscript-gradle.lockfile", destination / "buildscript-gradle.lockfile")
    app = destination / "app"
    (app / "libs").mkdir(parents=True)
    shutil.copyfile(TEMPLATES / "app.gradle.kts", app / "build.gradle.kts")
    main_source = app / "src/main/java/example/upgrade"
    main_source.mkdir(parents=True)
    shutil.copyfile(TEMPLATES / "ProbeApplication.kt", main_source / "ProbeApplication.kt")
    (app / "src/main/AndroidManifest.xml").write_text(
        '<manifest xmlns:android="http://schemas.android.com/apk/res/android">'
        '<application android:name="example.upgrade.ProbeApplication" '
        'android:label="Synthetic Updater Migration" android:allowBackup="false"/></manifest>')
    tests = app / "src/androidTest/java/example/upgrade"
    tests.mkdir(parents=True)
    shutil.copyfile(TEMPLATES / "UpgradeTest.kt", tests / "UpgradeTest.kt")
    bridge = (TEMPLATES / "Bridge.kt.template").read_text()
    for name, data in inputs.items():
        (app / "libs" / f"{name}.aar").write_bytes(data)
        source = app / f"src/{name}/java/example/upgrade"
        source.mkdir(parents=True)
        (source / "Bridge.kt").write_text(bridge.replace("@NAMESPACE@", PINS[name][1]))
    print(destination)
    print("Synthetic host only; no installation, signing-key selection or device operation performed.")


if __name__ == "__main__":
    main()
