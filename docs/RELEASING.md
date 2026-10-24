# Releases

Use explicit immutable versions. Once an artifact has been distributed, changed bytes require a new version. Update updater/build.gradle.kts and scripts/export_bundle.py together. Run ktlintCheck, detekt, testDebugUnitTest, lintRelease and Maven publication. Record exact results in TESTING.md.

Commit the reviewed source, tag it `vX.Y.Z`, and export a fresh bundle using scripts/export_bundle.py. Publish the complete Maven/source/license bundle and SHA256SUMS on the matching GitHub release. Tag and source bundle must describe the same code. Mark candidates prerelease while signed platform/physical-device acceptance remains pending. Inspect outgoing files; never include caches, local.properties, credentials or app APKs.

Consumers download the release bundle, validate its SHA256SUMS, and vendor the immutable Maven repository and corresponding source. Gradle must check all pins and reject unlisted Maven files. Publication does not automatically update Kura/Raiun pins. No Maven Central availability is claimed.
