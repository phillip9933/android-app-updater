package io.github.phillip9933.appupdater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AndroidUpdateFeedTest {
    private val config = UpdateConfig("phillip9933", "raiun", "android-v", "raiun-android-", setOf("0.9.3"), "raiun-")

    private fun select(
        json: String,
        channel: AndroidUpdateChannel,
    ) = AndroidUpdateFeed.select(json, channel, config)

    @Test
    fun stableIgnoresNewerDesktopAndPrereleaseTagsAndUsesNumericOrdering() {
        val result =
            select(
                releases(
                    release("android-v1.9.0", asset("raiun-android-1.9.0.apk")),
                    release("android-v1.10.0", asset("raiun-android-1.10.0.apk")),
                    release("android-v9.0.0-beta.10", asset("raiun-android-9.0.0-beta.10.apk"), prerelease = true),
                    release("desktop-v99.0.0", asset("raiun-android-99.0.0.apk")),
                ),
                AndroidUpdateChannel.STABLE,
            )

        assertEquals("android-v1.10.0", result?.tag)
    }

    @Test
    fun betaChoosesHighestSemanticVersionIncludingStableAndComparesPrereleaseNumbers() {
        val result =
            select(
                releases(
                    release("android-v2.0.0-beta.9", asset("raiun-android-2.0.0-beta.9.apk"), prerelease = true),
                    release("android-v2.0.0-beta.10", asset("raiun-android-2.0.0-beta.10.apk"), prerelease = true),
                    release("android-v1.99.0", asset("raiun-android-1.99.0.apk")),
                ),
                AndroidUpdateChannel.BETA,
            )

        assertEquals("android-v2.0.0-beta.10", result?.tag)
    }

    @Test
    fun acceptsOnlyHistoricalReleaseTagsWithLegacyAssetName() {
        val release = release("v0.9.3", asset("raiun-0.9.3.apk"))
        val result = select(releases(release), AndroidUpdateChannel.STABLE)
        assertEquals("raiun-0.9.3.apk", result?.assetName)
        assertNull(
            select(releases(release("0.9.4", asset("raiun-0.9.4.apk"))), AndroidUpdateChannel.STABLE),
        )
    }

    @Test
    fun rejectsWrongUrlCredentialsQueryAndFragment() {
        val valid = asset("raiun-android-1.2.3.apk")
        val badUrls =
            listOf(
                "https://evil.example/phillip9933/raiun/releases/download/android-v1.2.3/raiun-android-1.2.3.apk",
                "https://user@github.com/phillip9933/raiun/releases/download/android-v1.2.3/raiun-android-1.2.3.apk",
                "https://github.com/phillip9933/raiun/releases/download/android-v1.2.3/raiun-android-1.2.3.apk?token=secret",
                "https://github.com/phillip9933/raiun/releases/download/android-v1.2.3/raiun-android-1.2.3.apk#fragment",
            )
        badUrls.forEach { url ->
            assertNull(
                select(
                    releases(release("android-v1.2.3", valid.replace(URL, url))),
                    AndroidUpdateChannel.STABLE,
                ),
            )
        }
    }

    @Test
    fun rejectsDuplicateOrAmbiguousApksAndDebugUnsignedOrTestNames() {
        val tag = "android-v1.2.3"
        val expected = asset("raiun-android-1.2.3.apk")

        fun selectAssets(vararg assets: String) = select(releases(release(tag, *assets)), AndroidUpdateChannel.STABLE)

        assertNotNull(selectAssets(expected))
        assertNull(selectAssets(expected, expected))
        assertNull(selectAssets(expected, asset("other.apk")))
        for (kind in listOf("debug", "unsigned", "test")) {
            val prefix = "raiun-$kind-"
            val configured = config.copy(assetPrefix = prefix)
            val feed = releases(release(tag, asset("${prefix}1.2.3.apk")))
            assertNull(AndroidUpdateFeed.select(feed, AndroidUpdateChannel.STABLE, configured))
        }
    }

    @Test
    fun rejectsInvalidTagsAndAssetSizes() {
        val tag = "android-v1.2.3"
        val name = "raiun-android-1.2.3.apk"
        assertNotNull(select(releases(release(tag, asset(name))), AndroidUpdateChannel.STABLE))
        val malformed =
            listOf("android-v1.02.3", "android-v-1.2.3", "android-v999999999999999999999.0.0", "android-v1.2.3+build")
        malformed.forEach { invalid ->
            val version = invalid.removePrefix("android-v")
            assertNull(
                select(releases(release(invalid, asset("raiun-android-$version.apk"))), AndroidUpdateChannel.STABLE),
            )
        }
        for (size in listOf(0L, 600L * 1024 * 1024)) {
            assertNull(select(releases(release(tag, asset(name, size = size))), AndroidUpdateChannel.STABLE))
        }
    }

    @Test
    fun digestMayBeAbsentOrNullButMustOtherwiseBeASha256String() {
        fun parse(digestJson: String?) =
            select(
                releases(release("android-v1.2.3", asset("raiun-android-1.2.3.apk", digestJson = digestJson))),
                AndroidUpdateChannel.STABLE,
            )
        assertNull(requireNotNull(parse(null)).sha256)
        assertNull(requireNotNull(parse("null")).sha256)
        val hash = "AB".repeat(32)
        assertEquals(hash.lowercase(), requireNotNull(parse("\"sha256:$hash\"")).sha256)
        for (invalid in listOf("0", "true", "{}", "[]", "\"sha256:bad\"")) {
            assertNull(invalid, parse(invalid))
        }
    }

    private fun releases(vararg releases: String) = "[${releases.joinToString(",")}]"

    @Test fun rejectsJsonTypeConfusionAndInjectedTags() {
        val valid = releases(release("android-v1.2.3", asset("raiun-android-1.2.3.apk")))
        val confused =
            listOf(
                valid.replace("\"draft\":false", "\"draft\":\"false\""),
                valid.replace("\"prerelease\":false", "\"prerelease\":\"false\""),
                valid.replace("\"size\":1234", "\"size\":\"1234\""),
                valid.replace("\"tag_name\":\"android-v1.2.3\"", "\"tag_name\":123"),
            )
        confused.forEach { assertNull(select(it, AndroidUpdateChannel.STABLE)) }
        listOf("android-v1.2.3/../evil", "android-v1.2.3%2fother", "android-v1.2.3;sh", "android-v1.2.3?x=y")
            .forEach { tag ->
                assertNull(select(valid.replace("android-v1.2.3", tag), AndroidUpdateChannel.STABLE))
            }
    }

    private fun release(
        tag: String,
        vararg assets: String,
        prerelease: Boolean = false,
    ): String {
        val correctedAssets = assets.joinToString(",", "[", "]").replace("/download/android-v1.2.3/", "/download/$tag/")
        return """{
            "tag_name":"$tag", "draft":false, "prerelease":$prerelease, "body":"notes",
            "published_at":"2026-01-01T00:00:00Z", "assets":$correctedAssets
        }"""
    }

    private fun asset(
        name: String,
        size: Long = 1234,
        digestJson: String? = null,
    ): String {
        val url = "https://github.com/phillip9933/raiun/releases/download/android-v1.2.3/$name"
        val digestField = digestJson?.let { ",\"digest\":$it" }.orEmpty()
        return "{\"name\":\"$name\",\"size\":$size,\"browser_download_url\":\"$url\"$digestField}"
    }

    private companion object {
        const val URL = "https://github.com/phillip9933/raiun/releases/download/android-v1.2.3/raiun-android-1.2.3.apk"
    }
}
