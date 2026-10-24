# Android App Updater

Read ../../AGENTS.md and ../AGENTS.md when present. This is a standalone Android library, extracted from Raiun; it must not depend on an application module, branding, account/network client, or a sibling checkout. Package namespace is `dev.rogers.appupdater`; artifact `dev.rogers:android-updater` uses explicit versions.

Run Gradle from this directory with JDK 21. Use synthetic release/APK fixtures. Preserve strict GitHub origin, size, hash, package/version/signing checks and explicit foreground installation. Do not add secrets, signing material, automatic installation or arbitrary authenticated endpoints. WorkManager bootstrap must work after process recreation without relying on an Activity.

Keep UI and app-specific release configuration in the consuming app. Library manifest provides only the scoped nonexported APK FileProvider; network/install permissions are an explicit consuming-app choice. Do not integrate networking into Kura without addressing its offline-only policy with the owner.

Build/test and package locally. The owner authorized creating and publishing phillip9933/android-app-updater on 2026-10-24; this does not authorize future app releases or signing. Do not modify another project's live work, publish a mutable artifact under an already distributed version, or silently follow local source directories from consuming apps.
