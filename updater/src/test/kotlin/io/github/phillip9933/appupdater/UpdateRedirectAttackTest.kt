package io.github.phillip9933.appupdater

import android.content.Context
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Synthetic HTTP responses exercise the actual download loop without network traffic. */
@RunWith(RobolectricTestRunner::class)
class UpdateRedirectAttackTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val config = UpdateConfig("example", "updater", "v", "app-")
    private val release =
        AndroidUpdateRelease(
            "v2.0.0",
            "",
            "app-2.0.0.apk",
            "https://github.com/example/updater/releases/download/v2.0.0/app-2.0.0.apk",
            100,
            "a".repeat(64),
            null,
        )

    @Before fun clearState() {
        context
            .getSharedPreferences(config.stateName, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test fun `redirect injection is rejected before any attacker request`() =
        runBlocking {
            val malicious =
                listOf(
                    "https://attacker.invalid/fake.apk",
                    "//attacker.invalid/fake.apk",
                    "http://release-assets.githubusercontent.com/object",
                    "https://127.0.0.1/object",
                    "https://169.254.169.254/latest/meta-data/",
                    "file:///data/data/app/file",
                    "content://attacker/object",
                    "intent://install/#Intent;scheme=https;end",
                    "javascript:alert(1)",
                    "https://user:password@release-assets.githubusercontent.com/object",
                    "https://release-assets.githubusercontent.com:444/object",
                    "https://release-assets.githubusercontent.com.attacker.invalid/object",
                    "https://release-assets.githubusercontent.com/object#fragment",
                    "/example/other/releases/download/v9/evil.apk",
                )
            malicious.forEach { location ->
                val requested = mutableListOf<String>()
                val manager = manager(requested) { response -> response.header("Location", location) }
                manager.checkNow()
                manager.download()
                assertEquals(location, listOf(release.downloadUrl), requested)
                assertEquals(location, UpdateError.INVALID_RELEASE, manager.state.value.error)
                assertFalse(manager.state.value.ready)
                assertNull(manager.prepareInstall())
            }
        }

    @Test fun `second hop cannot escape approved CDN`() =
        runBlocking {
            val requested = mutableListOf<String>()
            val cdn = "https://release-assets.githubusercontent.com/object?signature=synthetic"
            val manager =
                manager(requested) { response ->
                    response.header("Location", if (requested.size == 1) cdn else "https://attacker.invalid/other.apk")
                }
            manager.checkNow()
            manager.download()
            assertEquals(listOf(release.downloadUrl, cdn), requested)
            assertEquals(UpdateError.INVALID_RELEASE, manager.state.value.error)
            assertNull(manager.prepareInstall())
        }

    @Test fun `redirect loops are bounded and cannot become install ready`() =
        runBlocking {
            val requested = mutableListOf<String>()
            val manager = manager(requested) { it.header("Location", "https://objects.githubusercontent.com/loop") }
            manager.checkNow()
            manager.download()
            assertEquals(6, requested.size)
            assertEquals(UpdateError.INVALID_RELEASE, manager.state.value.error)
            assertNull(manager.prepareInstall())
        }

    @Test fun `redirect without location fails closed`() =
        runBlocking {
            val requested = mutableListOf<String>()
            val manager = manager(requested) { it }
            manager.checkNow()
            manager.download()
            assertEquals(1, requested.size)
            assertEquals(UpdateError.INVALID_RELEASE, manager.state.value.error)
            assertNull(manager.prepareInstall())
        }

    @Test fun `CDN body still must match pinned digest`() =
        runBlocking {
            val requested = mutableListOf<String>()
            val manager =
                manager(requested) {
                    if (requested.size == 1) {
                        it.header("Location", "https://objects.githubusercontent.com/object")
                    } else {
                        it.code(200).body(ByteArray(100) { 1 }.toResponseBody())
                    }
                }
            manager.checkNow()
            manager.download()
            assertEquals(2, requested.size)
            assertEquals(UpdateError.HASH_MISMATCH, manager.state.value.error)
            assertNull(manager.prepareInstall())
        }

    private fun manager(
        requested: MutableList<String>,
        reply: (Response.Builder) -> Response.Builder,
    ): UpdateManager {
        val manager = UpdateManager(context, config, { release }, { "1.0.0" })
        val fake =
            OkHttpClient
                .Builder()
                .followRedirects(false)
                .addInterceptor { chain ->
                    requested += chain.request().url.toString()
                    reply(
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(302)
                            .message("Synthetic response")
                            .body(ByteArray(0).toResponseBody()),
                    ).build()
                }.build()
        UpdateManager::class.java
            .getDeclaredField("client")
            .apply { isAccessible = true }
            .set(manager, fake)
        return manager
    }
}
