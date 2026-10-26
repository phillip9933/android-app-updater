package example.updates

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import io.github.phillip9933.appupdater.UpdateManager
import io.github.phillip9933.appupdater.UpdateState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Host example: construct once per Activity; call onResume/onPause from the matching callbacks. */
class UpdatePromptController(
    private val activity: Activity,
    private val updater: UpdateManager,
    private val applicationScope: CoroutineScope,
) {
    private val preferences = activity.getSharedPreferences("host_update_prompt", Context.MODE_PRIVATE)
    private var foregroundScope: CoroutineScope? = null
    private var observer: Job? = null
    private var dialog: AlertDialog? = null
    private var dialogTag: String? = null
    private var preparing = false

    fun onResume() {
        if (foregroundScope != null) return
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        foregroundScope = scope
        observer = scope.launch {
            updater.state.collect { state ->
                val release = state.available
                if (dialog == null && release != null && state.offer &&
                    preferences.getString("presented_tag", null) != release.tag
                ) {
                    showCurrent()
                }
                render(state)
            }
        }
    }

    fun onPause() {
        foregroundScope?.cancel()
        foregroundScope = null
        observer = null
        preparing = false
        dialog?.dismiss()
        dialog = null
        dialogTag = null
    }

    /** Also wire this to a persistent, user-accessible Updates entry for retry or later installation. */
    fun showCurrent() {
        if (foregroundScope == null || dialog != null || activity.isFinishing) return
        val state = updater.state.value
        val release = state.available ?: return
        preferences.edit().putString("presented_tag", release.tag).apply()
        val prompt = AlertDialog.Builder(activity)
            .setTitle("App update")
            .setMessage("")
            .setPositiveButton("Update", null)
            .setNegativeButton("Later") { _, _ -> updater.dismissOffer() }
            .setOnCancelListener { updater.dismissOffer() }
            .create()
        dialog = prompt
        dialogTag = release.tag
        prompt.setOnDismissListener { if (dialog === prompt) dialog = null }
        prompt.show()
        prompt.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { act() }
        render(state)
    }

    private fun render(state: UpdateState) {
        val prompt = dialog ?: return
        val release = state.available
        if (release == null || release.tag != dialogTag) {
            prompt.dismiss()
            return
        }
        val status = when {
            preparing -> "Verifying downloaded update…"
            state.downloading -> "Downloading: ${state.bytesDownloaded} bytes"
            state.error != null -> "Update failed: ${state.error}. You can retry from Updates."
            state.ready -> "Download verified. Tap Install to verify again and open Android's installer."
            else -> "An update is available."
        }
        // GitHub release notes are untrusted plain text. No HTML, Markdown or automatic links.
        prompt.setMessage("${release.tag}\n$status\n\n${release.notes.take(8000)}")
        prompt.getButton(AlertDialog.BUTTON_POSITIVE).apply {
            text = when {
                state.downloading -> "Cancel download"
                state.ready && !activity.packageManager.canRequestPackageInstalls() -> "Allow installation"
                state.ready -> "Install"
                else -> "Update"
            }
            isEnabled = !preparing
        }
    }

    private fun act() {
        val scope = foregroundScope ?: return
        if (preparing) return
        val state = updater.state.value
        val selectedTag = dialogTag ?: return
        if (state.available?.tag != selectedTag) return
        when {
            state.downloading -> updater.cancelDownload()
            !state.ready -> applicationScope.launch { updater.download() }
            !activity.packageManager.canRequestPackageInstalls() -> launchSafely(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")),
            )
            else -> {
                preparing = true
                render(state)
                scope.launch {
                    try {
                        val intent = updater.prepareInstall()
                        // Permission changes, leaving the Activity and failed revalidation require a new tap.
                        if (foregroundScope === scope && !activity.isFinishing &&
                            updater.state.value.available?.tag == selectedTag &&
                            activity.packageManager.canRequestPackageInstalls()
                        ) {
                            if (intent != null) launchSafely(intent) else notifyUser("Update verification failed")
                        }
                    } finally {
                        preparing = false
                        render(updater.state.value)
                    }
                }
            }
        }
    }

    private fun launchSafely(intent: Intent) {
        try {
            activity.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            notifyUser("No application can handle this action")
        } catch (_: SecurityException) {
            notifyUser("Android did not permit this action")
        }
    }

    private fun notifyUser(message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }
}
