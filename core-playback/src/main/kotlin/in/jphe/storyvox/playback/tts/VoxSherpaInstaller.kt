package `in`.jphe.storyvox.playback.tts

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.playback.BuildConfig
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink

/**
 * In-app VoxSherpa downloader and installer.
 *
 * The user still confirms each install (Android requires user-presence for
 * any non-system-app install since N), but they don't have to leave storyvox,
 * find a browser, find the right release asset, and re-tap a downloaded file
 * in their notification shade. We resolve `releases/latest` from GitHub,
 * stream the APK into our cache directory with a progress flow the UI can
 * subscribe to, then hand it to Android's package-installer activity via the
 * standard `ACTION_VIEW` intent on a [FileProvider] content URI.
 *
 * Why ACTION_VIEW vs `PackageInstaller` session API: same UX, fewer moving
 * parts. The session API is interesting if we want a foreground notification
 * during install or to chain multiple package operations — neither of which
 * applies here.
 */
@Singleton
class VoxSherpaInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** Outcomes the UI subscribes to. */
    sealed interface Progress {
        data object Resolving : Progress
        data class Downloading(val bytesRead: Long, val totalBytes: Long) : Progress
        data object LaunchingInstaller : Progress
        data class Failed(val reason: String) : Progress
    }

    /** State the gate / settings UI uses to decide what to render. */
    data class EngineState(
        val installed: Boolean,
        val installedVersionName: String?,
        val isUpToDate: Boolean,
    )

    private val client by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** Cheap synchronous probe — used by the gate at composition time. */
    fun engineState(): EngineState {
        val info = runCatching {
            context.packageManager.getPackageInfo(BuildConfig.VOXSHERPA_PACKAGE, 0)
        }.getOrNull()
        val name = info?.versionName
        return EngineState(
            installed = info != null,
            installedVersionName = name,
            isUpToDate = name != null && versionAtLeast(name, BuildConfig.VOXSHERPA_MIN_VERSION),
        )
    }

    /**
     * Resolve the latest release's APK asset, stream it to cache, and hand off
     * to Android's package installer. Each phase emits a [Progress]. The flow
     * terminates after [Progress.LaunchingInstaller] (or [Progress.Failed]) —
     * the actual install is a separate Activity that we have no signal from.
     */
    fun downloadAndInstall(): Flow<Progress> = flow {
        emit(Progress.Resolving)
        val apkUrl = resolveLatestApkUrl() ?: run {
            emit(Progress.Failed("Couldn't find a VoxSherpa release on GitHub."))
            return@flow
        }

        val outFile = File(context.cacheDir, "$INSTALL_SUBDIR/$APK_FILE_NAME").apply {
            parentFile?.mkdirs()
            // Make sure we don't append to a partial download from a prior attempt.
            if (exists()) delete()
        }

        val req = Request.Builder().url(apkUrl).build()
        try {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    emit(Progress.Failed("Download failed (HTTP ${resp.code})"))
                    return@flow
                }
                val body = resp.body ?: run {
                    emit(Progress.Failed("Empty response from GitHub"))
                    return@flow
                }
                val total = body.contentLength()
                emit(Progress.Downloading(0L, total))
                body.source().use { source ->
                    outFile.sink().buffer().use { sink ->
                        val buf = okio.Buffer()
                        var read = 0L
                        while (true) {
                            val n = source.read(buf, BUFFER_BYTES)
                            if (n == -1L) break
                            sink.write(buf, n)
                            read += n
                            emit(Progress.Downloading(read, total))
                        }
                        sink.flush()
                    }
                }
            }
        } catch (e: Exception) {
            emit(Progress.Failed(e.message ?: "Download failed"))
            return@flow
        }

        emit(Progress.LaunchingInstaller)
        try {
            launchInstallIntent(outFile)
        } catch (e: Exception) {
            emit(Progress.Failed("Couldn't launch installer: ${e.message}"))
        }
    }.flowOn(Dispatchers.IO)

    /** GitHub releases API → first asset whose name ends with .apk. */
    private suspend fun resolveLatestApkUrl(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url(BuildConfig.VOXSHERPA_LATEST_API)
                .header("Accept", "application/vnd.github+json")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val payload = resp.body?.string() ?: return@withContext null
                val release = json.decodeFromString<Release>(payload)
                release.assets.firstOrNull { it.name.endsWith(".apk") }?.browserDownloadUrl
            }
        }.getOrNull()
    }

    /**
     * Launches Android's uninstall confirmation activity for the existing
     * VoxSherpa package. Used when the user has upstream's signed build
     * installed and we need them to remove it before our fork (signed with
     * a different keystore) can replace it. Android surfaces the standard
     * uninstall UI; we have no callback, the gate just re-probes on resume.
     *
     * Returns true if an activity was launched, false otherwise. The gate
     * uses this to fall back to a "go to Settings → Apps" instruction if
     * the system uninstall flow can't be reached directly.
     */
    fun launchUninstallExisting(): Boolean {
        val pkg = BuildConfig.VOXSHERPA_PACKAGE
        Log.i("VoxSherpaInstaller", "launchUninstallExisting() pkg=$pkg")
        // ACTION_UNINSTALL_PACKAGE is more explicit than ACTION_DELETE and
        // resolves more reliably across OEMs. It's marked deprecated since
        // API 29 in favour of PackageInstaller.uninstall(), but the latter
        // returns the result silently to a PendingIntent we'd have to
        // plumb back; the user-facing UI is the same dialog either way.
        @Suppress("DEPRECATION")
        val primary = Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.fromParts("package", pkg, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (tryStart(primary)) return true

        // Fallback: ACTION_DELETE with the same URI. Older OEM ROMs handle
        // this when ACTION_UNINSTALL_PACKAGE doesn't resolve.
        val fallback = Intent(Intent.ACTION_DELETE, Uri.fromParts("package", pkg, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (tryStart(fallback)) return true

        // Last resort: open the system app-info screen so the user can hit
        // Uninstall manually.
        val appInfo = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", pkg, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return tryStart(appInfo)
    }

    private fun tryStart(intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        Log.w("VoxSherpaInstaller", "startActivity ${intent.action} failed: ${e.message}")
        false
    }

    private fun launchInstallIntent(apk: File) {
        val authority = "${context.packageName}.installs"
        val uri = FileProvider.getUriForFile(context, authority, apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    @Serializable
    private data class Release(val assets: List<Asset> = emptyList())

    @Serializable
    private data class Asset(
        val name: String,
        @SerialName("browser_download_url") val browserDownloadUrl: String,
    )

    private companion object {
        const val INSTALL_SUBDIR = "installs"
        const val APK_FILE_NAME = "voxsherpa-latest.apk"
        const val BUFFER_BYTES = 64L * 1024L
    }
}

/**
 * Returns true when [actual] is >= [minimum] under a tolerant dotted-version
 * comparison. Suffixes after `-` (e.g. `2.6.1-fork`) are ignored. Missing
 * components default to 0, so `2.6` < `2.6.1` < `2.7`.
 */
internal fun versionAtLeast(actual: String, minimum: String): Boolean {
    fun parse(v: String): List<Int> = v.substringBefore('-')
        .split('.')
        .map { it.trim().toIntOrNull() ?: 0 }
    val a = parse(actual)
    val b = parse(minimum)
    val n = maxOf(a.size, b.size)
    for (i in 0 until n) {
        val ai = a.getOrNull(i) ?: 0
        val bi = b.getOrNull(i) ?: 0
        if (ai != bi) return ai > bi
    }
    return true
}
