package io.github.phillip9933.appupdater

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {
    private val config = UpdateConfig("phillip9933", "raiun", "android-v", "raiun-android-", setOf("0.9.3"), "raiun-")
    private val policy = UpdatePolicy(config)
    private val release =
        AndroidUpdateRelease(
            "android-v1.0.0",
            "",
            "raiun-android-1.0.0.apk",
            "https://github.com/phillip9933/raiun/releases/download/android-v1.0.0/raiun-android-1.0.0.apk",
            1024,
            null,
            null,
        )

    @Test fun `only the exact repository asset may begin a download`() {
        assertTrue(policy.validRelease(release))
        assertFalse(policy.validRelease(release.copy(downloadUrl = "https://evil.example/raiun.apk")))
        assertFalse(policy.validRelease(release.copy(downloadUrl = "${release.downloadUrl}?token=secret")))
        assertFalse(policy.validRelease(release.copy(assetName = "raiun-android-1.0.0-debug.apk")))
    }

    @Test fun `redirect may use only trusted https asset host without user info`() {
        assertTrue(
            policy.validRedirect(
                "https://release-assets.githubusercontent.com/github-production-release-asset/123".toHttpUrl(),
            ),
        )
        assertFalse(policy.validRedirect("http://release-assets.githubusercontent.com/object".toHttpUrl()))
        assertFalse(
            policy.validRedirect("https://release-assets.githubusercontent.com.evil.example/object".toHttpUrl()),
        )
        assertFalse(policy.validRedirect("https://user:pass@release-assets.githubusercontent.com/object".toHttpUrl()))
    }

    @Test fun `metadata rejects wrong package old build version sdk debug and signer changes`() {
        val original = listOf(byteArrayOf(1, 2, 3))

        fun check(
            packageName: String = "app.raiun.cloud",
            versionCode: Long = 47,
            versionName: String? = "1.0.0",
            minSdk: Int = 26,
            debug: Boolean = false,
            signers: List<ByteArray> = original,
        ) = policy.validApkMetadata(
            release,
            "app.raiun.cloud",
            UpdatePolicy.InstalledApk(46, original),
            UpdatePolicy.CandidateApk(packageName, versionCode, versionName, minSdk, debug, signers),
            26,
        )
        assertEquals(null, check())
        assertEquals(UpdatePolicy.MetadataError.WRONG_PACKAGE, check(packageName = "app.attacker.cloud"))
        assertEquals(UpdatePolicy.MetadataError.OLD_VERSION, check(versionCode = 46))
        assertEquals(UpdatePolicy.MetadataError.VERSION_MISMATCH, check(versionName = "2.0.0"))
        assertEquals(UpdatePolicy.MetadataError.INCOMPATIBLE_SDK, check(minSdk = 27))
        assertEquals(UpdatePolicy.MetadataError.DEBUG_APK, check(debug = true))
        assertEquals(UpdatePolicy.MetadataError.SIGNATURE_MISMATCH, check(signers = listOf(byteArrayOf(9, 8, 7))))
    }

    @Test fun `semantic comparison does not offer older or equal versions`() {
        assertTrue(policy.isNewer("1.0.0", "0.9.3"))
        assertTrue(policy.isNewer("1.0.0", "1.0.0-beta.1"))
        assertFalse(policy.isNewer("0.9.3", "1.0.0"))
        assertFalse(policy.isNewer("1.0.0", "1.0.0"))
        assertTrue(policy.isNewer("1.0.0-beta.10", "1.0.0-beta.2"))
    }

    @Test fun `hash mismatch rejects candidate bytes`() {
        val expected = "aa".repeat(32)
        assertTrue(policy.matchingHash(expected.uppercase(), expected))
        assertFalse(policy.matchingHash("bb".repeat(32), expected))
        assertFalse(policy.matchingHash(expected, "not-a-sha256"))
    }
}
