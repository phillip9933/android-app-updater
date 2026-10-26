package example.installhost

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import io.github.phillip9933.appupdater.AndroidUpdateRelease
import io.github.phillip9933.appupdater.UpdateConfig
import io.github.phillip9933.appupdater.UpdateManager
import io.github.phillip9933.appupdater.UpdateState
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Synthetic installer acceptance host, deliberately not consumer integration code. No network permission. */
class HostActivity : Activity() {
    private lateinit var manager: UpdateManager
    private lateinit var status: TextView
    private var resumed = false
    private var foreground: CoroutineScope? = null
    private val sentinel get() = File(filesDir, "synthetic-private-sentinel.txt")

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (BuildConfig.FLAVOR == "base" && !sentinel.exists()) {
            sentinel.writeText("UPDATER_PRIVATE_SENTINEL_20261026")
        }
        val privatePreferences = getSharedPreferences("synthetic_host", Context.MODE_PRIVATE)
        if (BuildConfig.FLAVOR == "base" && !privatePreferences.contains("marker")) {
            privatePreferences.edit().putString("marker", "UPDATER_PREFERENCE_20261026").commit()
        }
        // Keep this synthetic fixture offline and prevent periodic checks.
        getSharedPreferences("android_updates", Context.MODE_PRIVATE).edit()
            .putBoolean("automatic", false).putLong("checked", System.currentTimeMillis())
            .putLong("attempted", System.currentTimeMillis()).commit()
        manager = UpdateManager.get(this, UpdateConfig("example", "updater", "android-v", "example-android-"))
        manager.initialize()
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 32, 24, 24) }
        layout.addView(TextView(this).apply { text = "SYNTHETIC UPDATER INSTALL TEST"; textSize = 20f })
        status = TextView(this).apply { textSize = 15f }
        layout.addView(status)
        fun button(label: String, action: () -> Unit) {
            layout.addView(Button(this).apply { text = label; setOnClickListener { action() } })
        }
        button("Stage synthetic candidate") {
            foreground?.launch {
                try {
                    withContext(Dispatchers.IO) { stageCandidate() }
                    status.text = "STAGED verified=${manager.prepareInstall() != null}\n${report()}"
                } catch (failure: Exception) {
                    status.text = "STAGE_ERROR ${failure.javaClass.simpleName}\n${report()}"
                }
            }
        }
        button("Installation permission") {
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
        }
        button("Install verified update") {
            val scope = foreground ?: return@button
            scope.launch {
                if (!packageManager.canRequestPackageInstalls()) {
                    status.text = "PERMISSION_DENIED\n${report()}"
                } else {
                    val intent = manager.prepareInstall()
                    if (resumed && foreground === scope && intent != null) startActivity(intent)
                    else status.text = "NO_INSTALLER_HANDOFF\n${report()}"
                }
            }
        }
        button("Cancel synthetic download") { manager.cancelDownload(); status.text = "CANCELLED\n${report()}" }
        button("Refresh synthetic report") { status.text = report() }
        setContentView(layout)
        status.text = report()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        foreground = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        foreground?.launch { manager.state.collect { status.text = report() } }
    }

    override fun onPause() {
        resumed = false
        foreground?.cancel()
        foreground = null
        super.onPause()
    }

    private fun report(): String = "host=${BuildConfig.VERSION_NAME}\n" +
        "permission=${packageManager.canRequestPackageInstalls()}\n" +
        "ready=${manager.state.value.ready} error=${manager.state.value.error}\n" +
        "sentinel=${if (sentinel.isFile) sentinel.readText() else "MISSING"}\n" +
        "preference=${getSharedPreferences("synthetic_host", Context.MODE_PRIVATE).getString("marker", "MISSING")}"

    private fun stageCandidate() {
        val bytes = assets.open("candidate.apk").use { it.readBytes() }
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val tag = "android-v${BuildConfig.CANDIDATE_VERSION}"
        val name = MessageDigest.getInstance("SHA-256").digest("$tag\u0000$sha".toByteArray())
            .joinToString("") { "%02x".format(it) }
        File(cacheDir, "updates/update-$name.apk").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        val release = AndroidUpdateRelease(tag, "Synthetic installer fixture", "example-android-${BuildConfig.CANDIDATE_VERSION}.apk",
            "https://github.com/example/updater/releases/download/$tag/example-android-${BuildConfig.CANDIDATE_VERSION}.apk", bytes.size.toLong(), sha, null)
        getSharedPreferences("android_updates", Context.MODE_PRIVATE).edit()
            .putString("release_channel", "STABLE").putString("release_tag", tag)
            .putString("release_notes", release.notes).putString("release_asset", release.assetName)
            .putString("release_url", release.downloadUrl).putLong("release_size", release.sizeBytes)
            .putString("release_sha", sha).putString("ready_tag", tag).putString("ready_sha", sha).commit()
        // Synthetic cache seeding only. The real SDK prepareInstall() must verify these bytes before handoff.
        @Suppress("UNCHECKED_CAST")
        val mutable = manager.state as MutableStateFlow<UpdateState>
        mutable.value = mutable.value.copy(available = release, ready = true, error = null)
    }
}
