package io.github.phillip9933.appupdater

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.res.Resources
import android.content.res.XmlResourceParser
import android.os.Build
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Reads package metadata and signing identity before an APK is offered to Android's installer. */
internal class UpdateApkVerifier(
    private val context: Context,
    private val policy: UpdatePolicy,
) {
    // Separate guard returns map every verification failure to a stable UI error kind.
    @Suppress("ReturnCount")
    fun verify(
        file: File,
        release: AndroidUpdateRelease,
        expectedHash: String,
    ): UpdateError? {
        if (!file.isFile || file.length() != release.sizeBytes) return UpdateError.SIZE_MISMATCH
        val hash =
            try {
                sha256(file)
            } catch (_: IOException) {
                return UpdateError.STORAGE
            }
        if (!policy.matchingHash(hash, expectedHash) ||
            !policy.matchingHash(hash, release.sha256)
        ) {
            return UpdateError.HASH_MISMATCH
        }
        val installed = installedPackage() ?: return UpdateError.INVALID_APK
        val candidate = archivePackage(file) ?: return UpdateError.INVALID_APK
        val manifestDebuggable = archiveManifestDebuggable(file, candidate) ?: return UpdateError.INVALID_APK
        return policy
            .validApkMetadata(
                release,
                context.packageName,
                UpdatePolicy.InstalledApk(versionCode(installed), signers(installed)),
                UpdatePolicy.CandidateApk(
                    candidate.packageName,
                    versionCode(candidate),
                    candidate.versionName,
                    candidate.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE,
                    candidate.applicationInfo?.flags?.and(ApplicationInfo.FLAG_DEBUGGABLE) != 0 || manifestDebuggable,
                    signers(candidate),
                ),
                Build.VERSION.SDK_INT,
            ).asUpdateError()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Suppress("DEPRECATION")
    private fun installedPackage(): PackageInfo? =
        try {
            if (Build.VERSION.SDK_INT >=
                33
            ) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
                )
            } else {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    if (Build.VERSION.SDK_INT >=
                        28
                    ) {
                        PackageManager.GET_SIGNING_CERTIFICATES
                    } else {
                        PackageManager.GET_SIGNATURES
                    },
                )
            }
        } catch (_: Exception) {
            null
        }

    @Suppress("DEPRECATION")
    private fun archivePackage(file: File): PackageInfo? =
        try {
            if (Build.VERSION.SDK_INT >=
                33
            ) {
                context.packageManager.getPackageArchiveInfo(
                    file.absolutePath,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
                )
            } else {
                context.packageManager.getPackageArchiveInfo(
                    file.absolutePath,
                    if (Build.VERSION.SDK_INT >=
                        28
                    ) {
                        PackageManager.GET_SIGNING_CERTIFICATES
                    } else {
                        PackageManager.GET_SIGNATURES
                    },
                )
            }
        } catch (_: Exception) {
            null
        }

    /** Archive PackageInfo flags can omit manifest-derived values on API 35; read the APK's binary manifest. */
    private fun archiveManifestDebuggable(
        file: File,
        candidate: PackageInfo,
    ): Boolean? {
        val archiveInfo = candidate.applicationInfo ?: return null
        val application =
            ApplicationInfo(archiveInfo).apply {
                sourceDir = file.absolutePath
                publicSourceDir = file.absolutePath
                splitSourceDirs = null
                splitPublicSourceDirs = null
            }
        return try {
            val resources = context.packageManager.getResourcesForApplication(application)
            val parser = resources.assets.openXmlResourceParser("AndroidManifest.xml")
            try {
                parseArchiveManifestDebuggable(resources, parser)
            } finally {
                parser.close()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseArchiveManifestDebuggable(
        resources: Resources,
        parser: XmlResourceParser,
    ): Boolean? {
        if (!nextRootManifest(parser)) return null
        var sawApplication = false
        var debuggable = false
        var duplicateApplication = false
        var closedManifest = false
        while (!closedManifest && parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && isElement(parser, 2, "application")) {
                if (sawApplication) {
                    duplicateApplication = true
                } else {
                    sawApplication = true
                    debuggable = applicationDebuggable(resources, parser)
                }
            }
            if (parser.eventType == XmlPullParser.END_TAG && isElement(parser, 1, "manifest")) {
                closedManifest = true
            }
        }
        val validStructure = closedManifest && sawApplication && !duplicateApplication
        return if (validStructure && parser.next() == XmlPullParser.END_DOCUMENT) {
            debuggable
        } else {
            null
        }
    }

    private fun isElement(
        parser: XmlResourceParser,
        depth: Int,
        name: String,
    ): Boolean = parser.depth == depth && parser.name == name

    private fun nextRootManifest(parser: XmlResourceParser): Boolean {
        while (parser.eventType != XmlPullParser.START_TAG && parser.eventType != XmlPullParser.END_DOCUMENT) {
            parser.next()
        }
        return isElement(parser, 1, "manifest")
    }

    private fun applicationDebuggable(
        resources: Resources,
        parser: XmlResourceParser,
    ): Boolean {
        val attributes = resources.obtainAttributes(parser, intArrayOf(android.R.attr.debuggable))
        return try {
            attributes.getBoolean(0, false)
        } finally {
            attributes.recycle()
        }
    }

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): List<ByteArray> =
        if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo
                ?.apkContentsSigners
                ?.map { it.toByteArray() }
                .orEmpty()
        } else {
            info.signatures?.map { it.toByteArray() }.orEmpty()
        }

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >=
            28
        ) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
}

private fun UpdatePolicy.MetadataError?.asUpdateError(): UpdateError? =
    when (this) {
        UpdatePolicy.MetadataError.INVALID_RELEASE -> UpdateError.INVALID_RELEASE
        UpdatePolicy.MetadataError.WRONG_PACKAGE -> UpdateError.WRONG_PACKAGE
        UpdatePolicy.MetadataError.DEBUG_APK -> UpdateError.DEBUG_APK
        UpdatePolicy.MetadataError.INCOMPATIBLE_SDK -> UpdateError.INCOMPATIBLE_SDK
        UpdatePolicy.MetadataError.OLD_VERSION -> UpdateError.OLD_VERSION
        UpdatePolicy.MetadataError.VERSION_MISMATCH -> UpdateError.VERSION_MISMATCH
        UpdatePolicy.MetadataError.SIGNATURE_MISMATCH -> UpdateError.SIGNATURE_MISMATCH
        null -> null
    }
