package dev.rogers.appupdater

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.security.MessageDigest

/** Release and APK metadata checks shared by apps; Android package-manager access stays in the app. */
class UpdatePolicy(
    private val config: UpdateConfig,
) {
    enum class MetadataError {
        INVALID_RELEASE,
        WRONG_PACKAGE,
        DEBUG_APK,
        INCOMPATIBLE_SDK,
        OLD_VERSION,
        VERSION_MISMATCH,
        SIGNATURE_MISMATCH,
    }

    data class InstalledApk(
        val versionCode: Long,
        val signers: List<ByteArray>,
    )

    data class CandidateApk(
        val packageName: String,
        val versionCode: Long,
        val versionName: String?,
        val minSdk: Int,
        val debuggable: Boolean,
        val signers: List<ByteArray>,
    )

    fun expectedVersion(tag: String): String? = config.versionFromTag(tag)

    fun isNewer(
        candidate: String,
        installed: String,
    ): Boolean = AndroidUpdateFeed.isNewer(candidate, installed)

    @Suppress("ReturnCount")
    fun validRelease(release: AndroidUpdateRelease): Boolean {
        val expectedAsset = config.assetName(release.tag) ?: return false
        if (release.assetName != expectedAsset || release.sizeBytes !in 1..MAX_APK_BYTES) return false
        if (release.assetName.contains("debug", true) ||
            release.assetName.contains("unsigned", true) ||
            release.assetName.contains("test", true)
        ) {
            return false
        }
        val checksum = release.sha256
        if (checksum != null && !HASH.matches(checksum)) return false
        val url = release.downloadUrl.toHttpUrlOrNull() ?: return false
        return cleanHttps(url) &&
            url.host == "github.com" &&
            url.query == null &&
            url.encodedPathSegments ==
            listOf(config.owner, config.repository, "releases", "download", release.tag, release.assetName)
    }

    fun validRedirect(url: HttpUrl): Boolean =
        cleanHttps(url) && url.host in DOWNLOAD_HOSTS && url.encodedPath.isNotEmpty()

    fun matchingSigners(
        installed: List<ByteArray>,
        candidate: List<ByteArray>,
    ): Boolean {
        if (installed.isEmpty() || candidate.isEmpty()) return false

        fun fingerprints(values: List<ByteArray>) =
            values
                .map {
                    MessageDigest.getInstance("SHA-256").digest(it).toList()
                }.toSet()
        return fingerprints(installed) == fingerprints(candidate)
    }

    fun matchingHash(
        actual: String,
        expected: String?,
    ): Boolean = expected == null || HASH.matches(expected) && actual.equals(expected, true)

    fun validApkMetadata(
        release: AndroidUpdateRelease,
        packageName: String,
        installed: InstalledApk,
        candidate: CandidateApk,
        deviceSdk: Int,
    ): MetadataError? =
        when {
            !validRelease(release) -> MetadataError.INVALID_RELEASE
            candidate.packageName != packageName -> MetadataError.WRONG_PACKAGE
            candidate.debuggable -> MetadataError.DEBUG_APK
            candidate.minSdk > deviceSdk -> MetadataError.INCOMPATIBLE_SDK
            candidate.versionCode <= installed.versionCode -> MetadataError.OLD_VERSION
            candidate.versionName != expectedVersion(release.tag) -> MetadataError.VERSION_MISMATCH
            !matchingSigners(installed.signers, candidate.signers) -> MetadataError.SIGNATURE_MISMATCH
            else -> null
        }

    private fun cleanHttps(url: HttpUrl) =
        url.scheme == "https" &&
            url.port == 443 &&
            url.username.isEmpty() &&
            url.password.isEmpty() &&
            url.fragment == null

    private companion object {
        const val MAX_APK_BYTES = 512L * 1024 * 1024
        val HASH = Regex("[0-9a-fA-F]{64}")
        val DOWNLOAD_HOSTS = setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com")
    }
}
