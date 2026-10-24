# Contributing

Start with README.md and docs/ARCHITECTURE.md. Use JDK 21 and SDK 36. Run the quality commands in README.md serially. Prefer focused changes that prioritize data safety, security, correctness and privacy, then usability, maintainability and performance. Preserve cancellation, bounded inputs, package identity and signing checks. Use synthetic data only.

Do not introduce app UI, branding, account clients or authenticated update servers into the library. Never install an APK from a background worker. New API/behavior needs meaningful regression coverage. Update canonical documentation and disclose material AI assistance, tests run, failures, reused results and remaining limits. Contributions use AGPL-3.0; retain third-party notices.
