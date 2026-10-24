# Android App Updater

A standalone Kotlin Android library for public GitHub release checks and verified, user-confirmed APK updates. It contains no app UI, cloud account access or application-specific dependency. Minimum Android API: 26. Built with JDK 21, Gradle 8.13, AGP 8.13.2 and Kotlin 2.2.20.

## Security update

Use **0.1.3 or newer**. Version 0.1.2's archive debuggable check is unreliable on Android 15. The fix reads the binary manifest, resolves resource-backed booleans and fails closed on missing/unreadable metadata. See [testing](docs/TESTING.md) for native regression evidence and remaining acceptance gates.

## Consume an explicit version

The next version, **0.2.0**, uses `io.github.phillip9933:android-updater:0.2.0` and the Kotlin package `io.github.phillip9933.appupdater`. Use explicit versions, never dynamic versions, `SNAPSHOT`, sibling source paths or mutable Git branches. Versioned source and checksum-pinned Maven bundles are distributed through [GitHub Releases](https://github.com/phillip9933/android-app-updater/releases); check release availability before pinning a version. The library is not published to Maven Central.

Version 0.2.0 changes the package and Maven group. Update imports and dependency coordinates together. Reinitialize the updater after upgrading: its bootstrap preference namespace changes, so automatic checks start with the new registration. Existing application data is unaffected.

Copy the exported bundle into the consuming project and add its `maven/` directory to `dependencyResolutionManagement.repositories`:

```kotlin
exclusiveContent {
    forRepository {
        maven { url = uri(rootDir.resolve("third_party/android-updater/maven")) }
    }
    filter { includeModule("io.github.phillip9933", "android-updater") }
}
```

Retain and verify the bundle's SHA256SUMS before resolving dependencies, and reject unlisted Maven files. Then add the explicit dependency and app configuration:

```kotlin
dependencies {
    implementation("io.github.phillip9933:android-updater:0.2.0")
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

Version 0.1.3 passed 43 host tests and native verifier regression tests on API 26 and API 35 (10 tests each), including independent GitHub CI runs. The renamed 0.2.0 candidate passed 43 host tests, formatting/static checks, release lint, Maven publication and probe APK assembly locally. Native device tests have not been rerun for 0.2.0. See [testing evidence](docs/TESTING.md) for scope and remaining physical-device acceptance.

## About this project

I build projects to solve problems I run into in my own life. I share them because I believe in open source and hope others can learn from them, adapt them or find them useful. Public availability does not promise production readiness, a support schedule or suitability for every setup.

## Contribute and validate

Read [Contributing](CONTRIBUTING.md), [Security](SECURITY.md), [architecture](docs/ARCHITECTURE.md), [testing evidence](docs/TESTING.md) and [release procedure](docs/RELEASING.md). AI tools assist analysis, code, tests and documentation; contribution summaries must disclose material assistance and distinguish checks actually run from pending checks. Do not share real vault data, credentials or signing material.

## License and provenance

Distributed under the GNU Affero General Public License version 3; see [LICENSE](LICENSE). Exported bundles include corresponding library source and build files.
