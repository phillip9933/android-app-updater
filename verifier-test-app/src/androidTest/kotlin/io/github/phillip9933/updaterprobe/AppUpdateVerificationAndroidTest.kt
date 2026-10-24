package io.github.phillip9933.updaterprobe

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import io.github.phillip9933.appupdater.AndroidUpdateRelease
import io.github.phillip9933.appupdater.UpdateConfig
import io.github.phillip9933.appupdater.UpdateError
import io.github.phillip9933.appupdater.UpdatePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/** Exercises the library verifier against PackageManager's real APK archive and signature parsing. */
class AppUpdateVerificationAndroidTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun higherVersionWithInstalledSignerIsAccepted() {
        withFixture("valid-higher.apk") { file, release ->
            assertNull(verify(file, release, requireNotNull(release.sha256)))
        }
    }

    @Test fun differentSignerIsRejected() {
        assertFixtureError("wrong-signer.apk", UpdateError.SIGNATURE_MISMATCH)
    }

    @Test fun differentPackageIsRejected() {
        assertFixtureError("wrong-package.apk", UpdateError.WRONG_PACKAGE)
    }

    @Test fun nonIncreasingVersionIsRejected() {
        assertFixtureError("old-version.apk", UpdateError.OLD_VERSION)
    }

    @Test fun resourceReferencedDebuggableTrueIsRejected() {
        assertFixtureError("resource-true.apk", UpdateError.DEBUG_APK)
    }

    @Test fun resourceReferencedDebuggableFalseIsAccepted() {
        withFixture("resource-false.apk") { file, release ->
            assertNull(verify(file, release, requireNotNull(release.sha256)))
        }
    }

    @Test fun missingApplicationFailsClosed() {
        assertFixtureError("missing-application.apk", UpdateError.INVALID_APK)
    }

    @Test fun debuggableArchiveIsRejected() {
        assertFixtureError("debuggable.apk", UpdateError.DEBUG_APK)
    }

    @Test fun alteredBytesAndWrongExpectedHashAreRejectedBeforePackageParsing() {
        withFixture("valid-higher.apk") { file, release ->
            assertEquals(UpdateError.HASH_MISMATCH, verify(file, release, "0".repeat(64)))
            RandomAccessFile(file, "rw").use { archive ->
                val firstByte = archive.readUnsignedByte()
                archive.seek(0)
                archive.writeByte(firstByte xor 0x01)
            }
            assertEquals(UpdateError.HASH_MISMATCH, verify(file, release, requireNotNull(release.sha256)))
        }
    }

    @Test fun truncatedArchiveIsRejectedBeforePackageParsing() {
        withFixture("valid-higher.apk") { file, release ->
            file.outputStream().use { it.write(0) }
            assertEquals(UpdateError.SIZE_MISMATCH, verify(file, release, requireNotNull(release.sha256)))
        }
    }

    private fun assertFixtureError(
        name: String,
        expected: UpdateError,
    ) {
        withFixture(name) { file, release ->
            assertEquals(expected, verify(file, release, requireNotNull(release.sha256)))
        }
    }

    private fun withFixture(
        name: String,
        check: (File, AndroidUpdateRelease) -> Unit,
    ) {
        val file = File.createTempFile("native-update-verification-", ".apk", context.cacheDir)
        try {
            InstrumentationRegistry.getInstrumentation().context.assets.open("updater/$name").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            val release =
                AndroidUpdateRelease(
                    tag = "v99.0.0",
                    notes = "Synthetic native verifier test",
                    assetName = "probe-99.0.0.apk",
                    downloadUrl =
                        "https://github.com/octocat/updater-test-fixtures/releases/download/" +
                            "v99.0.0/probe-99.0.0.apk",
                    sizeBytes = file.length(),
                    sha256 = sha256(file),
                    publishedAt = null,
                )
            check(file, release)
        } finally {
            file.delete()
        }
    }

    private fun verify(
        file: File,
        release: AndroidUpdateRelease,
        expectedHash: String,
    ): UpdateError? {
        val config =
            UpdateConfig(
                owner = "octocat",
                repository = "updater-test-fixtures",
                tagPrefix = "v",
                assetPrefix = "probe-",
            )
        val verifierClass = Class.forName("io.github.phillip9933.appupdater.UpdateApkVerifier")
        val verifier =
            verifierClass
                .getDeclaredConstructor(Context::class.java, UpdatePolicy::class.java)
                .apply { isAccessible = true }
                .newInstance(context, UpdatePolicy(config))
        val method =
            verifierClass.declaredMethods.single {
                it.name.startsWith("verify") &&
                    it.parameterTypes.contentEquals(
                        arrayOf(File::class.java, AndroidUpdateRelease::class.java, String::class.java),
                    )
            }
        return method.apply { isAccessible = true }.invoke(verifier, file, release, expectedHash) as UpdateError?
    }

    private fun sha256(file: File): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }

}
