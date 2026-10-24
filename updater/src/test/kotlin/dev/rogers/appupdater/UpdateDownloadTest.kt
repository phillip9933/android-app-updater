package dev.rogers.appupdater

import android.content.Context
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
class UpdateDownloadTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val config =
        UpdateConfig(
            owner = "example",
            repository = "updater",
            tagPrefix = "android-v",
            assetPrefix = "example-android-",
        )
    private val bytes = "apk test bytes".toByteArray()

    @Before fun clearPrivateTestState() {
        context
            .getSharedPreferences("android_updates", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        File(context.cacheDir, "updates/update.part").delete()
    }

    @Test fun `exact response is streamed and hash checked without becoming install ready`() =
        runBlocking {
            val manager = manager()
            val expected = hash(bytes)
            val digest =
                manager.saveResponseBody(
                    response(bytes.size.toLong(), bytes),
                    release(bytes.size.toLong(), expected),
                    0,
                )
            assertEquals(expected, digest)
            assertEquals(bytes.toList(), File(context.cacheDir, "updates/update.part").readBytes().toList())
            assertNull(manager.prepareInstall())
        }

    @Test fun `truncated body with dishonest content length is rejected`() =
        runBlocking {
            val expectedSize = bytes.size.toLong() + 3
            assertRejected(UpdateError.SIZE_MISMATCH, response(expectedSize, bytes), release(expectedSize, null))
        }

    @Test fun `extra response bytes cannot overrun advertised size`() =
        runBlocking {
            val expectedSize = bytes.size.toLong() - 1
            assertRejected(UpdateError.SIZE_MISMATCH, response(expectedSize, bytes), release(expectedSize, null))
            assertEquals(0L, File(context.cacheDir, "updates/update.part").length())
        }

    @Test fun `wrong published hash cannot be accepted`() =
        runBlocking {
            assertRejected(
                UpdateError.HASH_MISMATCH,
                response(bytes.size.toLong(), bytes),
                release(bytes.size.toLong(), "00".repeat(32)),
            )
        }

    @Test fun `cancelled download generation cannot finish a response`() =
        runBlocking {
            val manager = manager()
            manager.cancelDownload()
            val failure =
                runCatching {
                    manager.saveResponseBody(
                        response(bytes.size.toLong(), bytes),
                        release(bytes.size.toLong(), hash(bytes)),
                        0,
                    )
                }.exceptionOrNull()
            assertEquals(UpdateError.CANCELLED, (failure as UpdateFailure).kind)
            assertNull(manager.prepareInstall())
        }

    @Test fun `unknown content length still requires exact streamed bytes and digest`() =
        runBlocking {
            val expected = hash(bytes)
            assertEquals(
                expected,
                manager().saveResponseBody(response(-1, bytes), release(bytes.size.toLong(), expected), 0),
            )
        }

    @Test fun `unknown content length cannot accept a truncated response`() =
        runBlocking {
            assertRejected(UpdateError.SIZE_MISMATCH, response(-1, bytes), release(bytes.size.toLong() + 1, null))
        }

    @Test fun `unknown content length cannot exceed release size`() =
        runBlocking {
            assertRejected(UpdateError.SIZE_MISMATCH, response(-1, bytes), release(bytes.size.toLong() - 1, null))
        }

    private suspend fun assertRejected(
        error: UpdateError,
        response: Response,
        release: AndroidUpdateRelease,
    ) {
        val manager = manager()
        val failure = runCatching { manager.saveResponseBody(response, release, 0) }.exceptionOrNull()
        assertEquals(error, (failure as UpdateFailure).kind)
        assertNull(manager.prepareInstall())
    }

    private fun manager() = UpdateManager(context, config, { null }, { "1.0.0" })

    private fun release(
        size: Long,
        hash: String?,
    ) = AndroidUpdateRelease(
        "android-v1.0.1",
        "",
        "example-android-1.0.1.apk",
        "https://github.com/example/updater/releases/download/android-v1.0.1/example-android-1.0.1.apk",
        size,
        hash,
        null,
    )

    private fun response(
        advertisedSize: Long,
        data: ByteArray,
    ): Response =
        Response
            .Builder()
            .request(
                Request
                    .Builder()
                    .url(
                        "https://github.com/example/updater/releases/download/android-v1.0.1/example-android-1.0.1.apk",
                    ).build(),
            ).protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(
                object : ResponseBody() {
                    override fun contentType(): MediaType? = null

                    override fun contentLength() = advertisedSize

                    override fun source(): BufferedSource = Buffer().write(data)
                },
            ).build()

    private fun hash(data: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") {
            "%02x".format(it)
        }
}
