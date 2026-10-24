package io.github.phillip9933.appupdater

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class AndroidUpdateChannel { STABLE, BETA }

data class AndroidUpdateRelease(
    val tag: String,
    val notes: String,
    val assetName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val sha256: String?,
    val publishedAt: String?,
)

/** Reads only the configured public GitHub release feed. APK downloads are handled separately. */
class AndroidUpdateFeed(
    private val config: UpdateConfig,
    client: OkHttpClient = OkHttpClient(),
) {
    private val client =
        client
            .newBuilder()
            .apply {
                interceptors().clear()
                networkInterceptors().clear()
            }.cookieJar(CookieJar.NO_COOKIES)
            .authenticator(okhttp3.Authenticator.NONE)
            .proxyAuthenticator(okhttp3.Authenticator.NONE)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()

    fun latest(channel: AndroidUpdateChannel): AndroidUpdateRelease? = select(fetchReleases(), channel, config)

    private fun fetchReleases(): JsonArray {
        val url = "https://api.github.com/repos/${config.owner}/${config.repository}/releases?per_page=100".toHttpUrl()
        val request =
            Request
                .Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .get()
                .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) feedFailure("GitHub release feed returned HTTP ${response.code}")
            val body = response.body ?: feedFailure("GitHub release feed response had no body")
            val source = body.source()
            source.request(MAX_FEED_BYTES + 1)
            if (source.buffer.size > MAX_FEED_BYTES) feedFailure("GitHub release feed exceeded the size limit")
            val root =
                try {
                    json.parseToJsonElement(source.buffer.readUtf8())
                } catch (failure: SerializationException) {
                    feedFailure("GitHub release feed contained invalid JSON", failure)
                }
            root as? JsonArray ?: feedFailure("GitHub release feed was not a JSON array")
        }
    }

    companion object {
        private const val MAX_FEED_BYTES = 2L * 1024 * 1024
        private const val MAX_APK_BYTES = 512L * 1024 * 1024
        private val json = Json { ignoreUnknownKeys = true }
        private val shaPattern = Regex("sha256:([0-9a-fA-F]{64})")

        private fun feedFailure(
            message: String,
            cause: Throwable? = null,
        ): Nothing = throw IOException(message, cause)

        fun select(
            jsonText: String,
            channel: AndroidUpdateChannel,
            config: UpdateConfig,
        ): AndroidUpdateRelease? {
            val releases =
                try {
                    json.parseToJsonElement(jsonText) as? JsonArray
                } catch (_: SerializationException) {
                    null
                }
            return releases?.let { select(it, channel, config) }
        }

        fun versionFromTag(
            tag: String,
            config: UpdateConfig,
        ): String? = config.versionFromTag(tag)

        // Invalid versions must be rejected before semantic comparison.
        @Suppress("ReturnCount")
        fun isNewer(
            candidate: String,
            installed: String,
        ): Boolean {
            val candidateVersion = UpdateConfig.parseSemVer(candidate) ?: return false
            val installedVersion = UpdateConfig.parseSemVer(installed) ?: return false
            return UpdateConfig.compare(candidateVersion, installedVersion) > 0
        }

        private fun select(
            releases: JsonArray,
            channel: AndroidUpdateChannel,
            config: UpdateConfig,
        ): AndroidUpdateRelease? =
            releases
                .mapNotNull { it as? JsonObject }
                .mapNotNull { parseRelease(it, channel, config) }
                .maxWithOrNull { a, b -> UpdateConfig.compare(a.version, b.version) }
                ?.release

        private data class Candidate(
            val release: AndroidUpdateRelease,
            val version: SemanticVersion,
        )

        private data class ReleaseIdentity(
            val tag: String,
            val version: SemanticVersion,
        )

        private data class ReleaseAsset(
            val name: String,
            val url: HttpUrl,
            val size: Long,
            val digest: String?,
        )

        // Keep invalid identity and asset records out of the release candidate set.
        @Suppress("ReturnCount")
        private fun parseRelease(
            obj: JsonObject,
            channel: AndroidUpdateChannel,
            config: UpdateConfig,
        ): Candidate? {
            val identity = releaseIdentity(obj, channel, config) ?: return null
            val asset = releaseAsset(obj, identity.tag, config) ?: return null
            val release =
                AndroidUpdateRelease(
                    identity.tag,
                    obj.string("body").orEmpty().take(12_000),
                    asset.name,
                    asset.url.toString(),
                    asset.size,
                    asset.digest,
                    obj.string("published_at"),
                )
            return Candidate(release, identity.version)
        }

        // Reject malformed tags and channel metadata before inspecting assets.
        @Suppress("ReturnCount")
        private fun releaseIdentity(
            obj: JsonObject,
            channel: AndroidUpdateChannel,
            config: UpdateConfig,
        ): ReleaseIdentity? {
            if (obj.boolean("draft") != false) return null
            val tag = obj.string("tag_name") ?: return null
            val versionText = config.versionFromTag(tag) ?: return null
            val version = UpdateConfig.parseSemVer(versionText) ?: return null
            val isPrerelease = obj.boolean("prerelease")
            val excludedByChannel =
                when (channel) {
                    AndroidUpdateChannel.STABLE -> isPrerelease != false || version.prerelease != null
                    AndroidUpdateChannel.BETA -> isPrerelease == null
                }
            if (excludedByChannel) return null
            return ReleaseIdentity(tag, version)
        }

        // Validate each untrusted asset field before constructing a download candidate.
        @Suppress("ReturnCount")
        private fun releaseAsset(
            obj: JsonObject,
            tag: String,
            config: UpdateConfig,
        ): ReleaseAsset? {
            val assets = (obj["assets"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: return null
            val apkAssets = assets.filter { it.string("name")?.endsWith(".apk", ignoreCase = true) == true }
            if (apkAssets.size != 1) return null
            val asset = apkAssets.single()
            val name = asset.string("name") ?: return null
            if (name != config.assetName(tag) || rejectedApkName(name)) return null
            val size = asset.long("size")?.takeIf { it in 1..MAX_APK_BYTES } ?: return null
            val urlText = asset.string("browser_download_url") ?: return null
            val url = validDownloadUrl(urlText, config, tag, name) ?: return null
            val digestValue = asset.string("digest")
            val digest =
                if (digestValue == null) {
                    null
                } else {
                    shaPattern
                        .matchEntire(digestValue)
                        ?.groupValues
                        ?.get(1)
                        ?.lowercase() ?: return null
                }
            return ReleaseAsset(name, url, size, digest)
        }

        private fun rejectedApkName(name: String): Boolean =
            listOf("debug", "unsigned", "test").any { name.contains(it, ignoreCase = true) }

        // URL failures stay explicit so the exact public repository path is the only accepted destination.
        @Suppress("ReturnCount")
        private fun validDownloadUrl(
            value: String,
            config: UpdateConfig,
            tag: String,
            asset: String,
        ): HttpUrl? {
            val url = value.toHttpUrlOrNull() ?: return null
            if (!hasSecureGithubOrigin(url) || !hasNoUrlDecoration(url)) return null
            if (!hasExpectedReleasePath(url, config, tag, asset)) return null
            return url
        }

        private fun hasSecureGithubOrigin(url: HttpUrl): Boolean =
            url.scheme == "https" && url.host == "github.com" && url.port == 443

        private fun hasNoUrlDecoration(url: HttpUrl): Boolean =
            url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null

        private fun hasExpectedReleasePath(
            url: HttpUrl,
            config: UpdateConfig,
            tag: String,
            asset: String,
        ): Boolean = url.pathSegments == listOf(config.owner, config.repository, "releases", "download", tag, asset)

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

        private fun JsonObject.boolean(key: String): Boolean? =
            (this[key] as? JsonPrimitive)
                ?.takeIf { it !is JsonNull && !it.isString }
                ?.content
                ?.toBooleanStrictOrNull()

        private fun JsonObject.long(key: String): Long? =
            (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.content?.toLongOrNull()
    }
}
