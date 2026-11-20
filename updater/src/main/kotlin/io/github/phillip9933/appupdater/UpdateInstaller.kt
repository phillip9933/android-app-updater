package io.github.phillip9933.appupdater

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri

/** Resolve the device's system installer before adding the scoped read grant. */
internal object UpdateInstaller {
    @Suppress("DEPRECATION")
    fun prepareIntent(
        packageManager: PackageManager,
        apk: Uri,
    ): Intent? {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(apk, "application/vnd.android.package-archive")
        val flags = PackageManager.MATCH_DEFAULT_ONLY or PackageManager.MATCH_SYSTEM_ONLY
        val activity =
            packageManager
                .queryIntentActivities(intent, flags)
                .mapNotNull { it.activityInfo }
                .firstOrNull { isSystemInstaller(packageManager, it) }
                ?: return null
        return intent
            .setComponent(ComponentName(activity.packageName, activity.name))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private fun isSystemInstaller(
        packageManager: PackageManager,
        activity: ActivityInfo,
    ): Boolean =
        activity.exported &&
            activity.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0 &&
            packageManager.checkPermission(Manifest.permission.INSTALL_PACKAGES, activity.packageName) ==
            PackageManager.PERMISSION_GRANTED
}
