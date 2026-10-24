package io.github.phillip9933.appupdater

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityBoundaryReviewTest {
    private val config = UpdateConfig("example", "updater", "v", "app-")
    private val policy = UpdatePolicy(config)
    private val release =
        AndroidUpdateRelease(
            "v2.0.0",
            "",
            "app-2.0.0.apk",
            "https://github.com/example/updater/releases/download/v2.0.0/app-2.0.0.apk",
            100,
            null,
            null,
        )

    @Test fun hostileInitialOriginsAndPathsAreRejected() {
        val urls =
            listOf(
                "http://github.com/example/updater/releases/download/v2.0.0/app-2.0.0.apk",
                release.downloadUrl.replace("github.com", "github.com.evil.invalid"),
                release.downloadUrl.replace("github.com", "github.com:444"),
                release.downloadUrl.replace("github.com", "user:password@github.com"),
                release.downloadUrl.replace("example/updater", "other/updater"),
                release.downloadUrl.replace("v2.0.0/app", "v3.0.0/app"),
                release.downloadUrl + "?token=x",
                release.downloadUrl + "#x",
                release.downloadUrl.replace("/releases/", "/%2freleases/"),
                release.downloadUrl.replace("/updater/", "/updater%2f../"),
            )
        urls.forEach { assertFalse(it, policy.validRelease(release.copy(downloadUrl = it))) }
    }

    @Test fun hostileRedirectAuthoritiesAreRejected() {
        val hosts =
            listOf(
                "localhost",
                "127.0.0.1",
                "[::1]",
                "169.254.169.254",
                "github.com",
                "release-assets.githubusercontent.com.evil.invalid",
                "objects.githubusercontent.com.evil.invalid",
            )
        hosts.forEach { assertFalse(it, policy.validRedirect("https://$it/object".toHttpUrl())) }
        for (host in listOf("release-assets.githubusercontent.com", "objects.githubusercontent.com")) {
            assertTrue(policy.validRedirect("https://$host/object?signature=public".toHttpUrl()))
            listOf(
                "http://$host/object",
                "https://$host:444/object",
                "https://user@$host/object",
                "https://$host/object#x",
            ).forEach { assertFalse(it, policy.validRedirect(it.toHttpUrl())) }
        }
    }

    @Test fun sizeDigestAndVersionBoundariesFailClosed() {
        listOf(Long.MIN_VALUE, -1L, 0L, 536870913L, Long.MAX_VALUE).forEach {
            assertFalse(policy.validRelease(release.copy(sizeBytes = it)))
        }
        listOf("", "a".repeat(63), "a".repeat(65), "g".repeat(64), "../file").forEach {
            assertFalse(policy.validRelease(release.copy(sha256 = it)))
        }
        listOf("2.00.0", "2.0.0/../x", "2.0.0\u0000", "2.0.0-beta.01", "9".repeat(129)).forEach {
            assertNull(config.versionFromTag("v$it"))
        }
    }

    @Test fun emptyAndDifferentSignerSetsFailClosed() {
        val a = byteArrayOf(1, 2)
        val b = byteArrayOf(3, 4)
        assertFalse(policy.matchingSigners(emptyList(), emptyList()))
        assertFalse(policy.matchingSigners(listOf(a), emptyList()))
        assertFalse(policy.matchingSigners(listOf(a), listOf(a, b)))
        assertTrue(policy.matchingSigners(listOf(a, b), listOf(b, a)))
    }

    @Test fun absentReleaseDigestDoesNotDisableApkIdentityChecks() {
        val installed = UpdatePolicy.InstalledApk(10, listOf(byteArrayOf(1)))
        val candidate = UpdatePolicy.CandidateApk("example.app", 11, "2.0.0", 26, false, listOf(byteArrayOf(1)))
        assertNull(policy.validApkMetadata(release, "example.app", installed, candidate, 35))
        assertEquals(
            UpdatePolicy.MetadataError.SIGNATURE_MISMATCH,
            policy.validApkMetadata(release, "example.app", installed, candidate.copy(signers = emptyList()), 35),
        )
        assertEquals(
            UpdatePolicy.MetadataError.DEBUG_APK,
            policy.validApkMetadata(release, "example.app", installed, candidate.copy(debuggable = true), 35),
        )
        assertEquals(
            UpdatePolicy.MetadataError.WRONG_PACKAGE,
            policy.validApkMetadata(
                release,
                "example.app",
                installed,
                candidate.copy(packageName = "attacker.app"),
                35,
            ),
        )
        assertEquals(
            UpdatePolicy.MetadataError.OLD_VERSION,
            policy.validApkMetadata(release, "example.app", installed, candidate.copy(versionCode = 10), 35),
        )
    }

    @Test fun malformedReleaseShapesCannotYieldCandidates() {
        listOf("null", "{}", "[null,1,true,[]]", "[{}]", "not JSON").forEach {
            assertNull(AndroidUpdateFeed.select(it, AndroidUpdateChannel.STABLE, config))
        }
    }
}
