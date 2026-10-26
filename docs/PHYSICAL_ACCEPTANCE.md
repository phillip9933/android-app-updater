# Physical acceptance — 26 October 2026

The owner authorized a connected Pixel 10 Pro and coordinated an exclusive handoff
with the document scanner chat. It ran Android 17 / API 37, security patch
2026-10-05, with 4096-byte pages. No serial, accounts, real documents or production
signing material are included in the evidence.

## Results

- **Published 0.2.0:** all 13 native APK security tests passed using the immutable
  AAR SHA-256 `ede8c4b3660654918b15004b17d0633bc8c7e1989c1548786671b98be9ea4854`,
  from release revision `90367917ac451bb1b065d4a49044df23ed3138a6`. Cases include
  wrong package/signer/version, debug and resource-backed debug flags, unsigned
  and forged APKs, size/hash changes and read-only installer URI revalidation.
- **Real Android installation:** an offline synthetic host passed permission
  denial, state restoration after process stop and retry. An exact-widget Android
  instrumentation driver activated Cancel and verified version 1.0.1 stayed
  installed. After another process recreation, a fresh explicit Update activated
  Android's confirmation and installed same-package/same-signer version 1.0.2.
  A private file and preference marker survived. The upgraded fixture deliberately
  cannot recreate missing markers, so missing data would fail the check.
- **Local 0.2.1:** all 13 native security cases passed again. Signed synthetic
  0.1.3 → local 0.2.1 host replacements passed with automatic checks on and off,
  including retained private data/preferences, old queued-work cleanup and
  worker-only process recreation with unrelated configuration-token rejection.

The owner explicitly approved temporary “Allow from this source” permission for
`io.github.phillip9933.updateracceptance.install20261026` after an automatic
approval review required that specific authorization. Permission was disabled and
verified denied afterwards. Only the five session-created synthetic packages and
the session's temporary UI dump were removed. Existing apps/data, scanner data,
lock settings and the shared adb server were preserved. Device access was
explicitly returned to the scanner chat.

## Fixture and evidence boundaries

`scripts/prepare_physical_acceptance.py` stages a separate generated project under
`build/`, verifies the exact published AAR, and builds an offline host with a
non-debuggable upgrade using disposable default Android test signing. The host
declares REQUEST_INSTALL_PACKAGES but no INTERNET permission. It seeds a synthetic
private cached APK; the real SDK's `prepareInstall()` reverifies its bytes,
package/version and installed signer before returning an Intent. This does not
test a live GitHub feed or an on-device HTTP download.

The platform Activity is test-fixture UI, not a library UI module. The opt-in
installer driver under `scripts/physical_acceptance/driver/` is compiled only with
`-PincludeInstallerUiDriver=true`; explicitly select its class and pass
`-e installerAction Cancel` or `Update`. It checks the Android installer package,
synthetic app label, button resource ID and exact text before using an
accessibility widget action. The ordinary native suite remains 13 tests.

`pixel_ui.py` is a narrow interactive test-session helper. Its package guard refuses
unrelated foreground app content; it requires an owner-approved physical serial
stored only in ignored `build/pixel-serial.txt`. Do not run it on an unapproved
device or automatically enable installation permission. Obtain an exclusive
handoff, preserve existing packages and restore any explicitly approved temporary
permission before cleanup and release.

Initial fixture build failures and an inconclusive coordinate-based Cancel trial
are preserved and excluded from the exact-widget result. The first upgrade showed
retained markers, but the later fixture strengthened the check by preventing
marker recreation in the upgraded APK. Acceptance relies on that stronger run.
No security warning was bypassed or Play Protect setting changed.

Raw command/build reports, validated UI XML, exact test APK hashes, disposable
signer digests, migration input hashes and cleanup confirmation are preserved in
the maintainer's `outputs/physical-acceptance-2026-10-26/`. AI assistance was used.
No SDK was pushed/published or promoted to Stable, no consumer was repinned and no
production-signed app upgrade was attempted. These local correction bytes are
test candidates, not consumer pins; owner source review and independent CI on the
eventual reviewed revision remain separate requirements.
