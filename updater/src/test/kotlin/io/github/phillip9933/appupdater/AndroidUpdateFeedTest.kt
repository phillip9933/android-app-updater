package io.github.phillip9933.appupdater

import org.junit.Assert.assertEquals
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
                    release("android-v1.9.0", apk("raiun-android-1.9.0.apk")),
                    release("android-v1.10.0", apk("raiun-android-1.10.0.apk")),
                    release("android-v9.0.0-beta.10", apk("raiun-android-9.0.0-beta.10.apk"), prerelease = true),
                    release("desktop-v99.0.0", apk("raiun-android-99.0.0.apk")),
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
                    release("android-v2.0.0-beta.9", apk("raiun-android-2.0.0-beta.9.apk"), prerelease = true),
                    release("android-v2.0.0-beta.10", apk("raiun-android-2.0.0-beta.10.apk"), prerelease = true),
                    release("android-v1.99.0", apk("raiun-android-1.99.0.apk")),
                ),
                AndroidUpdateChannel.BETA,
            )

        assertEquals("android-v2.0.0-beta.10", result?.tag)
    }

    @Test
    fun acceptsOnlyHistoricalReleaseTagsWithLegacyAssetName() {
        val release = release("v0.9.3", apk("raiun-0.9.3.apk"))
        val result = select(releases(release), AndroidUpdateChannel.STABLE)
        assertEquals("raiun-0.9.3.apk", result?.assetName)
        assertNull(
            select(releases(release("0.9.4", apk("raiun-0.9.4.apk"))), AndroidUpdateChannel.STABLE),
        )
    }

    @Test
    fun rejectsWrongUrlCredentialsQueryAndFragment() {
        val valid = apk("raiun-android-1.2.3.apk")
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
        val apk = apk("raiun-android-1.2.3.apk")
        val duplicateName =
            apk
                .replace("\"assets\":[", "\"assets\":[")
                .dropLast(1) + ",${asset("raiun-android-1.2.3.apk")}]}"
        val multiple = apk.dropLast(1) + ",${asset("other.apk")}]}"
        val invalidNames =
            listOf("raiun-android-1.2.3-debug.apk", "raiun-android-1.2.3-unsigned.apk", "raiun-android-1.2.3-test.apk")
        assertNull(
            select(releases(release("android-v1.2.3", duplicateName)), AndroidUpdateChannel.STABLE),
        )
        assertNull(select(releases(release("android-v1.2.3", multiple)), AndroidUpdateChannel.STABLE))
        invalidNames.forEach { name ->
            assertNull(
                select(releases(release("android-v1.2.3", asset(name))), AndroidUpdateChannel.STABLE),
            )
        }
    }

    @Test
    fun rejectsInvalidTagsSizesAndMalformedDigest() {
        val malformed =
            listOf("android-v1.02.3", "android-v-1.2.3", "android-v999999999999999999999.0.0", "android-v1.2.3+build")
        malformed.forEach { tag ->
            val version = tag.removePrefix("android-v")
            assertNull(
                select(
                    releases(release(tag, apk("raiun-android-$version.apk"))),
                    AndroidUpdateChannel.STABLE,
                ),
            )
        }
        assertNull(
            select(
                releases(release("android-v1.2.3", asset("raiun-android-1.2.3.apk", size = 0))),
                AndroidUpdateChannel.STABLE,
            ),
        )
        assertNull(
            select(
                releases(
                    release(
                        "android-v1.2.3",
                        asset(
                            "raiun-android-1.2.3.apk",
                            size =
                                600L * 1024 * 1024,
                        ),
                    ),
                ),
                AndroidUpdateChannel.STABLE,
            ),
        )
        assertNull(
            select(
                releases(release("android-v1.2.3", asset("raiun-android-1.2.3.apk", digest = "sha256:bad"))),
                AndroidUpdateChannel.STABLE,
            ),
        )
    }

    private fun releases(vararg releases: String) = "[${releases.joinToString(",")}]"

    @Test fun rejectsJsonTypeConfusionAndInjectedTags() {
        val valid = releases(release("android-v1.2.3", apk("raiun-android-1.2.3.apk")))
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
        assets: String,
        prerelease: Boolean = false,
    ): String {
        val correctedAssets = assets.replace("/download/android-v1.2.3/", "/download/$tag/")
        return """{
            "tag_name":"$tag", "draft":false, "prerelease":$prerelease, "body":"notes",
            "published_at":"2026-01-01T00:00:00Z", "assets":$correctedAssets
        }"""
    }

    private fun apk(name: String) = "[${asset(name)}]"

    private fun asset(
        name: String,
        size: Long = 1234,
        digest: String? = null,
    ): String {
        val url = "https://github.com/phillip9933/raiun/releases/download/android-v1.2.3/$name"
        val digestJson = digest?.let { ",\"digest\":\"$it\"" }.orEmpty()
        return "{\"name\":\"$name\",\"size\":$size,\"browser_download_url\":\"$url\"$digestJson}"
    }

    private companion object {
        const val URL = "https://github.com/phillip9933/raiun/releases/download/android-v1.2.3/raiun-android-1.2.3.apk"
    }
}
