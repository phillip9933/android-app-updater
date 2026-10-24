package io.github.phillip9933.appupdater

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class UpdateManagerTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val config =
        UpdateConfig(
            owner = "example",
            repository = "updater",
            tagPrefix = "android-v",
            assetPrefix = "example-android-",
        )
    private val release =
        AndroidUpdateRelease(
            "android-v2.0.0",
            "Release notes",
            "example-android-2.0.0.apk",
            "https://github.com/example/updater/releases/download/android-v2.0.0/example-android-2.0.0.apk",
            1024,
            "ab".repeat(32),
            null,
        )

    @Before fun reset() {
        UpdateManager.clearProcessInstanceForTests()
        context.cacheDir.resolve("updates").deleteRecursively()
        context
            .getSharedPreferences("android_updates", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        context
            .getSharedPreferences("io.github.phillip9933.appupdater.bootstrap", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test fun `worker with no bootstrap cannot start network work`() {
        assertNull(UpdateManager.getForWorker(context))
        val worker = TestListenableWorkerBuilder<UpdateWorker>(context).build()
        assertEquals(ListenableWorker.Result.success(), runBlocking { worker.doWork() })
    }

    @Test fun `failed bootstrap write leaves updater inactive and host can retry`() =
        runBlocking {
            val failingContext =
                object : ContextWrapper(context) {
                    override fun getApplicationContext(): Context = this

                    override fun getSharedPreferences(
                        name: String,
                        mode: Int,
                    ): SharedPreferences {
                        val original = super.getSharedPreferences(name, mode)
                        return if (name ==
                            "io.github.phillip9933.appupdater.bootstrap"
                        ) {
                            failingCommits(original)
                        } else {
                            original
                        }
                    }
                }
            val disabled = UpdateManager.get(failingContext, config)
            assertEquals(UpdateError.STORAGE, disabled.state.value.error)
            disabled.initialize()
            assertFalse(disabled.checkNow())
            assertNull(disabled.prepareInstall())
            assertNull(UpdateManager.getForWorker(failingContext))

            val recovered = UpdateManager.get(context, config)
            assertFalse(disabled === recovered)
            assertNull(recovered.state.value.error)
            UpdateManager.clearProcessInstanceForTests()
            assertEquals(config, UpdateManager.getForWorker(context)?.config)
        }

    private fun failingCommits(delegate: SharedPreferences): SharedPreferences =
        object : SharedPreferences by delegate {
            override fun edit(): SharedPreferences.Editor {
                val target = delegate.edit()
                lateinit var proxy: SharedPreferences.Editor
                proxy =
                    Proxy.newProxyInstance(
                        SharedPreferences.Editor::class.java.classLoader,
                        arrayOf(SharedPreferences.Editor::class.java),
                    ) { _, method, args ->
                        if (method.name == "commit") {
                            false
                        } else {
                            val result = method.invoke(target, *(args ?: emptyArray()))
                            if (result is SharedPreferences.Editor) proxy else result
                        }
                    } as SharedPreferences.Editor
                return proxy
            }
        }

    @Test fun `worker restores persisted configuration after process death`() {
        val registered = UpdateManager.get(context, config)
        UpdateManager.clearProcessInstanceForTests()
        val restored = UpdateManager.getForWorker(context)
        assertEquals(config, restored?.config)
        assertFalse(registered === restored)
    }

    @Test fun `worker ignores bootstrap from older host version until host registers`() {
        UpdateManager.get(context, config)
        val bootstrap = context.getSharedPreferences("io.github.phillip9933.appupdater.bootstrap", Context.MODE_PRIVATE)
        bootstrap.edit().putLong("host_version_code", -1).commit()
        UpdateManager.clearProcessInstanceForTests()
        assertNull(UpdateManager.getForWorker(context))

        val changed = config.copy(repository = "new-repository")
        assertEquals(changed, UpdateManager.get(context, changed).config)
        UpdateManager.clearProcessInstanceForTests()
        assertEquals(changed, UpdateManager.getForWorker(context)?.config)
    }

    @Test fun `worker ignores missing host version marker until host repairs it`() {
        UpdateManager.get(context, config)
        val bootstrap = context.getSharedPreferences("io.github.phillip9933.appupdater.bootstrap", Context.MODE_PRIVATE)
        bootstrap.edit().remove("host_version_code").commit()
        UpdateManager.clearProcessInstanceForTests()
        assertNull(UpdateManager.getForWorker(context))
        UpdateManager.get(context, config)
        UpdateManager.clearProcessInstanceForTests()
        assertEquals(config, UpdateManager.getForWorker(context)?.config)
    }

    @Test fun `different configuration is rejected in process and safely replaces persisted config after recreation`() {
        UpdateManager.get(context, config)
        val different = config.copy(repository = "other")
        assertTrue(runCatching { UpdateManager.get(context, different) }.exceptionOrNull() is IllegalArgumentException)
        val prefs = context.getSharedPreferences(config.stateName, Context.MODE_PRIVATE)
        prefs
            .edit()
            .putString("release_tag", release.tag)
            .putString("ready_tag", release.tag)
            .putBoolean("automatic", false)
            .putBoolean("wifi", true)
            .commit()
        val oldApk = context.cacheDir.resolve("updates/update-${"a".repeat(64)}.apk")
        oldApk.parentFile?.mkdirs()
        oldApk.writeText("stale")
        val handoff = context.cacheDir.resolve("updates/update-${"b".repeat(64)}.apk")
        handoff.writeText("installer")
        prefs
            .edit()
            .putString("handoff_file", handoff.name)
            .putLong("handoff_at", System.currentTimeMillis())
            .commit()
        UpdateManager.clearProcessInstanceForTests()
        val replacement = UpdateManager.get(context, different)
        assertEquals(different, replacement.config)
        assertNull(prefs.getString("release_tag", null))
        assertNull(prefs.getString("ready_tag", null))
        assertFalse(oldApk.exists())
        assertTrue(handoff.exists())
        assertFalse(replacement.state.value.automaticChecks)
        assertTrue(replacement.state.value.wifiDownload)
        UpdateManager.clearProcessInstanceForTests()
        assertEquals(different, UpdateManager.getForWorker(context)?.config)
    }

    @Test fun `invalid worker bootstrap is ignored and repaired by host`() {
        context
            .getSharedPreferences("io.github.phillip9933.appupdater.bootstrap", Context.MODE_PRIVATE)
            .edit()
            .putInt("version", 1)
            .putString("owner", "example")
            .commit()
        assertNull(UpdateManager.getForWorker(context))
        assertEquals(config, UpdateManager.get(context, config).config)
        UpdateManager.clearProcessInstanceForTests()
        assertEquals(config, UpdateManager.getForWorker(context)?.config)
    }

    @Test fun `scheduled worker ignores stale configuration token`() =
        runBlocking {
            UpdateManager.get(context, config)
            val worker =
                TestListenableWorkerBuilder<UpdateWorker>(context)
                    .setInputData(Data.Builder().putString("config_id", "stale").build())
                    .build()
            assertEquals(ListenableWorker.Result.success(), worker.doWork())
        }

    @Test fun `bootstrap preserves legacy options and custom state name`() {
        val custom =
            config.copy(
                stateName = "custom_updates",
                legacyVersions = setOf("0.9.3"),
                legacyAssetPrefix = "example-",
            )
        UpdateManager.get(context, custom)
        UpdateManager.clearProcessInstanceForTests()
        assertEquals(custom, UpdateManager.getForWorker(context)?.config)
    }

    @Test fun `check saves offer and dismissal persists through manager recreation`() =
        runBlocking {
            val manager = UpdateManager(context, config, { release }, { "1.0.0" })
            manager.checkNow()
            assertEquals(release, manager.state.value.available)
            assertTrue(manager.state.value.offer)
            assertTrue(manager.state.value.lastChecked > 0)
            manager.dismissOffer()
            assertFalse(manager.state.value.offer)

            val restored = UpdateManager(context, config, { release }, { "1.0.0" })
            assertEquals(release, restored.state.value.available)
            assertFalse(restored.state.value.offer)
        }

    @Test fun `installed update removes stale saved offer on recreation`() =
        runBlocking {
            UpdateManager(context, config, { release }, { "1.0.0" }).checkNow()
            val upgraded = UpdateManager(context, config, { null }, { "2.0.0" })
            assertNull(upgraded.state.value.available)
            assertFalse(upgraded.state.value.offer)
        }

    @Test fun `failed feed keeps saved offer and checked time`() =
        runBlocking {
            val manager = UpdateManager(context, config, { release }, { "1.0.0" })
            manager.checkNow()
            val checked = manager.state.value.lastChecked
            val failing = UpdateManager(context, config, { throw java.io.IOException() }, { "1.0.0" })
            failing.checkNow()
            assertEquals(release, failing.state.value.available)
            assertEquals(checked, failing.state.value.lastChecked)
            assertEquals(UpdateError.NETWORK, failing.state.value.error)
        }

    @Test fun `background checks throttle failed attempts but manual check forces retry`() =
        runBlocking {
            val attempts = AtomicInteger()
            val manager =
                UpdateManager(context, config, {
                    attempts.incrementAndGet()
                    throw java.io.IOException()
                }, { "1.0.0" })
            manager.checkNow(force = false)
            manager.checkNow(force = false)
            assertEquals(1, attempts.get())
            manager.checkNow()
            assertEquals(2, attempts.get())
        }

    @Test fun `cache pruning keeps ready and recent previous but removes stale files after upgrade`() =
        runBlocking {
            val manager = UpdateManager(context, config, { release }, { "1.0.0" })
            manager.checkNow()
            val directory = context.cacheDir.resolve("updates").also { it.mkdirs() }
            val ready = manager.apkFile(release, "ab".repeat(32)).also { it.writeText("ready") }
            val recent = directory.resolve("update-${"c".repeat(64)}.apk").also { it.writeText("recent") }
            val stale = directory.resolve("update-${"d".repeat(64)}.apk").also { it.writeText("stale") }
            val twoDaysAgo = System.currentTimeMillis() - 2L * 24 * 60 * 60 * 1000
            ready.setLastModified(twoDaysAgo)
            stale.setLastModified(twoDaysAgo)
            context
                .getSharedPreferences("android_updates", Context.MODE_PRIVATE)
                .edit()
                .putString("ready_tag", release.tag)
                .putString("ready_sha", "ab".repeat(32))
                .commit()

            manager.pruneCache()
            assertTrue(ready.exists())
            assertTrue(recent.exists())
            assertFalse(stale.exists())

            val upgraded = UpdateManager(context, config, { null }, { "2.0.0" })
            upgraded.pruneCache()
            assertFalse(ready.exists())
            assertTrue(recent.exists())
        }

    @Test fun `recent installer handoff survives process recreation and pruning`() =
        runBlocking {
            val manager = UpdateManager(context, config, { release }, { "1.0.0" })
            manager.checkNow()
            val directory = context.cacheDir.resolve("updates").also { it.mkdirs() }
            val handedOff = directory.resolve("update-${"e".repeat(64)}.apk").also { it.writeText("installer") }
            handedOff.setLastModified(System.currentTimeMillis() - 2L * 24 * 60 * 60 * 1000)
            context
                .getSharedPreferences("android_updates", Context.MODE_PRIVATE)
                .edit()
                .putString("handoff_file", handedOff.name)
                .putLong("handoff_at", System.currentTimeMillis())
                .commit()

            val upgraded = UpdateManager(context, config, { null }, { "2.0.0" })
            upgraded.pruneCache()
            assertTrue(handedOff.exists())
        }

    @Test fun `malformed preferences fall back without preventing startup`() {
        context
            .getSharedPreferences("android_updates", Context.MODE_PRIVATE)
            .edit()
            .putString("automatic", "unexpected")
            .putInt("wifi", 5)
            .putBoolean("channel", true)
            .putString("checked", "invalid")
            .commit()
        val manager = UpdateManager(context, config, { null }, { "1.0.0" })
        assertTrue(manager.state.value.automaticChecks)
        assertFalse(manager.state.value.wifiDownload)
        assertEquals(AndroidUpdateChannel.STABLE, manager.state.value.channel)
        assertEquals(0L, manager.state.value.lastChecked)
        assertNull(manager.state.value.available)
    }
}
