package io.github.phillip9933.appupdater

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

enum class UpdateError {
    NETWORK,
    INVALID_RELEASE,
    DOWNLOAD_FAILED,
    SIZE_MISMATCH,
    HASH_MISMATCH,
    INVALID_APK,
    WRONG_PACKAGE,
    DEBUG_APK,
    INCOMPATIBLE_SDK,
    OLD_VERSION,
    VERSION_MISMATCH,
    SIGNATURE_MISMATCH,
    STORAGE,
    CANCELLED,
}

data class UpdateState(
    val automaticChecks: Boolean = true,
    val wifiDownload: Boolean = false,
    val channel: AndroidUpdateChannel = AndroidUpdateChannel.STABLE,
    val available: AndroidUpdateRelease? = null,
    val offer: Boolean = false,
    val checking: Boolean = false,
    val downloading: Boolean = false,
    val bytesDownloaded: Long = 0,
    val ready: Boolean = false,
    val error: UpdateError? = null,
    val lastChecked: Long = 0,
    val installedVersion: String = "",
)

/** Public-release update state. Install launch and permission decisions remain in the foreground UI. */
class UpdateManager internal constructor(
    private val context: Context,
    val config: UpdateConfig,
    private val latestRelease: (
        AndroidUpdateChannel,
    ) -> AndroidUpdateRelease? = { AndroidUpdateFeed(config).latest(it) },
    private val installedVersionName: () -> String = { installedVersionName(context) },
    @Volatile private var registrationReady: Boolean = true,
) {
    private val policy = UpdatePolicy(config)
    private val verifier = UpdateApkVerifier(context, policy)
    private val prefs = context.getSharedPreferences(config.stateName, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operation = Mutex()
    private val generation = AtomicLong(0)
    private val client =
        OkHttpClient
            .Builder()
            .cookieJar(CookieJar.NO_COOKIES)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.MINUTES)
            .build()

    @Volatile private var activeCall: Call? = null

    @Volatile private var activeAutomaticTag: String? = null

    @Volatile private var initialized = false

    private val initialChannel =
        runCatching {
            AndroidUpdateChannel.valueOf(
                prefs.updateString("channel", "STABLE") ?: "STABLE",
            )
        }.getOrDefault(AndroidUpdateChannel.STABLE)
    private val _state =
        MutableStateFlow(
            UpdateState(
                automaticChecks = prefs.updateBoolean("automatic", true),
                wifiDownload = prefs.updateBoolean("wifi", false),
                channel = initialChannel,
                available = savedRelease(initialChannel, installedVersionName()),
                lastChecked = prefs.updateLong("checked", 0L),
                installedVersion = installedVersionName(),
            ),
        )
    val state: StateFlow<UpdateState> = _state

    init {
        _state.value =
            _state.value.copy(
                offer = shouldOffer(_state.value.available),
                error = UpdateError.STORAGE.takeUnless { registrationReady },
            )
    }

    /** Schedule public GitHub checks and refresh a previously verified private-cache APK. */
    fun initialize() {
        if (!registrationReady || initialized) return
        synchronized(this) {
            if (initialized) return
            initialized = true
        }
        scheduleChecks()
        scope.launch {
            restoreReady()
            operation.withLock { pruneCache() }
            scheduleAutomaticDownload()
            checkNow(force = false)
        }
    }

    suspend fun checkNow(force: Boolean = true): Boolean =
        withContext(Dispatchers.IO) {
            if (!registrationReady) return@withContext false
            operation.withLock {
                if (_state.value.downloading) return@withLock false
                if (!force && !_state.value.automaticChecks) return@withLock false
                val now = System.currentTimeMillis()
                if (!force &&
                    now - prefs.updateLong("attempted", 0L) in 0 until CHECK_INTERVAL_MS
                ) {
                    return@withLock false
                }
                prefs.edit().putLong("attempted", now).apply()
                val epoch = generation.get()
                val channel = _state.value.channel
                _state.value = _state.value.copy(checking = true, error = null)
                try {
                    val release =
                        latestRelease(channel)
                            ?.takeIf {
                                policy.validRelease(it) &&
                                    policy.isNewer(
                                        policy.expectedVersion(it.tag).orEmpty(),
                                        _state.value.installedVersion,
                                    )
                            }
                    synchronized(this@UpdateManager) {
                        if (epoch != generation.get()) return@withLock false
                        val old = _state.value.available
                        if (old?.tag != release?.tag) clearReady()
                        saveRelease(release, channel)
                        val checked = System.currentTimeMillis()
                        prefs.edit().putLong("checked", checked).apply()
                        _state.value =
                            _state.value.copy(
                                available = release,
                                offer = shouldOffer(release),
                                ready =
                                    old?.tag == release?.tag && _state.value.ready,
                                lastChecked = checked,
                                error = null,
                            )
                        pruneCache()
                        scheduleAutomaticDownload()
                    }
                } catch (_: IOException) {
                    _state.value = _state.value.copy(error = UpdateError.NETWORK)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    _state.value = _state.value.copy(error = UpdateError.NETWORK)
                } finally {
                    _state.value = _state.value.copy(checking = false)
                }
                true
            }
        }

    suspend fun download() = downloadInternal(automatic = false)

    // Distinct catch branches preserve typed failures and coroutine cancellation for callers.
    @Suppress("CyclomaticComplexMethod")
    private suspend fun downloadInternal(automatic: Boolean) =
        withContext(Dispatchers.IO) {
            if (!registrationReady) return@withContext
            operation.withLock {
                val release = _state.value.available ?: return@withLock
                if (!policy.validRelease(release) || _state.value.ready) return@withLock
                if (automatic && (!_state.value.wifiDownload || !hasWifiTransport())) return@withLock
                val epoch = generation.incrementAndGet()
                activeAutomaticTag = release.tag.takeIf { automatic }
                _state.value = _state.value.copy(downloading = true, bytesDownloaded = 0, ready = false, error = null)
                try {
                    storeDownloadedApk(release, epoch)
                } catch (e: CancellationException) {
                    partFile().delete()
                    if (epoch == generation.get()) _state.value = _state.value.copy(error = UpdateError.CANCELLED)
                    throw e
                } catch (e: UpdateFailure) {
                    partFile().delete()
                    if (epoch == generation.get()) _state.value = _state.value.copy(error = e.kind)
                } catch (_: IOException) {
                    partFile().delete()
                    if (epoch == generation.get()) _state.value = _state.value.copy(error = UpdateError.DOWNLOAD_FAILED)
                } catch (_: Exception) {
                    partFile().delete()
                    if (epoch == generation.get()) _state.value = _state.value.copy(error = UpdateError.INVALID_APK)
                } finally {
                    activeCall = null
                    activeAutomaticTag = null
                    _state.value = _state.value.copy(downloading = false)
                }
            }
        }

    // File, metadata, and generation checks must fail before a ready marker is persisted.
    @Suppress("ThrowsCount")
    private suspend fun storeDownloadedApk(
        release: AndroidUpdateRelease,
        epoch: Long,
    ) {
        clearReady()
        val digest = downloadToPart(release, epoch)
        ensureCurrent(epoch)
        val part = partFile()
        verifier.verify(part, release, digest)?.let { throw UpdateFailure(it) }
        ensureCurrent(epoch)
        val verified = apkFile(release, digest)
        if (verified.isFile) {
            if (verifier.verify(verified, release, digest) != null) throw UpdateFailure(UpdateError.STORAGE)
            part.delete()
        } else if (!part.renameTo(verified)) {
            throw UpdateFailure(UpdateError.STORAGE)
        }
        synchronized(this@UpdateManager) {
            ensureCurrent(epoch)
            if (_state.value.available?.tag != release.tag) throw UpdateFailure(UpdateError.CANCELLED)
            prefs
                .edit()
                .putString("ready_tag", release.tag)
                .putString("ready_sha", digest)
                .apply()
            _state.value = _state.value.copy(ready = true, error = null)
            pruneCache()
        }
    }

    @Synchronized fun cancelDownload() {
        generation.incrementAndGet()
        activeCall?.cancel()
        _state.value = _state.value.copy(downloading = false, bytesDownloaded = 0, error = UpdateError.CANCELLED)
    }

    /** Reverify under the operation lease; caller confirms and launches only in the foreground. */
    suspend fun prepareInstall(): Intent? =
        withContext(Dispatchers.IO) {
            if (!registrationReady) return@withContext null
            operation.withLock {
                val snapshot = _state.value
                val release = snapshot.available ?: return@withLock null
                if (!snapshot.ready || snapshot.downloading || !policy.validRelease(release)) return@withLock null
                val epoch = generation.get()
                val digest = prefs.updateString("ready_sha", null) ?: return@withLock null
                val file = apkFile(release, digest)
                if (prefs.updateString("ready_tag", null) != release.tag || !file.isFile) {
                    _state.value = _state.value.copy(ready = false, error = UpdateError.STORAGE)
                    return@withLock null
                }
                val failure = verifier.verify(file, release, digest)
                synchronized(this@UpdateManager) {
                    if (failure != null || epoch != generation.get() || _state.value.available?.tag != release.tag) {
                        _state.value = _state.value.copy(ready = false, error = failure ?: UpdateError.CANCELLED)
                        return@withLock null
                    }
                    try {
                        val intent =
                            Intent(Intent.ACTION_VIEW)
                                .setDataAndType(
                                    FileProvider.getUriForFile(context, "${context.packageName}.android-updates", file),
                                    "application/vnd.android.package-archive",
                                ).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        prefs
                            .edit()
                            .putString(
                                "handoff_file",
                                file.name,
                            ).putLong("handoff_at", System.currentTimeMillis())
                            .apply()
                        intent
                    } catch (_: IllegalArgumentException) {
                        _state.value = _state.value.copy(error = UpdateError.STORAGE)
                        null
                    }
                }
            }
        }

    fun setAutomaticChecks(enabled: Boolean) {
        prefs.edit().putBoolean("automatic", enabled).apply()
        _state.value = _state.value.copy(automaticChecks = enabled)
        scheduleChecks()
    }

    fun setWifiDownload(enabled: Boolean) {
        prefs.edit().putBoolean("wifi", enabled).apply()
        _state.value = _state.value.copy(wifiDownload = enabled)
        if (enabled) {
            scheduleAutomaticDownload()
        } else {
            WorkManager.getInstance(context).cancelUniqueWork(AUTO_DOWNLOAD_WORK_NAME)
            if (activeAutomaticTag != null) cancelDownload()
        }
    }

    @Synchronized fun setChannel(channel: AndroidUpdateChannel) {
        if (_state.value.channel == channel) return
        cancelDownload()
        WorkManager.getInstance(context).cancelUniqueWork(AUTO_DOWNLOAD_WORK_NAME)
        prefs
            .edit()
            .putString("channel", channel.name)
            .remove("release_tag")
            .remove("ready_tag")
            .remove("ready_sha")
            .putLong("checked", 0)
            .putLong("attempted", 0)
            .apply()
        _state.value =
            _state.value.copy(
                channel = channel,
                available = null,
                offer = false,
                ready = false,
                error = null,
                lastChecked = 0,
            )
        val epoch = generation.get()
        scope.launch {
            operation.withLock {
                if (generation.get() == epoch) {
                    clearReady()
                    pruneCache()
                }
            }
        }
    }

    fun dismissOffer() {
        val tag = _state.value.available?.tag ?: return
        prefs.edit().putString("dismissed_tag", tag).apply()
        _state.value = _state.value.copy(offer = false)
    }

    private fun scheduleChecks() {
        if (!registrationReady) return
        val work = WorkManager.getInstance(context)
        if (!_state.value.automaticChecks) {
            work.cancelUniqueWork(WORK_NAME)
            return
        }
        val request =
            PeriodicWorkRequestBuilder<UpdateWorker>(24, TimeUnit.HOURS)
                .setInputData(workDataOf("config_id" to configId()))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
        work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private fun automaticDownloadEnabled(snapshot: UpdateState): Boolean =
        registrationReady && snapshot.wifiDownload && !snapshot.ready

    private fun scheduleAutomaticDownload() {
        val snapshot = _state.value
        val release = snapshot.available
        if (release == null || !automaticDownloadEnabled(snapshot) || !policy.validRelease(release)) {
            // A host upgrade can make a saved release obsolete while its old worker remains queued.
            WorkManager.getInstance(context).cancelUniqueWork(AUTO_DOWNLOAD_WORK_NAME)
            return
        }
        val request =
            OneTimeWorkRequestBuilder<UpdateDownloadWorker>()
                .setInputData(
                    workDataOf(
                        "config_id" to configId(),
                        "channel" to snapshot.channel.name,
                        "tag" to release.tag,
                    ),
                ).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                .build()
        WorkManager.getInstance(context).enqueueUniqueWork(AUTO_DOWNLOAD_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    internal suspend fun automaticDownload(
        channel: AndroidUpdateChannel,
        tag: String,
    ): Boolean {
        if (!registrationReady) return true
        // WorkManager can start this worker in a fresh process before Application.initialize() runs.
        restoreReady()
        val snapshot = _state.value
        val selected = snapshot.channel == channel && snapshot.available?.tag == tag
        return if (!snapshot.wifiDownload || !selected || snapshot.ready) {
            true
        } else if (!hasWifiTransport()) {
            false
        } else {
            downloadInternal(automatic = true)
            _state.value.ready ||
                hasWifiTransport() &&
                _state.value.error != UpdateError.NETWORK &&
                _state.value.error != UpdateError.DOWNLOAD_FAILED
        }
    }

    private suspend fun restoreReady() =
        operation.withLock {
            val release = _state.value.available ?: return@withLock
            val digest = prefs.updateString("ready_sha", null) ?: return@withLock
            val failure =
                if (prefs.updateString("ready_tag", null) !=
                    release.tag
                ) {
                    UpdateError.INVALID_APK
                } else {
                    verifier.verify(apkFile(release, digest), release, digest)
                }
            if (failure != null) {
                clearReady()
                _state.value = _state.value.copy(error = failure)
                return@withLock
            }
            _state.value = _state.value.copy(ready = true)
        }

    // The scoped response keeps its body open through redirect and bounded streaming decisions.
    @Suppress("NestedBlockDepth")
    private suspend fun downloadToPart(
        release: AndroidUpdateRelease,
        epoch: Long,
    ): String {
        var url = release.downloadUrl.toHttpUrlOrNull() ?: throw UpdateFailure(UpdateError.INVALID_RELEASE)
        var redirects = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            ensureCurrent(epoch)
            val call =
                client.newCall(
                    Request
                        .Builder()
                        .url(url)
                        .get()
                        .build(),
                )
            activeCall = call
            call.execute().use { response ->
                if (response.code in 300..399) {
                    if (++redirects > 5) throw UpdateFailure(UpdateError.INVALID_RELEASE)
                    url = safeRedirect(url, response)
                    return@use
                }
                return saveResponseBody(response, release, epoch)
            }
        }
    }

    // Each rejection prevents a redirect outside the trusted release asset hosts.
    @Suppress("ThrowsCount")
    private fun safeRedirect(
        from: HttpUrl,
        response: Response,
    ): HttpUrl {
        val location = response.header("Location") ?: throw UpdateFailure(UpdateError.INVALID_RELEASE)
        val target = from.resolve(location) ?: throw UpdateFailure(UpdateError.INVALID_RELEASE)
        if (!policy.validRedirect(target)) throw UpdateFailure(UpdateError.INVALID_RELEASE)
        return target
    }

    // Explicit guards keep malformed or oversized HTTP bodies out of private cache.
    @Suppress("ThrowsCount")
    internal suspend fun saveResponseBody(
        response: Response,
        release: AndroidUpdateRelease,
        epoch: Long,
    ): String {
        if (!response.isSuccessful) throw UpdateFailure(UpdateError.DOWNLOAD_FAILED)
        val body = response.body ?: throw UpdateFailure(UpdateError.DOWNLOAD_FAILED)
        val declaredSize = body.contentLength()
        if (declaredSize >= 0 && declaredSize != release.sizeBytes) throw UpdateFailure(UpdateError.SIZE_MISMATCH)
        if (!updatesDir().isDirectory && !updatesDir().mkdirs()) throw UpdateFailure(UpdateError.STORAGE)
        val digest = MessageDigest.getInstance("SHA-256")
        val count =
            partFile().outputStream().buffered().use { output ->
                body.byteStream().use { input -> streamBody(input, output, release.sizeBytes, epoch, digest) }
            }
        if (count != release.sizeBytes) throw UpdateFailure(UpdateError.SIZE_MISMATCH)
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        if (!policy.matchingHash(hex, release.sha256)) throw UpdateFailure(UpdateError.HASH_MISMATCH)
        return hex
    }

    private suspend fun streamBody(
        input: InputStream,
        output: OutputStream,
        limit: Long,
        epoch: Long,
        digest: MessageDigest,
    ): Long {
        var count = 0L
        val bytes = ByteArray(64 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            ensureCurrent(epoch)
            val read = input.read(bytes)
            if (read == -1) break
            count += read
            if (count > limit) throw UpdateFailure(UpdateError.SIZE_MISMATCH)
            output.write(bytes, 0, read)
            digest.update(bytes, 0, read)
            _state.value = _state.value.copy(bytesDownloaded = count)
        }
        return count
    }

    private fun ensureCurrent(epoch: Long) {
        if (epoch != generation.get()) throw UpdateFailure(UpdateError.CANCELLED)
    }

    // The host may intentionally omit this normal permission. Check it before any platform query.
    @SuppressLint("MissingPermission")
    internal fun hasWifiTransport(): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_NETWORK_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        return try {
            val network = manager?.activeNetwork
            val capabilities = if (network != null) manager.getNetworkCapabilities(network) else null
            capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
        } catch (_: SecurityException) {
            false
        }
    }

    private fun shouldOffer(release: AndroidUpdateRelease?): Boolean =
        release != null && prefs.updateString("dismissed_tag", null) != release.tag

    private fun updatesDir() = File(context.cacheDir, "updates")

    private fun partFile() = File(updatesDir(), "update.part")

    internal fun apkFile(
        release: AndroidUpdateRelease,
        digest: String,
    ): File {
        val identity = "${release.tag}\u0000$digest".toByteArray(Charsets.UTF_8)
        val nameHash =
            MessageDigest
                .getInstance("SHA-256")
                .digest(identity)
                .joinToString("") { "%02x".format(it) }
        return File(updatesDir(), "update-$nameHash.apk")
    }

    private fun clearReady() {
        prefs
            .edit()
            .remove("ready_tag")
            .remove("ready_sha")
            .apply()
        partFile().delete()
        _state.value = _state.value.copy(ready = false, bytesDownloaded = 0)
    }

    /** Keep the verified candidate and one recently handed-off or downloaded predecessor. */
    internal fun pruneCache() {
        val files = updatesDir().listFiles()?.filter { it.isFile && APK_CACHE_NAME.matches(it.name) }.orEmpty()
        val now = System.currentTimeMillis()
        val release = _state.value.available
        val readyName =
            if (release != null && prefs.updateString("ready_tag", null) == release.tag) {
                prefs
                    .updateString(
                        "ready_sha",
                        null,
                    )?.takeIf { HASH_PATTERN.matches(it) }
                    ?.let { apkFile(release, it).name }
            } else {
                null
            }
        val handoffName =
            prefs
                .updateString("handoff_file", null)
                ?.takeIf {
                    APK_CACHE_NAME.matches(it) &&
                        now - prefs.updateLong("handoff_at", 0L) in 0 until CACHE_HANDOFF_WINDOW_MS
                }
        val keep = mutableSetOf<String>()
        if (readyName != null) keep += readyName
        if (handoffName != null) keep += handoffName
        files
            .sortedByDescending(File::lastModified)
            .firstOrNull {
                it.name !in keep &&
                    now - it.lastModified() in 0 until CACHE_HANDOFF_WINDOW_MS
            }?.takeIf { keep.size < 2 }
            ?.let { keep += it.name }
        files.filter { it.name !in keep }.forEach(File::delete)
    }

    // Saved metadata is untrusted after process death; every missing or invalid field aborts restoration.
    @Suppress("ReturnCount")
    private fun savedRelease(
        channel: AndroidUpdateChannel,
        installedVersion: String,
    ): AndroidUpdateRelease? {
        if (prefs.updateString("release_channel", null) != channel.name) return null
        val release =
            AndroidUpdateRelease(
                prefs.updateString("release_tag", null) ?: return null,
                prefs.updateString("release_notes", "").orEmpty(),
                prefs.updateString("release_asset", null) ?: return null,
                prefs.updateString("release_url", null) ?: return null,
                prefs.updateLong("release_size", 0),
                prefs.updateString("release_sha", null),
                prefs.updateString("release_published", null),
            )
        return release.takeIf {
            policy.validRelease(it) &&
                policy.isNewer(policy.expectedVersion(it.tag).orEmpty(), installedVersion)
        }
    }

    private fun saveRelease(
        release: AndroidUpdateRelease?,
        channel: AndroidUpdateChannel,
    ) {
        val editor = prefs.edit().putString("release_channel", channel.name)
        if (release == null) {
            listOf(
                "release_tag",
                "release_notes",
                "release_asset",
                "release_url",
                "release_size",
                "release_sha",
                "release_published",
            ).forEach(editor::remove)
        } else {
            editor
                .putString("release_tag", release.tag)
                .putString("release_notes", release.notes)
                .putString("release_asset", release.assetName)
                .putString("release_url", release.downloadUrl)
                .putLong("release_size", release.sizeBytes)
                .putString("release_sha", release.sha256)
                .putString("release_published", release.publishedAt)
        }
        editor.apply()
    }

    internal fun automaticChecksEnabled(): Boolean = _state.value.automaticChecks

    internal fun matchesWorkConfig(id: String?): Boolean = id != null && id == configId()

    private fun configId(): String = configIdentity(config)

    companion object {
        private const val WORK_NAME = "android-public-release-check"
        private const val AUTO_DOWNLOAD_WORK_NAME = "android-public-release-wifi-download"
        private const val CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
        private const val CACHE_HANDOFF_WINDOW_MS = 24L * 60 * 60 * 1000
        private val APK_CACHE_NAME = Regex("update-[0-9a-f]{64}\\.apk")
        private val HASH_PATTERN = Regex("[0-9a-fA-F]{64}")

        @Volatile private var instance: UpdateManager? = null

        /** Registers the app's sole update source before any workers are scheduled. */
        fun get(
            context: Context,
            config: UpdateConfig,
        ): UpdateManager =
            synchronized(this) {
                val appContext = context.applicationContext
                val current = instance
                require(
                    current == null || current.config == config,
                ) { "Only one updater configuration is allowed per process" }
                val registered =
                    try {
                        registerBootstrap(appContext, config, current == null || !current.registrationReady)
                        true
                    } catch (_: Exception) {
                        false
                    }
                if (!registered) {
                    val disabled = current ?: UpdateManager(appContext, config, registrationReady = false)
                    disabled.registrationReady = false
                    disabled._state.value = disabled._state.value.copy(error = UpdateError.STORAGE, ready = false)
                    instance = disabled
                    return@synchronized disabled
                }
                if (current != null && current.registrationReady) return@synchronized current
                UpdateManager(appContext, config).also { instance = it }
            }

        private fun registerBootstrap(
            context: Context,
            config: UpdateConfig,
            mayMigrate: Boolean,
        ) {
            val saved = readBootstrap(context)
            val bootstrap = bootstrapPrefs(context)
            val hostVersionCode = installedVersionCode(context)
            val codeChanged = bootstrap.all["host_version_code"] != hostVersionCode
            if (saved != config && mayMigrate && bootstrap.all.isNotEmpty()) {
                discardOldConfiguration(context, saved?.stateName, config.stateName)
            }
            if (saved != config || codeChanged) persistBootstrap(bootstrap, config, hostVersionCode)
        }

        /** Workers in a fresh process only run after a valid app configuration was persisted. */
        internal fun getForWorker(context: Context): UpdateManager? =
            synchronized(this) {
                val appContext = context.applicationContext
                val hostVersionCode = installedVersionCode(appContext) ?: return@synchronized null
                if (bootstrapPrefs(appContext).all["host_version_code"] != hostVersionCode) return@synchronized null
                val config = readBootstrap(appContext) ?: return@synchronized null
                val current = instance
                if (current != null) {
                    return@synchronized current.takeIf { it.config == config }
                }
                UpdateManager(appContext, config).also { instance = it }
            }

        private fun bootstrapPrefs(context: Context): SharedPreferences =
            context.getSharedPreferences("io.github.phillip9933.appupdater.bootstrap", Context.MODE_PRIVATE)

        // Each early exit rejects one corrupt or incomplete persisted field before workers run.
        @Suppress("ReturnCount")
        private fun readBootstrap(context: Context): UpdateConfig? {
            val values = bootstrapPrefs(context).all
            if (values["version"] != 1) return null
            val owner = values["owner"] as? String ?: return null
            val repository = values["repository"] as? String ?: return null
            val tagPrefix = values["tag_prefix"] as? String ?: return null
            val assetPrefix = values["asset_prefix"] as? String ?: return null
            val stateName = values["state_name"] as? String ?: return null
            val legacyAssetPrefix = values["legacy_asset_prefix"] as? String ?: return null
            val legacyVersions =
                (values["legacy_versions"] as? Set<*>)
                    ?.takeIf { it.all { value -> value is String } }
                    ?.filterIsInstance<String>()
                    ?.toSet() ?: return null
            return runCatching {
                UpdateConfig(
                    owner = owner,
                    repository = repository,
                    tagPrefix = tagPrefix,
                    assetPrefix = assetPrefix,
                    legacyVersions = legacyVersions,
                    legacyAssetPrefix = legacyAssetPrefix,
                    stateName = stateName,
                )
            }.getOrNull()
        }

        private fun persistBootstrap(
            prefs: SharedPreferences,
            config: UpdateConfig,
            hostVersionCode: Long?,
        ) {
            val editor =
                prefs
                    .edit()
                    .clear()
                    .putInt("version", 1)
                    .putString("owner", config.owner)
                    .putString("repository", config.repository)
                    .putString("tag_prefix", config.tagPrefix)
                    .putString("asset_prefix", config.assetPrefix)
                    .putString("state_name", config.stateName)
                    .putString("legacy_asset_prefix", config.legacyAssetPrefix)
                    .putStringSet("legacy_versions", config.legacyVersions.toSet())
            if (hostVersionCode != null) editor.putLong("host_version_code", hostVersionCode)
            check(
                editor.commit(),
            ) { "Could not persist updater configuration" }
        }

        @Suppress("DEPRECATION")
        private fun installedVersionCode(context: Context): Long? =
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    context.packageManager
                        .getPackageInfo(
                            context.packageName,
                            PackageManager.PackageInfoFlags.of(0),
                        ).longVersionCode
                } else {
                    val info = context.packageManager.getPackageInfo(context.packageName, 0)
                    if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
                }
            } catch (_: Exception) {
                null
            }

        private fun discardOldConfiguration(
            context: Context,
            oldStateName: String?,
            newStateName: String,
        ) {
            runCatching {
                val work = WorkManager.getInstance(context)
                work.cancelUniqueWork(WORK_NAME)
                work.cancelUniqueWork(AUTO_DOWNLOAD_WORK_NAME)
            }
            val handoffs = mutableSetOf<String>()
            setOfNotNull(oldStateName, newStateName).forEach { name ->
                val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                prefs
                    .updateString("handoff_file", null)
                    ?.takeIf {
                        APK_CACHE_NAME.matches(it) &&
                            System.currentTimeMillis() - prefs.updateLong("handoff_at", 0L) in
                            0 until CACHE_HANDOFF_WINDOW_MS
                    }?.let(handoffs::add)
                check(
                    prefs
                        .edit()
                        .remove("release_channel")
                        .remove("release_tag")
                        .remove("release_notes")
                        .remove("release_asset")
                        .remove("release_url")
                        .remove("release_size")
                        .remove("release_sha")
                        .remove("release_published")
                        .remove("ready_tag")
                        .remove("ready_sha")
                        .remove("dismissed_tag")
                        .remove("attempted")
                        .remove("checked")
                        .commit(),
                ) { "Could not clear stale updater state" }
            }
            val directory = File(context.cacheDir, "updates")
            directory.resolve("update.part").delete()
            directory
                .listFiles()
                ?.filter { it.isFile && APK_CACHE_NAME.matches(it.name) && it.name !in handoffs }
                ?.forEach(File::delete)
        }

        private fun configIdentity(config: UpdateConfig): String {
            val fields =
                listOf(
                    config.owner,
                    config.repository,
                    config.tagPrefix,
                    config.assetPrefix,
                    config.legacyAssetPrefix,
                    config.stateName,
                ) + config.legacyVersions.sorted()
            val bytes = fields.joinToString("\u0000").toByteArray(Charsets.UTF_8)
            return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        }

        /** Simulates process death in unit tests while leaving the persisted bootstrap intact. */
        internal fun clearProcessInstanceForTests() = synchronized(this) { instance = null }

        private fun installedVersionName(context: Context): String =
            try {
                @Suppress("DEPRECATION")
                if (Build.VERSION.SDK_INT >=
                    33
                ) {
                    context.packageManager
                        .getPackageInfo(
                            context.packageName,
                            PackageManager.PackageInfoFlags.of(0),
                        ).versionName
                        .orEmpty()
                } else {
                    context.packageManager
                        .getPackageInfo(context.packageName, 0)
                        .versionName
                        .orEmpty()
                }
            } catch (_: Exception) {
                ""
            }
    }
}

internal class UpdateFailure(
    val kind: UpdateError,
) : RuntimeException()

// Updater metadata is non-sensitive and disposable; malformed values must not block app startup.
private fun SharedPreferences.updateString(
    key: String,
    fallback: String?,
): String? = all[key] as? String ?: fallback

private fun SharedPreferences.updateBoolean(
    key: String,
    fallback: Boolean,
): Boolean = all[key] as? Boolean ?: fallback

private fun SharedPreferences.updateLong(
    key: String,
    fallback: Long,
): Long = all[key] as? Long ?: fallback
