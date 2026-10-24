# Architecture

The standalone `updater` Android library contains public GitHub release configuration/parsing, update policy, bounded transport/download, package verification and WorkManager scheduling. `UpdateManager` owns a public `StateFlow`, private update preferences and cached APKs. A persisted configuration supports worker recreation; configuration tokens reject stale work.

Consumers own branding/UI, network/install permissions, opt-in policy and installer launching from a foreground action. No vault, account token or app module is accepted by this API. The nonexported FileProvider exposes only the update-cache directory. Initialize once from the host Application; do not rely on Activity lifetime. Downloads and checks are separate from installation.

Only public universal APK releases are supported. Exact installed signer sets, package IDs, increasing version codes and matching semantic version names are required. Minimum API is 26. No Play split APK, silent installation, private repository or signing-key rotation support is claimed.
