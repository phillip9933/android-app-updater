package example.upgrade

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UpgradeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val host get() = context.getSharedPreferences("host", Context.MODE_PRIVATE)
    private val prefs get() = context.getSharedPreferences("android_updates", Context.MODE_PRIVATE)
    private val sentinel get() = File(context.filesDir, "synthetic-sentinel.txt")
    private val work get() = WorkManager.getInstance(context)

    @Test fun seedLegacy() {
        assertEquals("legacy", BuildConfig.FLAVOR)
        val automatic = InstrumentationRegistry.getArguments().getString("automatic") != "false"
        sentinel.writeText("synthetic private data retained across signed upgrade")
        assertTrue(host.edit().putBoolean("seeded", true).putBoolean("automatic", automatic)
            .putBoolean("initialize", true).commit())
        assertTrue(prefs.edit().putBoolean("automatic", automatic).putBoolean("wifi", true)
            .putString("dismissed_tag", "android-v9.9.9").putLong("checked", System.currentTimeMillis())
            .putString("release_channel", "STABLE").putString("release_tag", "android-v1.0.1")
            .putString("release_asset", "example-android-1.0.1.apk")
            .putString("release_url", "https://github.com/example/updater/releases/download/android-v1.0.1/example-android-1.0.1.apk")
            .putLong("release_size", 1024).putString("release_notes", "synthetic release")
            .putLong("attempted", System.currentTimeMillis()).commit())
        val manager = Bridge.manager(context)
        manager.initialize()
        assertNotNull(manager.state.value.available)
        // Wait for initialize's asynchronous scheduling before replacing its request with a held one.
        val scheduled = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (work.getWorkInfosForUniqueWork("android-public-release-wifi-download").get().isEmpty() &&
            System.nanoTime() < scheduled) Thread.sleep(100)
        assertFalse(work.getWorkInfosForUniqueWork("android-public-release-wifi-download").get().isEmpty())
        // Persist an old-class download that cannot run before the host upgrade.
        val oldClass = Class.forName("dev.rogers.appupdater.UpdateDownloadWorker")
            .asSubclass(androidx.work.ListenableWorker::class.java)
        val request = OneTimeWorkRequest.Builder(oldClass)
            .setInputData(workDataOf("channel" to "STABLE", "tag" to "android-v1.0.1", "config_id" to
                manager.javaClass.getDeclaredMethod("configId").apply { isAccessible = true }.invoke(manager)))
            .setInitialDelay(24, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
            .build()
        work.enqueueUniqueWork("android-public-release-wifi-download", ExistingWorkPolicy.REPLACE, request)
            .result.get(20, TimeUnit.SECONDS)
        assertEquals(request.id, work.getWorkInfosForUniqueWork("android-public-release-wifi-download").get()
            .single { !it.state.isFinished }.id)
        assertTrue(activeWorkerClasses().any { it.startsWith("dev.rogers.appupdater.") })
        println("LEGACY_SEEDED automatic=$automatic workers=${activeWorkerClasses()}")
    }

    @Test fun verifyUpgrade() {
        assertEquals("current", BuildConfig.FLAVOR)
        assertRetainedData()
        val manager = Bridge.manager(context)
        assertEquals(host.getBoolean("automatic", true), manager.state.value.automaticChecks)
        assertTrue(manager.state.value.wifiDownload)
        assertTrue(Bridge.workerBootstrap(context) != null)
        awaitReplacedWorkers()
        val classes = activeWorkerClasses()
        assertTrue(classes.none { it.startsWith("dev.rogers.") })
        if (host.getBoolean("automatic", true)) {
            assertTrue(classes.contains("io.github.phillip9933.appupdater.UpdateWorker"))
        } else {
            assertTrue(classes.isEmpty())
        }
        // Cancellation and retry retain the user's opt-in and private data.
        manager.cancelDownload()
        manager.setWifiDownload(false)
        manager.setWifiDownload(true)
        assertTrue(manager.state.value.wifiDownload)
        assertNull(kotlinx.coroutines.runBlocking { manager.prepareInstall() })
        assertRetainedData()
        assertTrue(host.edit().putBoolean("initialize", false).commit())
        println("UPGRADE_VERIFIED automatic=${manager.state.value.automaticChecks} workers=$classes")
    }

    @Test fun verifyWorkerOnlyRecreation() {
        // Application initialization was disabled before the orchestrator force-stopped this host.
        assertFalse(host.getBoolean("initialize", true))
        assertRetainedData()
        assertNotNull(Bridge.workerBootstrap(context))
        val manager = Bridge.manager(context)
        assertEquals(host.getBoolean("automatic", true), manager.state.value.automaticChecks)
        val method = manager.javaClass.declaredMethods.single {
            it.name.startsWith("matchesWorkConfig") && it.parameterTypes.size == 1
        }
        method.isAccessible = true
        assertEquals(false, method.invoke(manager, "unrelated-source-token"))
        assertNull(kotlinx.coroutines.runBlocking { manager.prepareInstall() })
        println("WORKER_ONLY_RECREATION_VERIFIED")
    }

    private fun assertRetainedData() {
        assertEquals("synthetic private data retained across signed upgrade", sentinel.readText())
        assertEquals("android-v9.9.9", prefs.getString("dismissed_tag", null))
    }

    private fun awaitReplacedWorkers() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) {
            if (activeWorkerClasses().none { it.startsWith("dev.rogers.") }) return
            Thread.sleep(100)
        }
        fail("Old worker classes remain active: ${activeWorkerClasses()}")
    }

    private fun activeWorkerClasses(): List<String> {
        val ids = listOf("android-public-release-check", "android-public-release-wifi-download")
            .flatMap { work.getWorkInfosForUniqueWork(it).get(20, TimeUnit.SECONDS) }
            .filterNot { it.state.isFinished }.map { it.id.toString() }
        if (ids.isEmpty()) return emptyList()
        val file = File(context.noBackupFilesDir, "androidx.work.workdb")
        check(file.isFile) { "Expected WorkManager database under no-backup storage" }
        return SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            ids.map { id ->
                db.query("WorkSpec", arrayOf("worker_class_name"), "id=?", arrayOf(id), null, null, null)
                    .use { cursor -> check(cursor.moveToFirst()); cursor.getString(0) }
            }
        }
    }
}
