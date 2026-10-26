# Testing and acceptance

Physical acceptance completed on 26 October 2026 after the owner-approved Pixel
handoff. See [physical results and fixture limits](PHYSICAL_ACCEPTANCE.md): 13
native tests passed for published 0.2.0 and again for local 0.2.1, real installer
denial/Cancel/Update and retained synthetic data passed, and both candidate worker
migration scenarios passed. The earlier pending statements below record the state
before this physical run. Source review/publication gates remain open.

## 0.2.1 maintenance validation — 26 October 2026

Deterministic same-package/same-signer host replacement using the immutable
published 0.1.3 and 0.2.0 AARs reproduced [obsolete queued Wi-Fi work](https://github.com/phillip9933/android-app-updater/issues/8)
on API 26. A delayed old-namespace request remained active after the host upgrade
made its selected release obsolete. The periodic worker was replaced correctly.
An independent host regression failed before the correction. Local 0.2.1 cancels
the unique download request when no eligible release remains; it preserves the
user's opt-in. Published 0.2.0 artifacts remain unchanged.

The corrected candidate passed the [synthetic host upgrade fixture](UPGRADE_ACCEPTANCE.md)
on separate API 26 and API 35 emulators, with automatic checks enabled and
disabled. Each scenario retains a private sentinel and preferences across signed
`install -r`, verifies obsolete-work cancellation/periodic replacement, then
checks worker-only bootstrap after another process stop and rejects an unrelated
configuration token. Old/new host certificate digests match. The fixture used
exact published 0.1.3 bytes and an explicitly hashed local correction AAR; these
local bytes are test candidates, not release artifacts. Initial harness setup
failures and a scheduling-race false pass were preserved and excluded from the
deterministic result.

Fresh correction checks passed: **56 host tests** with zero failures/errors/skips,
ktlint, detekt, release lint, library/probe APK assembly, **nine exporter
regressions**, and **13 native APK security tests on each API 26/35**. The existing
redirect, parsing, APK identity/signer/hash and installer-URI regressions were
included; no new bypass was demonstrated. The checksum-pinned published 0.2.0 API
also compiled the host-owned popup example, without adding UI to the SDK.

Build hardening adds project/plugin classpath locks and SHA-256 verification for
549 components / 980 artifacts. A clean strict offline build passed before the
runtime correction, and the correction passed the same strict quality tasks.
Four isolated Gradle checks prove valid input acceptance, altered/unlisted
artifact rejection and dependency version-drift rejection. The baseline pins
existing resolved inputs; it is not independent upstream provenance attestation.
See [dependency review and trust boundaries](DEPENDENCIES.md).

AI assistance was used for implementation, review and tests. These results were
recorded before owner approval to push and publish. No consumer integration, production signing,
physical-device interaction or installer-consent journey was performed. The
scanner chat retains exclusive Pixel access for its current owner-requested
tests at that time; the subsequent completed physical run is linked above. Final
source, independent CI and artifact verification are recorded on the
[0.2.1 release](https://github.com/phillip9933/android-app-updater/releases/tag/v0.2.1).

On 2026-10-24 the prepublication 0.1.1 source recorded 43 passing host tests, ktlint, detekt and release lint. Version 0.1.2 changes publication/provenance documentation and version metadata; fresh checks are recorded below after execution. CI runs the same checks and publishes reports and unsigned Maven artifacts.

Before calling this stable, run signed host installation/upgrade journeys on API 26, API 35 and physical hardware, including process recreation, install-permission denial, cancellation, wrong signer/package, offline failures and retained app data. Host tests and static checks do not prove Android installer acceptance. No private signing key is part of this library or its tests.

## Public 0.1.2 candidate — 2026-10-24

The local Linux/JDK 21 quality/publication build passed in 20 seconds: 79 tasks, 15 executed and 64 up-to-date. Existing host XML reports contain 43 tests, zero failures/errors/skips; test execution was reused, not presented as 43 new executions. ktlint/detekt and release lint gates passed (zero errors, 11 warnings and one hint); exact task reuse remains in the build log. No production signing, device upgrade or physical-device check was performed. AI assistance was used for extraction, implementation, tests, documentation and publication preparation.

## 0.1.3 native archive verification fix

Raiun acceptance found that Android 15 may return archive ApplicationInfo.flags=0 despite a compiled debuggable=true manifest. The patch now parses the APK binary manifest using public Android resource APIs, resolves boolean references, rejects debug APKs and fails closed on missing/unreadable manifest metadata. API 26's repeated initial START_DOCUMENT behavior is handled.

The contributing task recorded 43 passing host tests and 10/10 native probe tests on each of API 26 and API 35 for the scratch patch. These are reviewed external-task results, not new authoritative-artifact executions. Scratch 0.1.2 bytes were never redistributed as an updated 0.1.2. Authoritative 0.1.3 results are recorded after execution below.

The repository now includes `verifier-test-app`: a generic, non-network test host with generated manifest-only candidate APKs. `scripts/generate_updater_test_apks.py` uses only Gradle's default disposable debug keystore and a temporary wrong signer; no production/private project signing inputs are used or included. Assets are generated under build/, not committed. Run `:verifier-test-app:connectedDebugAndroidTest` on a disposable API 26/API 35 emulator to cover valid updates, signer/package/version mismatch, literal and resource-backed debuggable flags, missing application, changed hashes and truncation. This checks verifier behavior, not final Android installer handoff or real app retained-data upgrades.

Authoritative 0.1.3 local checks passed on 2026-10-24: 43 freshly executed host tests, zero failures/errors/skips; ktlint and detekt passed; release lint had zero errors, 11 warnings and one hint. Library/Maven publication and the generated verifier test APKs passed in 28 seconds (145 tasks, 93 executed / 52 up-to-date). The source-linked generic native verifier passed **10/10 on API 26** and **10/10 on API 35**, including both resource-backed boolean cases and missing application. Raw results are preserved in project outputs/release-0.1.3. These are verifier regressions, not app-level installer or physical-device acceptance. AI assistance was used for diagnosis, patch review/integration, fixtures, validation and release work.

The first added native CI run (40051864268) was cancelled before tests: the hosted runner's AVD manager and emulator used different default AVD locations, so the emulator exited with Unknown AVD name while adb waited. A workflow-only follow-up sets explicit AVD/user directories, validates the generated ini/listing, and bounds adb startup wait with diagnostic output. This does not alter the immutable 0.1.3 library/source release assets or verifier code. The earlier independent quality run passed and produced a byte-identical AAR.

GitHub rejected the first workflow-location follow-up before jobs started because runner.temp is unavailable in job-level env evaluation. The runtime directories are now set inside the preparation shell step. Both CI-only attempts remain visible; neither is counted as passing native coverage.

Final independent native CI [40052660831](https://github.com/phillip9933/android-app-updater/actions/runs/40052660831) passed **10/10 on API 26** and **10/10 on API 35**, with zero failures/errors/skips in both downloaded XML reports. Quality CI [40052660786](https://github.com/phillip9933/android-app-updater/actions/runs/40052660786) also passed after the workflow-only corrections. The original quality run's downloaded AAR is byte-identical to the published 0.1.3 AAR (SHA-256 `69cb93078aed03a03257b7c3837a580028daec06a3d97faaf5bab6bc88c40e26`). Tagged source `6c5dea178527c218204a877b449e5477c13b08af` and release assets remain immutable; subsequent commits changed CI setup and documentation only.

Raiun's independent task additionally attested the exact published AAR in app-level verifier/provider tests (**9/9 on each API 26/35**) and synthetic Android installer upgrades retaining a private sentinel on both APIs. This is separate app-task evidence, not production signing, Kura upgrade or physical-device acceptance.

## 0.2.0 neutral namespace candidate — 24 October 2026

After the package/Maven-group rename, local ktlint, detekt, host tests (43 passing), release lint, Maven publication and debug probe/instrumentation APK assembly passed. Subsequent independent quality CI 40054328620 and native CI 40054328585 passed on 405ea9e, including native API 26/35. Those runs predate the additional security hardening below. Version 0.2.0 remains a prerelease. Published versions and their history are preserved.

## 0.2.0 security review and hardening — 24 October 2026

Manual review covered feed/configuration parsing, HTTPS origin and redirect checks, streamed size/hash enforcement, APK package/version/debug/signer checks, cached-file revalidation, FileProvider/installer grants, persisted worker registration, SDK export and CI/dependency inputs. Two reproduced SDK export weaknesses were fixed: untracked/dirty source inclusion, and packaging artifacts unrelated to the exported source revision. Export now rejects dirty/untracked work, snapshots committed regular files and builds fresh artifacts in that isolated snapshot without build-cache reuse. Nine synthetic exporter regressions pass, including failed builds, source changes during builds, invalid publication identity, symlinks and stale artifacts. The wrapper JAR now matches the official Gradle 8.13 SHA-256; the previous JAR matched official Gradle 7.x bytes.

Fresh local JDK 21 checks passed: **55 host tests, zero failures/errors/skips**, ktlint, detekt, release lint (zero errors, 11 warnings and one hint), Maven publication and probe APK assembly (145 executed tasks). Twelve additional host regressions cover hostile origins/paths, CDN escapes, credentials/ports/schemes, redirect loops/missing Location, substituted bodies, malformed JSON field types, semantic versions and signer sets. Redirect tests inject synthetic HTTP responses into the actual downloader; they do not make outbound network requests. The library carries no release-note renderer; consuming apps must treat notes as untrusted text.

The expanded native suite passed **13/13 on API 26 and 13/13 on API 35**, with zero failures/errors/skips, on new disposable emulators. It rejects wrong-signing-key and unsigned APKs, and a signed APK whose payload was changed after signing even when the download hash is recomputed. A valid same-signer update produces a read-only scoped installer URI; changing the cached bytes then prevents another handoff. Existing wrong-package/version/debug/resource-backed manifest and truncation tests remain. Fixtures use only disposable default debug signing; no production keys or live app data were used. These tests prove neither production-signed host upgrades nor physical-device acceptance.

An end-to-end exporter check built the reviewed code in a temporary clean Git fixture and verified all seven generated pins and source exclusions. Its synthetic commit and bundle are validation fixtures, not a release revision or consumer pin. OSV returned no known matches for 49 Maven coordinates from the resolved runtime dependency report; this is database coverage, not a vulnerability-free guarantee. Reports, probes, XML results and hashes are preserved in the project outputs/security-review-0.2.0-2026-10-24 directory. These checks were completed before publication; GitHub Actions reports on the release revision provide independent CI evidence. No consumer integration or production signing was performed.
