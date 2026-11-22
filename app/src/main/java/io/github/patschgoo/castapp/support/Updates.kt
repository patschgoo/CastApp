package io.github.patschgoo.castapp.support

import io.github.patschgoo.castapp.Txt
import io.github.patschgoo.castapp.R
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import io.github.patschgoo.castapp.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** What the user sees of an update check. */
sealed interface UpdateUi {
    data object Checking : UpdateUi
    data class UpToDate(val version: String) : UpdateUi
    data class Available(val release: ReleaseInfo) : UpdateUi
    /** GitHub answered 404: no release has been published yet, or the repository is private. */
    data object NoRelease : UpdateUi
    data class Failed(val reason: String) : UpdateUi
    data class Downloading(val release: ReleaseInfo, val done: Long, val total: Long) : UpdateUi
    data class NeedsInstallPermission(val release: ReleaseInfo) : UpdateUi
}

/** Outcome of asking GitHub for the latest release (before it becomes UI). */
sealed interface CheckResult {
    data class UpToDate(val version: String) : CheckResult
    data class Newer(val release: ReleaseInfo) : CheckResult
    data object NoRelease : CheckResult
    data class Failed(val reason: String) : CheckResult
}

/**
 * Update check for an app that is installed from GitHub instead of a store.
 * Only api.github.com and github.com (plus the download servers it redirects to)
 * are contacted, and nothing about the user or the phone is sent.
 */
object Updates {
    const val OWNER = "patschgoo"
    const val REPO = "CastApp"
    const val LATEST_URL = "https://api.github.com/repos/$OWNER/$REPO/releases/latest"
    const val RELEASES_PAGE = "https://github.com/$OWNER/$REPO/releases"
    private const val DAY_MS = 24L * 60 * 60 * 1000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _ui = MutableStateFlow<UpdateUi?>(null)
    /** The dialog state; null when nothing is to be shown. */
    val ui: StateFlow<UpdateUi?> = _ui.asStateFlow()

    fun dismiss(skipVersion: Boolean = false, context: Context? = null) {
        val current = _ui.value
        if (skipVersion && context != null && current is UpdateUi.Available) {
            SupportPrefs.of(context).edit().putString(SupportPrefs.SKIPPED_VERSION, current.release.tag).apply()
        }
        _ui.value = null
    }

    /** Pure: turns GitHub's answer into a result. */
    fun interpret(status: Int, body: String?, currentVersion: String): CheckResult = when {
        status == 404 -> CheckResult.NoRelease
        status == 403 || status == 429 -> CheckResult.Failed(Txt.s(R.string.err_http_limit, status))
        status !in 200..299 || body == null -> CheckResult.Failed(Txt.s(R.string.err_http_error, status))
        else -> {
            val release = ReleaseInfo.parse(body)
            when {
                release == null -> CheckResult.Failed(Txt.s(R.string.err_github_unclear))
                Versions.isNewer(release.version, currentVersion) -> CheckResult.Newer(release)
                else -> CheckResult.UpToDate(currentVersion)
            }
        }
    }

    /** Asks GitHub for the latest release. Blocking; call on a background thread. */
    fun fetchLatest(currentVersion: String = BuildConfig.VERSION_NAME): CheckResult = try {
        val conn = URL(LATEST_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "CastApp-update-check")
        try {
            val status = conn.responseCode
            val body = if (status in 200..299) conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } else null
            interpret(status, body, currentVersion)
        } finally {
            conn.disconnect()
        }
    } catch (e: Exception) {
        CheckResult.Failed(Txt.s(R.string.err_github_unreachable, e.message ?: e.javaClass.simpleName))
    }

    /** "Check for updates": always asks, and always tells the user the answer. */
    fun checkNow(context: Context) {
        val app = context.applicationContext
        _ui.value = UpdateUi.Checking
        scope.launch {
            SupportPrefs.of(app).edit().putLong(SupportPrefs.LAST_UPDATE_CHECK, System.currentTimeMillis()).apply()
            _ui.value = when (val r = fetchLatest()) {
                is CheckResult.Newer -> UpdateUi.Available(r.release)
                is CheckResult.UpToDate -> UpdateUi.UpToDate(r.version)
                CheckResult.NoRelease -> UpdateUi.NoRelease
                is CheckResult.Failed -> UpdateUi.Failed(r.reason)
            }
        }
    }

    /** On app start: at most once a day, only if enabled, and only speaks up when there is a new version. */
    fun checkOnStart(context: Context) {
        val app = context.applicationContext
        val prefs = SupportPrefs.of(app)
        if (!prefs.getBoolean(SupportPrefs.AUTO_UPDATE_CHECK, true)) return
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(SupportPrefs.LAST_UPDATE_CHECK, 0L) < DAY_MS) return
        prefs.edit().putLong(SupportPrefs.LAST_UPDATE_CHECK, now).apply()
        scope.launch {
            val r = fetchLatest() as? CheckResult.Newer ?: return@launch
            if (prefs.getString(SupportPrefs.SKIPPED_VERSION, null) == r.release.tag) return@launch
            if (_ui.value == null) _ui.value = UpdateUi.Available(r.release)
        }
    }

    /** Downloads CastApp.apk of [release] to the cache and starts the installer. */
    fun downloadAndInstall(context: Context, release: ReleaseInfo) {
        val app = context.applicationContext
        val url = release.apkUrl ?: return
        if (Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {
            _ui.value = UpdateUi.NeedsInstallPermission(release)
            return
        }
        _ui.value = UpdateUi.Downloading(release, 0, release.apkSize)
        scope.launch {
            try {
                val file = download(app, url, release)
                _ui.value = null
                app.startActivity(installIntent(app, file))
            } catch (e: Exception) {
                _ui.value = UpdateUi.Failed(Txt.s(R.string.err_update_failed, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    /** Opens the system page where the user allows CastApp to install apps (Android 8+). */
    fun openInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { context.startActivity(intent) } catch (_: Exception) {}
    }

    private fun download(context: Context, url: String, release: ReleaseInfo): File {
        val dir = File(context.cacheDir, "support/updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, ReleaseInfo.APK_NAME)
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "CastApp-update-check")
        try {
            if (conn.responseCode !in 200..299) error(Txt.s(R.string.err_update_server, conn.responseCode))
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
            var done = 0L
            var lastReport = 0L
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastReport > 256 * 1024) {
                            lastReport = done
                            _ui.value = UpdateUi.Downloading(release, done, total)
                        }
                    }
                }
            }
            if (done == 0L) error(Txt.s(R.string.err_update_empty))
        } finally {
            conn.disconnect()
        }
        return file
    }

    fun fileUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, SupportFiles.authority(context), file)

    private fun installIntent(context: Context, file: File): Intent =
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(fileUri(context, file), "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
}
