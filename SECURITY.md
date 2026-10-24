# Security

Report suspected vulnerabilities using [GitHub private vulnerability reporting](https://github.com/phillip9933/android-app-updater/security/advisories/new). Include affected version, impact and a synthetic reproducer. Never publish tokens, real app/vault records, private backups or signing keys.

The library trusts Android package/signature enforcement and the host's configured public GitHub repository. It checks origins, redirects, byte limits, sizes, hashes when advertised, package/version/minimum API and installed signer identity before an explicit foreground installer handoff. It does not promise protection from a compromised OS, compromised release signing key or authorized malicious release. Strict signer matching does not support key rotation. Never uninstall an app to bypass a mismatch.

Tests are engineering evidence, not an independent audit. Production upgrade acceptance on physical devices remains required.

APK signer identity comes from the installed host package and Android's archive signature parsing, never the release title, asset filename or a certificate supplied by the feed. A matching display name is insufficient. Empty or different signer sets, another package, a nonincreasing version code, mismatched version names, debuggable APKs and changed cached bytes are rejected. Android's installer remains the final enforcement boundary; installation requires an explicit foreground action. A compromised host signing key can authorize malicious updates and is outside this protection.

Release notes are untrusted text. Consuming apps must render them as plain text or safely sanitize any rich rendering; never execute embedded HTML/JavaScript, commands or automatically followed links.
