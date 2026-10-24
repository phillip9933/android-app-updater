# 0.2.0 — unreleased

- Replace the personal-domain package and Maven group with `io.github.phillip9933`.
- Remove application-specific references from the README and use GitHub private vulnerability reporting.
- Breaking change: update imports and dependency coordinates. Updater bootstrap registration uses a new preference namespace; call initialize after upgrading. Previously published versions and Git history are preserved.

# Changelog

## 0.1.3 — 2026-10-24

Security fix: read debuggable status from the APK binary manifest as well as PackageManager flags. Android 15 may return zero archive flags, which allowed a same-signer debug APK through the previous check. Resolve boolean resources and fail closed on unreadable or invalid application metadata. Add a portable native verifier test app with generated, disposable debug-signed fixtures. Version 0.1.2 is superseded; adopt 0.1.3 before app-release acceptance.


## 0.1.2 — 2026-10-24

First public SDK prerelease of the standalone updater extracted from Raiun. Includes explicit app feed configuration, strict GitHub origin/download verification, installed-app identity checks, opt-in host policy controls, background check/download support and foreground installer handoff. Adds independent project guidance, security/contribution/release documentation and quality CI. Signed API 26/API 35/physical-device upgrade acceptance remains pending.

0.1.0 and 0.1.1 were local development bundles; their existing consumers and bytes are preserved.
