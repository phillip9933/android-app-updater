# Android App Updater

A standalone Kotlin Android library for public GitHub release checks and verified, user-confirmed APK updates. It contains no app UI, cloud account access or Raiun dependency. Minimum Android API: 26. Built with JDK 21, Gradle 8.13, AGP 8.13.2 and Kotlin 2.2.20.

## Consume an explicit version

Use `dev.rogers:android-updater:0.1.2`, never a dynamic version, `SNAPSHOT`, sibling source path or mutable Git branch. Version 0.1.2 is the first public SDK prerelease. Versioned source and checksum-pinned Maven bundles are available from [GitHub Releases](https://github.com/phillip9933/android-app-updater/releases). It is not yet published to Maven Central. Raiun consumes a checksum-pinned copy of its Maven artifacts and source bundle under `apps/android/third_party/android-updater/`. Other apps can use the same Maven bundle without accessing this working directory. A future public repository changes artifact delivery, not the app-facing API.

Copy the exported bundle into the consuming project and add its `maven/` directory to `dependencyResolutionManagement.repositories`:

```kotlin
exclusiveContent {
    forRepository {
        maven { url = uri(rootDir.resolve("third_party/android-updater/maven")) }
    }
    filter { includeModule("dev.rogers", "android-updater") }
}
```

Retain and verify the bundle's SHA256SUMS before resolving dependencies; Raiun's `apps/android/settings.gradle.kts` is a working example that also rejects unlisted Maven files. Then add the explicit dependency and app configuration:

```kotlin
dependencies {
    implementation("dev.rogers:android-updater:0.1.2")
}

val config = UpdateConfig(
    owner = "example-owner",
    repository = "example-app",
    tagPrefix = "android-v",
    assetPrefix = "example-android-",
)
val updater = UpdateManager.get(applicationContext, config)
updater.initialize()
```

The library exposes `StateFlow<UpdateState>` and operations for checking, choosing stable/beta, dismissal, download/cancellation and preparing an installer Intent. Keep Compose styling, translations, permission handling and launching the returned Intent in the app. Call `prepareInstall()` on an explicit foreground user action; never start an installer from a worker. An installer return does not prove installation succeeded.

The app must explicitly declare `INTERNET`, `ACCESS_NETWORK_STATE` and `REQUEST_INSTALL_PACKAGES` where its distribution and privacy policy permit them. The library itself does not declare INTERNET or REQUEST_INSTALL_PACKAGES. WorkManager can contribute its own normal permissions through manifest merging; inspect the final app manifest. It contributes a nonexported FileProvider at `${applicationId}.android-updates`, scoped to the `updates/` cache subdirectory. Do not duplicate that provider in the host manifest. WorkManager configuration must remain available after process recreation.

For example tags `android-v1.2.3` and `android-v1.3.0-beta.1`, attach exactly one universal release APK named `example-android-1.2.3.apk` or `example-android-1.3.0-beta.1.apk`. Mark betas as prereleases. APK versionName must match the tag version; versionCode must increase for every distributed build and retain the installed signing identity. Different apps must use distinct owner/repository/prefix configuration. Legacy tags are accepted only when explicitly listed in `legacyVersions` with their `legacyAssetPrefix`.

## Security and behavior

The feed uses a public, unauthenticated GitHub client with no cloud credentials or cookies. Exact repository/asset URLs are checked, redirects restricted to GitHub asset hosts, and metadata/download size and time bounds enforced. Checks run about daily by default, subject to Android scheduling; Wi-Fi downloads are opt-in. Downloads are private, streamed, cancellable and verified for exact size and advertised SHA-256 when available. Package ID, version, minimum SDK, debug flag and installed signer set are checked before publishing the cached APK and checked again before installer handoff. Android enforces final package signature validity and confirms installation.

Checksums authenticate bytes against metadata, not the release author's intent; keep the release signing key protected. Current strict signer-set matching does not support automatic signing-key rotation. The library does not handle Play-delivered split APKs, silent installs, arbitrary update servers, private GitHub repositories or desktop installation. The feed is bounded to the most recent 100 GitHub releases and 2 MiB; APKs are bounded to 512 MiB. No compatibility guarantee is made for app-store policies; apps must choose their own allowed distribution route.

Updater metadata and cached APKs are separate from application/vault data. Keeping the installed app is essential; never recommend uninstalling to work around a signing mismatch. Automated tests do not replace a real signed upgrade test.

## Build, test and package

```sh
./gradlew --no-daemon ktlintCheck detekt :updater:testDebugUnitTest :updater:lintRelease :updater:assembleRelease
./gradlew --no-daemon :updater:publishReleasePublicationToBundleRepository
```

The Maven repository is generated at `build/maven/`. `scripts/export_bundle.py DESTINATION` copies the pinned module artifacts, license and complete source snapshot to a consuming app's vendor directory and writes `SHA256SUMS`. Run it only after tests pass and the exact library publication is built. Once a version has been distributed, never rebuild changed bytes under the same version: bump the version, publish new artifacts, review the diff and deliberately update each consumer's pin.

A release needs a clean source revision, passing host/static/lint checks and a signed native install/upgrade journey on API 26, API 35 and a physical device. Do not publish production credentials or app APKs from this repository.

## Local verification

The earlier local 0.1.1 candidate recorded 43 passing host tests. The public 0.1.2 publication build passed ktlint, detekt, release lint (zero errors, 11 warnings and one hint) and Maven publication, reusing those 43 passing test results on Linux/JDK 21 on 2026-10-24. See docs/TESTING.md for scope and task reuse. Fixtures include two app identities, previous Raiun feed/policy regressions, worker migration, changed-source tokens, version-code startup gates, storage registration failure and missing network permission. Signed platform installation and API 26/API 35 device acceptance remain pending.

## About this project

I build projects to solve problems I run into in my own life. I share them because I believe in open source and hope others can learn from them, adapt them or find them useful. Public availability does not promise production readiness, a support schedule or suitability for every setup.

## Contribute and validate

Read [Contributing](CONTRIBUTING.md), [Security](SECURITY.md), [architecture](docs/ARCHITECTURE.md), [testing evidence](docs/TESTING.md) and [release procedure](docs/RELEASING.md). AI tools assist analysis, code, tests and documentation; contribution summaries must disclose material assistance and distinguish checks actually run from pending checks. Do not share real vault data, credentials or signing material.

## License and provenance

Extracted from the local Raiun Android updater implementation. Distributed under the existing GNU Affero General Public License version 3; see LICENSE. The bundle includes corresponding library source/build files. Extraction and local packaging do not publish or relicense Raiun or other apps.
