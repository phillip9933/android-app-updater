package io.github.phillip9933.updaterprobe

import android.os.SystemClock
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicitly selected physical-test driver; only clicks an identified synthetic-app installer button. */
class InstallerUiDriverAndroidTest {
    @Test fun clickRequestedInstallerButton() {
        val action = InstrumentationRegistry.getArguments().getString("installerAction")
        assumeTrue("Opt-in synthetic installer control only", action != null)
        require(action in setOf("Cancel", "Update"))
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        val deadline = SystemClock.uptimeMillis() + 30_000
        while (SystemClock.uptimeMillis() < deadline) {
            val root = automation.rootInActiveWindow
            if (root != null && root.packageName?.toString() in
                setOf("com.google.android.packageinstaller", "com.android.packageinstaller") &&
                root.findAccessibilityNodeInfosByText("Synthetic Updater Acceptance").isNotEmpty()
            ) {
                val id = if (action == "Cancel") "android:id/button2" else "android:id/button1"
                val node = root.findAccessibilityNodeInfosByViewId(id).singleOrNull()
                if (node != null && node.text?.toString() == action && node.isEnabled && node.isClickable) {
                    assertTrue("Installer rejected the exact widget click", node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                    println("SYNTHETIC_INSTALLER_ACTION=$action")
                    return
                }
            }
            SystemClock.sleep(100)
        }
        error("Expected synthetic-app installer confirmation was not visible; no other UI was clicked")
    }
}
