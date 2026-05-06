package `in`.jphe.storyvox.playback.voice

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.sink

private val Context.voicesSettingsStore: DataStore<Preferences> by preferencesDataStore(name = "voices_settings")

private object VoiceKeys {
    val INSTALLED_IDS = stringSetPreferencesKey("installed_voice_ids")
    val ACTIVE_ID = stringPreferencesKey("active_voice_id")
}

/**
 * Owns the user-facing voice library: which voices are downloadable
 * (the [VoiceCatalog]), which are installed on disk, and which one is
 * active for playback.
 *
 * Storage layout under [Context.getFilesDir]:
 * ```
 * voices/
 *   {voiceId}/
 *     model.onnx       (Piper) — model weights
 *     tokens.json      (Piper) — tokens metadata (.onnx.json from huggingface)
 * ```
 * Kokoro entries live entirely in DataStore: a Kokoro selection means
 * "use the shared Kokoro model that the engine has loaded, with this
 * speaker index" — no per-voice file payload. We mark Kokoro voices as
 * installed the moment [setActive] is called for them (or [download]
 * is called — for parity it just emits Done immediately).
 *
 * Why DataStore for state instead of just listing the voices directory?
 * - Survives app restart cleanly without a directory walk.
 * - Stores active selection alongside installed set in one transaction.
 * - Plays nicely with the Flow surface other modules consume.
 */
@Singleton
class VoiceManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val store: DataStore<Preferences> = context.voicesSettingsStore
    private val http: OkHttpClient = OkHttpClient.Builder().build()

    /** Catalog projected as [UiVoiceInfo]. Static — never changes at runtime. */
    val availableVoices: List<UiVoiceInfo>
        get() = VoiceCatalog.voices.map { it.toUiVoiceInfo(installed = false) }

    /** Hot Flow of installed voices, derived from the DataStore-backed installed-id set. */
    val installedVoices: Flow<List<UiVoiceInfo>> = store.data.map { prefs ->
        val installedIds = prefs[VoiceKeys.INSTALLED_IDS].orEmpty()
        VoiceCatalog.voices
            .filter { it.id in installedIds }
            .map { it.toUiVoiceInfo(installed = true) }
    }

    /** Hot Flow of the active voice (or null if nothing chosen yet). */
    val activeVoice: Flow<UiVoiceInfo?> = store.data.map { prefs ->
        val activeId = prefs[VoiceKeys.ACTIVE_ID] ?: return@map null
        val installed = prefs[VoiceKeys.INSTALLED_IDS].orEmpty()
        VoiceCatalog.byId(activeId)?.toUiVoiceInfo(installed = activeId in installed)
    }

    sealed interface DownloadProgress {
        data object Resolving : DownloadProgress
        data class Downloading(val bytesRead: Long, val totalBytes: Long) : DownloadProgress
        data object Done : DownloadProgress
        data class Failed(val reason: String) : DownloadProgress
    }

    /**
     * Stream a download for [voiceId]. Emits [DownloadProgress.Resolving]
     * before any network activity, then a sequence of [Downloading]
     * frames as bytes land, then a single terminal [Done] or [Failed].
     *
     * Does NOT call [setActive] on completion — callers (the gate, the
     * library UI) decide when activation happens.
     */
    fun download(voiceId: String): Flow<DownloadProgress> = flow {
        emit(DownloadProgress.Resolving)
        val entry = VoiceCatalog.byId(voiceId)
        if (entry == null) {
            emit(DownloadProgress.Failed("Unknown voiceId: $voiceId"))
            return@flow
        }

        when (entry.engineType) {
            is EngineType.Kokoro -> {
                // Kokoro speakers don't carry per-voice payload — selecting
                // one is just persisting the speaker id. Mark installed and
                // emit Done.
                markInstalled(voiceId)
                emit(DownloadProgress.Done)
            }
            EngineType.Piper -> {
                val piper = entry.piper
                if (piper == null) {
                    emit(DownloadProgress.Failed("Piper entry $voiceId has no URLs"))
                    return@flow
                }
                val voiceDir = voiceDirFor(voiceId).also { it.mkdirs() }
                try {
                    downloadFile(
                        url = piper.onnxUrl,
                        target = File(voiceDir, "model.onnx"),
                        knownTotalBytes = entry.sizeBytes,
                    ) { bytesRead, total -> emit(DownloadProgress.Downloading(bytesRead, total)) }
                    downloadFile(
                        url = piper.tokensUrl,
                        target = File(voiceDir, "tokens.txt"),
                        knownTotalBytes = 0L,
                    ) { _, _ -> /* tokens file is small (~1KB) — no per-byte tick */ }
                } catch (t: Throwable) {
                    voiceDir.deleteRecursively()
                    emit(DownloadProgress.Failed(t.message ?: t::class.java.simpleName))
                    return@flow
                }
                markInstalled(voiceId)
                emit(DownloadProgress.Done)
            }
        }
    }.flowOn(Dispatchers.IO)

    /** Remove a voice's files (Piper) and unmark it installed. If the
     *  active voice is being deleted, the active selection is cleared. */
    suspend fun delete(voiceId: String) {
        withContext(Dispatchers.IO) {
            voiceDirFor(voiceId).deleteRecursively()
        }
        store.edit { prefs ->
            val ids = prefs[VoiceKeys.INSTALLED_IDS].orEmpty().toMutableSet()
            ids.remove(voiceId)
            prefs[VoiceKeys.INSTALLED_IDS] = ids
            if (prefs[VoiceKeys.ACTIVE_ID] == voiceId) prefs.remove(VoiceKeys.ACTIVE_ID)
        }
    }

    /** Persist [voiceId] as the user's active voice. Does not validate
     *  installation — the picker UI is responsible for downloading first. */
    suspend fun setActive(voiceId: String) {
        store.edit { prefs -> prefs[VoiceKeys.ACTIVE_ID] = voiceId }
    }

    /**
     * Filesystem path for a voice's installed payload. Public so the
     * playback layer can hand the .onnx + tokens.json to VoiceEngine
     * when loading a Piper model. For Kokoro entries the directory
     * isn't used (returns the path anyway — never created).
     */
    fun voiceDirFor(voiceId: String): File = File(File(context.filesDir, "voices"), voiceId)

    // ----- internals -----

    private suspend fun markInstalled(voiceId: String) {
        store.edit { prefs ->
            val ids = prefs[VoiceKeys.INSTALLED_IDS].orEmpty().toMutableSet()
            ids.add(voiceId)
            prefs[VoiceKeys.INSTALLED_IDS] = ids
        }
    }

    private suspend inline fun downloadFile(
        url: String,
        target: File,
        knownTotalBytes: Long,
        crossinline onProgress: suspend (bytesRead: Long, totalBytes: Long) -> Unit,
    ) {
        val request = Request.Builder().url(url).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} fetching $url")
            }
            val body = response.body ?: throw IOException("Empty body for $url")
            val totalBytes = body.contentLength().takeIf { it > 0 } ?: knownTotalBytes
            target.sink().buffer().use { sink ->
                body.source().use { source ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = source.read(buf)
                        if (n == -1) break
                        sink.write(buf, 0, n)
                        read += n
                        onProgress(read, totalBytes)
                    }
                    sink.flush()
                }
            }
        }
    }

    private fun CatalogEntry.toUiVoiceInfo(installed: Boolean): UiVoiceInfo = UiVoiceInfo(
        id = id,
        displayName = displayName,
        language = language,
        sizeBytes = sizeBytes,
        isInstalled = installed,
        qualityLevel = qualityLevel,
        engineType = engineType,
    )
}
