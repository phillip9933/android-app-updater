package dev.rogers.appupdater

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateLibraryTest {
    private val raiun = UpdateConfig("phillip9933", "raiun", "android-v", "raiun-android-", setOf("0.9.3"), "raiun-")
    private val fixture = UpdateConfig("acme", "weather-app", "mobile-", "weather-android-")

    @Test fun `config validates names prefixes state and legacy versions`() {
        assertTrue(runCatching { UpdateConfig("octo-org", "repo_1", "v", "app-") }.isSuccess)
        assertFalse(runCatching { UpdateConfig("../evil", "repo", "v", "app-") }.isSuccess)
        assertFalse(runCatching { UpdateConfig("owner", "repo", "", "app-") }.isSuccess)
        assertFalse(runCatching { UpdateConfig("owner", "repo", "v", "app-", stateName = "../prefs") }.isSuccess)
        assertFalse(runCatching { UpdateConfig("owner", "repo", "v", "app-", setOf("01.2.3")) }.isSuccess)
    }

    @Test fun `config accepts only configured app tags and legacy versions`() {
        assertEquals("1.2.3", raiun.versionFromTag("android-v1.2.3"))
        assertEquals("0.9.3", raiun.versionFromTag("v0.9.3"))
        assertEquals("0.9.3", raiun.versionFromTag("0.9.3"))
        assertNull(raiun.versionFromTag("desktop-v9.0.0"))
        assertNull(raiun.versionFromTag("v0.9.4"))
        assertEquals("weather-android-2.3.4.apk", fixture.assetName("mobile-2.3.4"))
        assertNull(fixture.versionFromTag("android-v2.3.4"))
    }

    @Test fun `feed selector uses configured identity and rejects cross repo tags and assets`() {
        val valid = release("mobile-1.2.0", "weather-android-1.2.0.apk", "acme", "weather-app")
        assertEquals("mobile-1.2.0", AndroidUpdateFeed.select("[$valid]", AndroidUpdateChannel.STABLE, fixture)?.tag)
        val otherRepo = release("mobile-1.3.0", "weather-android-1.3.0.apk", "someone-else", "weather-app")
        val wrongTag = release("android-v9.0.0", "weather-android-9.0.0.apk", "acme", "weather-app")
        val wrongAsset = release("mobile-1.4.0", "raiun-android-1.4.0.apk", "acme", "weather-app")
        assertNull(AndroidUpdateFeed.select("[$otherRepo]", AndroidUpdateChannel.STABLE, fixture))
        assertNull(AndroidUpdateFeed.select("[$wrongTag]", AndroidUpdateChannel.STABLE, fixture))
        assertNull(AndroidUpdateFeed.select("[$wrongAsset]", AndroidUpdateChannel.STABLE, fixture))
    }

    @Test fun `feed semver comparison and release channel selection remain strict`() {
        val json =
            "[${release("android-v1.9.0", "raiun-android-1.9.0.apk", "phillip9933", "raiun")}," +
                "${release("android-v1.10.0", "raiun-android-1.10.0.apk", "phillip9933", "raiun")}," +
                "${release("android-v2.0.0-beta.2", "raiun-android-2.0.0-beta.2.apk", "phillip9933", "raiun", true)}]"
        assertEquals("android-v1.10.0", AndroidUpdateFeed.select(json, AndroidUpdateChannel.STABLE, raiun)?.tag)
        assertEquals("android-v2.0.0-beta.2", AndroidUpdateFeed.select(json, AndroidUpdateChannel.BETA, raiun)?.tag)
        assertTrue(AndroidUpdateFeed.isNewer("1.0.0-beta.10", "1.0.0-beta.2"))
        assertFalse(AndroidUpdateFeed.isNewer("1.0.0", "1.0.0"))
    }

    @Test fun `policy accepts only exact repository URL and trusted redirects`() {
        val release =
            AndroidUpdateRelease(
                "mobile-1.2.3",
                "",
                "weather-android-1.2.3.apk",
                "https://github.com/acme/weather-app/releases/download/mobile-1.2.3/weather-android-1.2.3.apk",
                1024,
                null,
                null,
            )
        val policy = UpdatePolicy(fixture)
        assertTrue(policy.validRelease(release))
        assertFalse(
            policy.validRelease(
                release.copy(downloadUrl = release.downloadUrl.replace("acme/weather-app", "someone-else/weather-app")),
            ),
        )
        assertFalse(policy.validRelease(release.copy(downloadUrl = release.downloadUrl + "?token=x")))
        assertFalse(policy.validRelease(release.copy(assetName = "other.apk")))
        assertTrue(policy.validRedirect("https://release-assets.githubusercontent.com/object/1".toHttpUrl()))
        assertFalse(
            policy.validRedirect("https://release-assets.githubusercontent.com.evil.example/object".toHttpUrl()),
        )
    }

    @Test fun `policy checks metadata and hashes`() {
        val release =
            AndroidUpdateRelease(
                "mobile-1.2.3",
                "",
                "weather-android-1.2.3.apk",
                "https://github.com/acme/weather-app/releases/download/mobile-1.2.3/weather-android-1.2.3.apk",
                1024,
                null,
                null,
            )
        val policy = UpdatePolicy(fixture)
        val signers = listOf(byteArrayOf(1, 2, 3))
        val installed = UpdatePolicy.InstalledApk(3, signers)
        val candidate = UpdatePolicy.CandidateApk("com.acme.weather", 4, "1.2.3", 26, false, signers)
        assertNull(policy.validApkMetadata(release, "com.acme.weather", installed, candidate, 26))
        assertEquals(
            UpdatePolicy.MetadataError.WRONG_PACKAGE,
            policy.validApkMetadata(release, "com.acme.weather", installed, candidate.copy(packageName = "evil"), 26),
        )
        assertTrue(policy.matchingHash("aa".repeat(32).uppercase(), "aa".repeat(32)))
        assertFalse(policy.matchingHash("bb".repeat(32), "aa".repeat(32)))
    }

    private fun release(
        tag: String,
        asset: String,
        owner: String,
        repo: String,
        prerelease: Boolean = false,
    ) = """{
        "tag_name":"$tag", "draft":false, "prerelease":$prerelease, "body":"notes",
        "assets":[{"name":"$asset", "size":1234,
        "browser_download_url":"https://github.com/$owner/$repo/releases/download/$tag/$asset"}]
    }"""
}
