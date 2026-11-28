# Android App Updater

A Kotlin library for Android 8.0 (API 26) and later. It helps an app discover updates on GitHub Releases, verify the APK, and open the Android installer when the user chooses to update.

<a href="https://github.com/phillip9933/android-app-updater/releases/tag/v1.0.0"><img src="docs/badges/github-releases.svg" alt="Get the Android App Updater SDK from GitHub Releases" height="40"></a>

**Support:** [Buy me a coffee](https://buymeacoffee.com/phillip9933) · [GitHub Sponsors](https://github.com/sponsors/phillip9933)

## Features

- Check a public GitHub repository for stable or beta releases of your app.
- Observe check and download progress in your own UI through `StateFlow<UpdateState>`.
- Schedule periodic checks and let users opt in to Wi-Fi downloads.
- Check the package name, version, Android compatibility, debug flag and signing certificates before offering an APK for installation.

## Host app example

The host app chooses when and how to show an update. The [Kotlin example](examples/UpdatePromptController.kt) uses a platform dialog for Download, Later and Install. The [integration guide](docs/UPDATE_PROMPT.md) explains how to connect it to an Activity. The verifier app compiles the example for tests. The distributed SDK has no screens.

## Install the SDK

Download `android-app-updater-1.0.0-bundle.zip` from the [1.0.0 release](https://github.com/phillip9933/android-app-updater/releases/tag/v1.0.0). The bundle contains a local Maven repository and the corresponding source. Compare the ZIP's SHA-256 with the release checksum before extracting anything. For 1.0.0, run this command in the download directory:

```sh
echo '13b0fa5df7524091191e063c9b25bdb4d222866053228754d98ea3f1d1e72763  android-app-updater-1.0.0-bundle.zip' | sha256sum -c -
```

Extract the verified ZIP into an empty `third_party/android-updater/` directory in the host project. From that directory, run `sha256sum -c SHA256SUMS`. Keep the checksums and reject unlisted Maven files when updating the vendored copy. Pin the exact version in Gradle.

Add the bundle’s `maven/` directory to `dependencyResolutionManagement.repositories` in `settings.gradle.kts`.

```kotlin
exclusiveContent {
    forRepository {
        maven { url = uri(rootDir.resolve("third_party/android-updater/maven")) }
    }
    filter { includeModule("io.github.phillip9933", "android-updater") }
}
```

Add the dependency to the host app’s `build.gradle.kts`.

```kotlin
dependencies {
    implementation("io.github.phillip9933:android-updater:1.0.0")
}
```

In your Application, initialize the updater with your app’s GitHub repository and release naming scheme. For example:

```kotlin
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

Apps upgrading from SDK 0.2.2 can keep their imports, configuration and preference names. When migrating from 0.1.x, update the imports and Maven group and initialize the new worker registration. See the [changelog](CHANGELOG.md) for earlier versions.

The app must explicitly declare `INTERNET`, `ACCESS_NETWORK_STATE` and `REQUEST_INSTALL_PACKAGES` where its distribution and privacy policy permit them. The library itself does not declare INTERNET or REQUEST_INSTALL_PACKAGES. WorkManager can contribute its own normal permissions through manifest merging; inspect the final app manifest. The updater contributes a nonexported FileProvider at `${applicationId}.android-updates`, scoped to the `updates/` cache subdirectory. Do not duplicate that provider in the host manifest. WorkManager configuration must remain available after process recreation.

For example tags `android-v1.2.3` and `android-v1.3.0-beta.1`, attach exactly one universal release APK named `example-android-1.2.3.apk` or `example-android-1.3.0-beta.1.apk`. Mark betas as prereleases. APK versionName must match the tag version; versionCode must increase for every distributed build and retain the installed signing identity. Different apps must use distinct owner/repository/prefix configuration. Legacy tags are accepted only when explicitly listed in `legacyVersions` with their `legacyAssetPrefix`.

## Security and behavior

The feed uses a public, unauthenticated GitHub client with no cloud credentials or cookies. Exact repository/asset URLs are checked, redirects restricted to GitHub asset hosts, and metadata/download size and time bounds enforced. Checks run about daily by default, subject to Android scheduling; Wi-Fi downloads are opt-in. Downloads are private, streamed, cancellable and verified for exact size and advertised SHA-256 when available. Package ID, version, minimum SDK, debug flag and installed signer set are checked before publishing the cached APK and checked again before installer handoff. On an Install tap, preserve the explicit system installer component and the read-only URI grant returned by `prepareInstall()`. If no eligible installer is available, the SDK returns no intent. Android verifies the package and asks the user to confirm.

Checksums authenticate bytes against metadata, not the release author's intent; keep the release signing key protected. Current strict signer-set matching does not support automatic signing-key rotation. The library does not handle Play-delivered split APKs, silent installs, arbitrary update servers, private GitHub repositories or desktop installation. The feed is bounded to the most recent 100 GitHub releases and 2 MiB; APKs are bounded to 512 MiB. No compatibility guarantee is made for app-store policies; apps must choose their own allowed distribution route.

Updater metadata and cached APKs are separate from application/vault data. Keeping the installed app is essential; never recommend uninstalling to work around a signing mismatch. Automated tests do not replace a real signed upgrade test.

## Build, test and package

Use JDK 21, Android SDK Platform 36 and Build Tools 35.0.0. From the repository root, run the Gradle wrapper.

```sh
./gradlew --no-daemon ktlintCheck detekt :updater:testDebugUnitTest :updater:lintRelease :updater:assembleRelease
./gradlew --no-daemon :updater:publishReleasePublicationToBundleRepository
```

The Maven repository is generated at `build/maven/`. After tests pass and reviewed changes are committed, run `python3 scripts/export_bundle.py DESTINATION` with JDK 21 and ANDROID_HOME configured. The exporter requires a clean checkout, builds in an isolated snapshot of that commit, validates the publication, and writes the module artifacts, license, committed source ZIP, `SOURCE_REVISION.txt` and `SHA256SUMS`. It never packages existing developer build outputs or untracked files. See [release instructions](docs/RELEASING.md). Once a version has been distributed, never rebuild changed bytes under the same version: bump the version, publish new artifacts, review the diff and deliberately update each consumer's pin.

A release needs a clean source revision, passing host/static/lint checks and a signed native install/upgrade journey on API 26, API 35 and a physical device. Do not publish production credentials or app APKs from this repository.

## Release verification

SDK 1.0.0 passed 63 host tests and 15 native verifier cases each on API 26, API 35 and a Pixel 6 Pro on API 37. The device test covered permission denial, cancellation and a confirmed update that retained private app data. See the [test evidence](docs/TESTING.md) and [release tracker](https://github.com/phillip9933/android-app-updater/issues/12).

## Roadmap and work

[Release roadmap](https://github.com/users/phillip9933/projects/4/views/2) · [Work board](https://github.com/users/phillip9933/projects/4/views/1) · [Issue conventions](CONTRIBUTING.md#public-roadmap-and-issue-management)

## About this project

I needed a safer way to update my Android apps outside an app store. I made the updater a library so each app could keep its own design and control when installation starts. I share it so others can inspect how it works or use it in their own apps.

## Contribute and validate

Read [Contributing](CONTRIBUTING.md), [Security](SECURITY.md), [architecture](docs/ARCHITECTURE.md), [dependency review](docs/DEPENDENCIES.md), [testing evidence](docs/TESTING.md) and [release procedure](docs/RELEASING.md). AI tools assist analysis, code, tests and documentation; contribution summaries must disclose material assistance and distinguish checks actually run from pending checks. Do not share real vault data, credentials or signing material.

## License and provenance

Distributed under the GNU Affero General Public License version 3; see [LICENSE](LICENSE). Exported bundles include corresponding library source and build files.

## AI usage

I use AI tools for analysis, implementation, tests, and documentation. I decide what ships and take responsibility for maintaining it. Project-specific testing and known limitations are documented separately.
