package dev.rogers.appupdater

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class UpdatePermissionsTest {
    @Test fun `missing network state permission prevents automatic wifi download eligibility`() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_NETWORK_STATE)
        assertEquals(
            PackageManager.PERMISSION_DENIED,
            ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_NETWORK_STATE),
        )
        val config = UpdateConfig("example", "another-app", "v", "another-app-")
        val manager = UpdateManager(app, config, { null }, { "1.0.0" })
        assertFalse(manager.hasWifiTransport())
    }
}
