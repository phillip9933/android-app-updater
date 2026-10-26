# Dependency changes

The build locks the resolved project configurations and root plugin classpath.
`buildscript-gradle.lockfile`, each module's `gradle.lockfile`, and
`gradle/verification-metadata.xml` belong in source control. Gradle checks SHA-256
for exact artifacts and their metadata, including build plugins. No group-wide
trust exceptions are configured. These checks pin reviewed bytes; they do not
prove that an upstream dependency is safe or identify its author cryptographically.

Normal builds use strict verification by default. Never add `--dependency-verification off`
or `lenient` to CI or the exporter. The wrapper distribution remains separately
SHA-256 pinned. Android SDK packages, the JDK, GitHub Actions and Robolectric's
downloaded Android runtimes have their own distribution mechanisms; Gradle Maven
verification does not cover those external downloads.

## Review an intentional dependency change

1. Inspect the upstream release, provenance and security advisories, including
   transitive changes. Record the exact intended coordinate/version and reason.
2. Change the declaration in a branch. Use a trusted repository and toolchain;
   do not bootstrap checksums from files reported as unexpectedly changed.
3. Regenerate locks and checksums with JDK 21:

   ```sh
   ./gradlew --no-daemon --refresh-dependencies --write-locks --write-verification-metadata sha256 \
     :updater:dependencies :verifier-test-app:dependencies \
     ktlintCheck detekt :updater:testDebugUnitTest :updater:lintRelease \
     :updater:assembleRelease :verifier-test-app:assembleDebug \
     :verifier-test-app:assembleDebugAndroidTest
   ```

4. Review every new/removed coordinate, selected version and artifact hash.
   Generation accepts the bytes it sees; it is not independent verification.
   Compare publisher checksums/signatures through an independent trusted path
   where available, investigate unexplained changes, and keep that evidence.
   Do not accept a second checksum for unexpectedly modified existing artifacts.
5. Build from clean outputs with strict verification and no write flags. Repeat
   resolution with `--refresh-dependencies` and verify independent CI: a warm
   resolution cache can hide parent POMs or BOM/module metadata that a new build
   needs. Review and pin those exact metadata files rather than adding trust
   exceptions or disabling verification. Run
   `python3 scripts/check_dependency_verification.py` to exercise rejection of
   altered/unlisted artifacts and version drift in a disposable local Maven repo.
   Run the normal quality and native suites appropriate to the change. CI only
   consumes the reviewed baseline; it never regenerates it.

The initial checksum baseline was generated from the existing resolved build
inputs, not from a new independent audit of every upstream publisher. Initial
locks introduce no intentional dependency upgrades. Retain this limitation in
reviews rather than describing checksum generation as supply-chain attestation.
