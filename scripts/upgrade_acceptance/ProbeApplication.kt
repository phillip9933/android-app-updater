package example.upgrade

import android.app.Application

class ProbeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("host", MODE_PRIVATE)
        if (prefs.getBoolean("seeded", false) && prefs.getBoolean("initialize", true)) {
            Bridge.initialize(this)
        }
    }
}
