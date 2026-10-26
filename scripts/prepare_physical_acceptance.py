#!/usr/bin/env python3
"""Stage an offline synthetic installer host using exact published SDK 0.2.0 bytes."""
from pathlib import Path
import argparse
import hashlib
import json
import shutil

ROOT = Path(__file__).resolve().parent.parent


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk-aar", type=Path, required=True)
    parser.add_argument("--round", type=int, default=1, choices=(1, 2))
    args = parser.parse_args()
    data = args.sdk_aar.read_bytes()
    if hashlib.sha256(data).hexdigest() != "ede8c4b3660654918b15004b17d0633bc8c7e1989c1548786671b98be9ea4854":
        raise SystemExit("Expected exact immutable published 0.2.0 AAR")
    destination = ROOT / ("build/physical-acceptance" if args.round == 1 else "build/physical-acceptance-round2")
    if destination.exists():
        raise SystemExit("Preserve the existing generated fixture; do not overwrite")
    destination.mkdir(parents=True)
    for name in ("build.gradle.kts", "gradle.properties", "buildscript-gradle.lockfile"):
        shutil.copyfile(ROOT / name, destination / name)
    settings = (ROOT / "settings.gradle.kts").read_text().split('include(":updater")')[0]
    (destination / "settings.gradle.kts").write_text(settings + 'include(":app")\n')
    (destination / "gradle").mkdir()
    shutil.copyfile(ROOT / "gradle/verification-metadata.xml", destination / "gradle/verification-metadata.xml")
    app = destination / "app"
    (app / "libs").mkdir(parents=True)
    (app / "libs/sdk.aar").write_bytes(data)
    build = (ROOT / "scripts/upgrade_acceptance/app.gradle.kts").read_text()
    build = build.replace('namespace = "example.upgrade"', 'namespace = "example.installhost"')
    build = build.replace('io.github.phillip9933.updateracceptance.migration', 'io.github.phillip9933.updateracceptance.install20261026')
    build = build.replace('"legacy"', '"base"').replace('"current"', '"candidate"')
    build = build.replace('versionCode = 1;', 'versionCode = 10;').replace('versionCode = 2;', 'versionCode = 11;')
    build = build.replace('    buildFeatures', '    buildTypes.getByName("debug") { isDebuggable = false }\n    buildFeatures')
    build = build.replace('    "legacyImplementation"(files("libs/legacy.aar"))\n    "currentImplementation"(files("libs/current.aar"))',
                          '    implementation(files("libs/sdk.aar"))')
    candidate_version = "1.0.1" if args.round == 1 else "1.0.2"
    if args.round == 2:
        build = build.replace('versionCode = 10; versionName = "1.0.0"', 'versionCode = 11; versionName = "1.0.1"')
        build = build.replace('create("candidate") { dimension = "sdk"; versionCode = 11; versionName = "1.0.1" }', 'create("candidate") { dimension = "sdk"; versionCode = 12; versionName = "1.0.2" }')
    build = build.replace("    buildFeatures", "    defaultConfig { buildConfigField(\"String\", \"CANDIDATE_VERSION\", " + json.dumps(json.dumps(candidate_version)) + ") }\n    buildFeatures")
    build += '''
val stageCandidate = tasks.register<Copy>("stageCandidate") {
    dependsOn("assembleCandidateDebug")
    from(layout.buildDirectory.file("outputs/apk/candidate/debug/app-candidate-debug.apk"))
    into(layout.buildDirectory.dir("generated/baseAssets"))
    rename { "candidate.apk" }
}
android.sourceSets.getByName("base").assets.srcDir(stageCandidate)
tasks.matching { it.name == "mergeBaseDebugAssets" }.configureEach { dependsOn(stageCandidate) }
tasks.matching { it.name.contains("BaseDebug") && (it.name.contains("Lint") || it.name.startsWith("lint")) }.configureEach { dependsOn(stageCandidate) }
'''
    (app / "build.gradle.kts").write_text(build)
    source = app / "src/main/java/example/installhost"
    source.mkdir(parents=True)
    shutil.copyfile(ROOT / "scripts/physical_acceptance/HostActivity.kt", source / "HostActivity.kt")
    (app / "src/main/AndroidManifest.xml").write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android">
<uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES"/>
<application android:label="Synthetic Updater Acceptance" android:allowBackup="false">
<activity android:name="example.installhost.HostActivity" android:exported="true">
<intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter>
</activity></application></manifest>''')
    print(destination)
    print("Only synthetic APKs and disposable default test signing; no device actions performed.")


if __name__ == "__main__":
    main()
