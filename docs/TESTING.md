# Testing and acceptance

On 2026-10-24 the prepublication 0.1.1 source recorded 43 passing host tests, ktlint, detekt and release lint. Version 0.1.2 changes publication/provenance documentation and version metadata; fresh checks are recorded below after execution. CI runs the same checks and publishes reports and unsigned Maven artifacts.

Before calling this stable, run signed host installation/upgrade journeys on API 26, API 35 and physical hardware, including process recreation, install-permission denial, cancellation, wrong signer/package, offline failures and retained app data. Host tests and static checks do not prove Android installer acceptance. No private signing key is part of this library or its tests.

## Public 0.1.2 candidate — 2026-10-24

The local Linux/JDK 21 quality/publication build passed in 20 seconds: 79 tasks, 15 executed and 64 up-to-date. Existing host XML reports contain 43 tests, zero failures/errors/skips; test execution was reused, not presented as 43 new executions. ktlint/detekt and release lint gates passed (zero errors, 11 warnings and one hint); exact task reuse remains in the build log. No production signing, device upgrade or physical-device check was performed. AI assistance was used for extraction, implementation, tests, documentation and publication preparation.

## 0.1.3 native archive verification fix

Raiun acceptance found that Android 15 may return archive ApplicationInfo.flags=0 despite a compiled debuggable=true manifest. The patch now parses the APK binary manifest using public Android resource APIs, resolves boolean references, rejects debug APKs and fails closed on missing/unreadable manifest metadata. API 26's repeated initial START_DOCUMENT behavior is handled.

The contributing task recorded 43 passing host tests and 10/10 native probe tests on each of API 26 and API 35 for the scratch patch. These are reviewed external-task results, not new authoritative-artifact executions. Scratch 0.1.2 bytes were never redistributed as an updated 0.1.2. Authoritative 0.1.3 results are recorded after execution below.

The repository now includes `verifier-test-app`: a generic, non-network test host with generated manifest-only candidate APKs. `scripts/generate_updater_test_apks.py` uses only Gradle's default disposable debug keystore and a temporary wrong signer; no production/private project signing inputs are used or included. Assets are generated under build/, not committed. Run `:verifier-test-app:connectedDebugAndroidTest` on a disposable API 26/API 35 emulator to cover valid updates, signer/package/version mismatch, literal and resource-backed debuggable flags, missing application, changed hashes and truncation. This checks verifier behavior, not final Android installer handoff or real app retained-data upgrades.

Authoritative 0.1.3 local checks passed on 2026-10-24: 43 freshly executed host tests, zero failures/errors/skips; ktlint and detekt passed; release lint had zero errors, 11 warnings and one hint. Library/Maven publication and the generated verifier test APKs passed in 28 seconds (145 tasks, 93 executed / 52 up-to-date). The source-linked generic native verifier passed **10/10 on API 26** and **10/10 on API 35**, including both resource-backed boolean cases and missing application. Raw results are preserved in project outputs/release-0.1.3. These are verifier regressions, not app-level installer or physical-device acceptance. AI assistance was used for diagnosis, patch review/integration, fixtures, validation and release work.
