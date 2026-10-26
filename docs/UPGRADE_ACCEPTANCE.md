# Synthetic host upgrade checks

The fixture in `scripts/upgrade_acceptance/` builds two versions of one synthetic
host. It uses the exact published 0.1.3 and 0.2.0 AAR hashes by default, without
linking a live consuming app or a sibling checkout. A local correction can be
tested only through an explicit candidate AAR path and SHA-256 argument. Generated
projects and test signing outputs stay under `build/`; never distribute those
scratch candidates as a published SDK or consumer pin.

The host package is `io.github.phillip9933.updateracceptance.migration`. Its
Application initializes the updater before normal work and can disable that
initialization to test worker-only bootstrap after process recreation. Both APKs
use Gradle's disposable default debug signer. No production signing inputs are
accepted. The host declares neither INTERNET nor REQUEST_INSTALL_PACKAGES and
does not launch an installer. WorkManager can add normal permissions through
manifest merging. This checks package replacement and persisted work, not Android's
foreground installation-consent journey.

## Build and run

With JDK 21 and the Android SDK configured, supply verified AAR files:

```sh
python3 scripts/prepare_upgrade_acceptance.py \
  --legacy-aar /absolute/path/to/published-0.1.3.aar \
  --current-aar /absolute/path/to/published-0.2.0.aar
./gradlew -p build/upgrade-acceptance --no-daemon --offline \
  --dependency-verification strict \
  :app:assembleLegacyDebug :app:assembleLegacyDebugAndroidTest \
  :app:assembleCurrentDebug :app:assembleCurrentDebugAndroidTest
python3 scripts/run_upgrade_acceptance.py \
  --adb "$ANDROID_HOME/platform-tools/adb" --serial emulator-5574 \
  --output /absolute/path/to/new-evidence-directory
```

For a local correction, also pass `--candidate-sha256 <reviewed hash>` and a fresh
`--destination build/upgrade-acceptance-fixed` during preparation, then pass that
project path to Gradle and `--project` to the runner. Candidate input hashes are
recorded in `SDK_INPUTS.json` and copied into the run evidence. Never regenerate
checksum metadata automatically to make a failing verification build pass.

The runner accepts only an emulator whose AVD name starts `updater-acceptance-`.
Create a fresh, dedicated disposable AVD with that name and an unused explicit
port. It refuses a physical serial or an already installed synthetic host. Do not
reuse another project's AVD, restart the shared adb server, or use an unqualified
adb command when devices are shared. The runner removes only its own synthetic
packages between scenarios, never between the old and new install in a scenario.
Failed runs retain the host and evidence for investigation; preserve those results
before manually removing those synthetic packages or using a fresh AVD.

## What is checked

For automatic checks enabled and disabled, seed a private sentinel and updater
preferences. Persist a valid old-class Wi-Fi download with a 24-hour initial delay
after Application scheduling completes, then stop the host process and replace
the host APK with `adb install -r`. The current host's version makes that selected
release obsolete. Assert private data/dismissal/Wi-Fi preferences survive, enabled
periodic work uses the new namespace, disabled checks stay disabled, and obsolete
old download work is cancelled. Toggle Wi-Fi download/cancel without changing
the user's opt-in or granting installation permission. A further process stop
tests worker-only bootstrap, rejection of an unrelated configuration token and
refusal to prepare an installer when no verified candidate exists.

Tests inspect the synthetic host's WorkManager database and internal bootstrap
methods for evidence; these are test probes, not recommended consumer APIs.
Holding the pending request after initialization is essential: otherwise an old
request can run or be replaced before package replacement, producing a false pass.

The deterministic fixture reproduces obsolete download work on published 0.2.0.
The local 0.2.1 correction cancels that request when no eligible download remains.
Preserve the failing original-artifact result separately from correction results.
Physical-device consent, denial, installer cancellation and accessibility remain
separate acceptance work. Passing this fixture does not prove those behaviors.
