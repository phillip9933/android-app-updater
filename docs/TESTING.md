# Testing and acceptance

On 2026-10-24 the prepublication 0.1.1 source recorded 43 passing host tests, ktlint, detekt and release lint. Version 0.1.2 changes publication/provenance documentation and version metadata; fresh checks are recorded below after execution. CI runs the same checks and publishes reports and unsigned Maven artifacts.

Before calling this stable, run signed host installation/upgrade journeys on API 26, API 35 and physical hardware, including process recreation, install-permission denial, cancellation, wrong signer/package, offline failures and retained app data. Host tests and static checks do not prove Android installer acceptance. No private signing key is part of this library or its tests.

## Public 0.1.2 candidate — 2026-10-24

The local Linux/JDK 21 quality/publication build passed in 20 seconds: 79 tasks, 15 executed and 64 up-to-date. Existing host XML reports contain 43 tests, zero failures/errors/skips; test execution was reused, not presented as 43 new executions. ktlint/detekt and release lint gates passed (zero errors, 11 warnings and one hint); exact task reuse remains in the build log. No production signing, device upgrade or physical-device check was performed. AI assistance was used for extraction, implementation, tests, documentation and publication preparation.
