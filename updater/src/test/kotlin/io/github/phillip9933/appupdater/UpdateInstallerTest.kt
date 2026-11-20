package io.github.phillip9933.appupdater

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class UpdateInstallerTest {
    private val packageManager = RuntimeEnvironment.getApplication().packageManager
    private val apk = Uri.parse("content://example.updates/verified.apk")

    @Test fun `handoff requires an exported system installer with install permission`() {
        registerHandler("example.viewer", system = false, canInstall = false)
        registerHandler("example.system.viewer", system = true, canInstall = false)
        registerHandler("example.internal.installer", system = true, canInstall = true, exported = false)
        assertNull(UpdateInstaller.prepareIntent(packageManager, apk))

        registerHandler("example.device.installer", system = true, canInstall = true)
        val intent = requireNotNull(UpdateInstaller.prepareIntent(packageManager, apk))
        val expected = ComponentName("example.device.installer", "example.device.installer.InstallActivity")
        assertEquals(expected, intent.component)
        assertEquals(apk, intent.data)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags)
    }

    private fun registerHandler(
        name: String,
        system: Boolean,
        canInstall: Boolean,
        exported: Boolean = true,
    ) {
        val app =
            ApplicationInfo().apply {
                packageName = name
                flags = if (system) ApplicationInfo.FLAG_SYSTEM else 0
                enabled = true
            }
        val activity =
            ActivityInfo().apply {
                packageName = name
                this.name = "$name.InstallActivity"
                applicationInfo = app
                this.exported = exported
                enabled = true
            }
        val info =
            PackageInfo().apply {
                packageName = name
                applicationInfo = app
                activities = arrayOf(activity)
                if (canInstall) {
                    requestedPermissions = arrayOf(Manifest.permission.INSTALL_PACKAGES)
                    requestedPermissionsFlags = intArrayOf(PackageInfo.REQUESTED_PERMISSION_GRANTED)
                }
            }
        val shadow = shadowOf(packageManager)
        shadow.installPackage(info)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(apk, "application/vnd.android.package-archive")
        shadow.addResolveInfoForIntentNoDefaults(
            view,
            ResolveInfo().apply {
                activityInfo = activity
                isDefault = true
            },
        )
    }
}
