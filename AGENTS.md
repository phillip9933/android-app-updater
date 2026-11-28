# Android App Updater

## Central guidance for local agents

Use the owner-configured absolute `APP_GUIDELINES_ROOT` when the trusted launch
environment supplies it. Otherwise, on the owner's laptop use
`/home/phil/Projects/App_Guidelines`. On another executor, use the confirmed,
accessible location of the same master.

Before changing the repository or running its code, read
`<resolved root>/agent-guidelines.md` and the guides that apply. If the root or
a required file is missing, denied, or inconsistent, report the exact path and
stop changing or executing repository code. Do not use a cached guide or
silently fall back from an explicit override. Record the revision you read in
private task evidence. The master is not a dependency of the product or CI.

## Project context

- Public Kotlin Android library, min API 26. Apply the library, Android and
  updater/distribution profiles. The product has no GUI; host apps and the
  system Package Installer own all user-facing UI. The native verifier is a
  test fixture, not a distributed app.
- See [architecture](docs/ARCHITECTURE.md), [tests](docs/TESTING.md),
  [dependency verification](docs/DEPENDENCIES.md) and [releases](docs/RELEASING.md).
  The README records the normal Gradle checks. Keep package signing and device
  acceptance separate from documentation changes.

Read ../../AGENTS.md and ../AGENTS.md when present. This is a standalone Android library, extracted from Raiun; it must not depend on an application module, branding, account/network client, or a sibling checkout. Package namespace is `io.github.phillip9933.appupdater`; artifact `io.github.phillip9933:android-updater` uses explicit versions.

Run Gradle from this directory with JDK 21. Use synthetic release/APK fixtures. Preserve strict GitHub origin, size, hash, package/version/signing checks and explicit foreground installation. Do not add secrets, signing material, automatic installation or arbitrary authenticated endpoints. WorkManager bootstrap must work after process recreation without relying on an Activity.

Keep UI and app-specific release configuration in the consuming app. Library manifest provides only the scoped nonexported APK FileProvider; network/install permissions are an explicit consuming-app choice. Do not integrate networking into Kura without addressing its offline-only policy with the owner.

Build/test and package locally. The owner authorized creating and publishing phillip9933/android-app-updater on 2026-10-24; this does not authorize future app releases or signing. Do not modify another project's live work, publish a mutable artifact under an already distributed version, or silently follow local source directories from consuming apps.
