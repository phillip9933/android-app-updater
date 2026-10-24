package io.github.phillip9933.appupdater

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Checks only the public release feed; it never downloads or installs APKs in background. */
class UpdateWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val manager = UpdateManager.getForWorker(applicationContext) ?: return@withContext Result.success()
            if (!manager.matchesWorkConfig(inputData.getString("config_id"))) return@withContext Result.success()
            if (!manager.automaticChecksEnabled()) return@withContext Result.success()
            try {
                val attempted = manager.checkNow(force = false)
                if (attempted &&
                    manager.state.value.error == UpdateError.NETWORK &&
                    runAttemptCount < 3
                ) {
                    Result.retry()
                } else {
                    Result.success()
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                if (runAttemptCount < 3) Result.retry() else Result.success()
            }
        }
}

/** Downloads only a previously selected public APK over Wi-Fi; never starts the installer. */
class UpdateDownloadWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val channel =
                runCatching { AndroidUpdateChannel.valueOf(inputData.getString("channel") ?: "") }.getOrNull()
                    ?: return@withContext Result.success()
            val tag = inputData.getString("tag") ?: return@withContext Result.success()
            try {
                val manager = UpdateManager.getForWorker(applicationContext) ?: return@withContext Result.success()
                if (!manager.matchesWorkConfig(inputData.getString("config_id"))) return@withContext Result.success()
                if (manager.automaticDownload(channel, tag)) {
                    Result.success()
                } else if (runAttemptCount < 3) {
                    Result.retry()
                } else {
                    Result.success()
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                if (runAttemptCount < 3) Result.retry() else Result.success()
            }
        }
}
