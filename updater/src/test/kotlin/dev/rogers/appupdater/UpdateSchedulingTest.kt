package dev.rogers.appupdater

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class UpdateSchedulingTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val config = UpdateConfig("example", "updater", "android-v", "example-android-")

    @Before fun setUp() {
        UpdateManager.clearProcessInstanceForTests()
        context
            .getSharedPreferences("dev.rogers.appupdater.bootstrap", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        context
            .getSharedPreferences(config.stateName, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    @Test fun `scheduling replaces an old periodic worker class and supplies config token`() {
        val work = WorkManager.getInstance(context)
        work
            .enqueueUniquePeriodicWork(
                "android-public-release-check",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<UpdateDownloadWorker>(24, TimeUnit.HOURS).build(),
            ).result
            .get()

        val manager = UpdateManager.get(context, config)
        manager.setAutomaticChecks(true)

        val info = work.getWorkInfosForUniqueWork("android-public-release-check").get().single()
        val spec =
            WorkManagerImpl
                .getInstance(context)
                .workDatabase
                .workSpecDao()
                .getWorkSpec(info.id.toString())!!
        assertEquals(UpdateWorker::class.java.name, spec.workerClassName)
        assertTrue(manager.matchesWorkConfig(spec.input.getString("config_id")))
    }
}
