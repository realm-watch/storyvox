package `in`.jphe.storyvox.playback.voice

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.annotation.VisibleForTesting
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
    @VisibleForTesting internal var http: OkHttpClient = OkHttpClient.Builder().build()
    private val migrationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // One-shot migration for users who installed v0.4.5–v0.4.13 with
        // `piper_*_int8`-suffixed IDs. The suffix was kept across the
        // INT8→fp32 catalog repoint to preserve persisted state, but it's
        // misleading (the files have been fp32 since v0.4.12). Strip it
        // from DataStore + rename matching voice directories.
        migrationScope.launch { migrateInt8VoiceIds() }
    }

    private suspend fun migrateInt8VoiceIds() {
        // 1. Filesystem: voices/{id}_int8/ → voices/{id}/. Track which IDs
        //    we successfully migrated so we don't rewrite DataStore for an
        //    ID whose directory we couldn't actually move (e.g. target
        //    already exists from a previous interrupted migration, or
        //    File.renameTo returns false on this filesystem). The
        //    [normalizeId] reads tolerate either form, so a partial
        //    migration is still functional — but we want DataStore to
        //    eventually agree with the on-disk truth.
        val migratedIds = mutableSetOf<String>()
        val voicesRoot = File(context.filesDir, "voices")
        if (voicesRoot.exists()) {
            voicesRoot.listFiles()?.forEach { dir ->
                if (dir.isDirectory && dir.name.endsWith("_int8")) {
                    val newName = dir.name.removeSuffix("_int8")
                    val newDir = File(voicesRoot, newName)
                    when {
                        newDir.exists() -> {
                            // New-form dir already there; treat the rename
                            // as logically done. Any duplicate files on the
                            // old path are dead weight but harmless.
                            migratedIds += newName
                        }
                        dir.renameTo(newDir) -> migratedIds += newName
                        // else: rename failed — leave both DataStore and
                        // disk in legacy state. Reads still resolve.
                    }
                }
            }
        }
        // 2. DataStore: only rewrite the legacy IDs whose directory we
        //    actually moved (or that have no per-voice directory at all,
        //    i.e. Kokoro entries). Anything else stays as `_int8` so
        //    voiceDirFor(id) still resolves.
        store.edit { prefs ->
            prefs[VoiceKeys.ACTIVE_ID]?.let { active ->
                val stripped = active.removeSuffix("_int8")
                val isLegacy = active.endsWith("_int8")
                val isKokoro = stripped.startsWith("kokoro_")
                if (isLegacy && (isKokoro || stripped in migratedIds)) {
                    prefs[VoiceKeys.ACTIVE_ID] = stripped
                }
            }
            prefs[VoiceKeys.INSTALLED_IDS]?.let { ids ->
                val rewritten = ids.map {
                    val stripped = it.removeSuffix("_int8")
                    val isLegacy = it.endsWith("_int8")
                    val isKokoro = stripped.startsWith("kokoro_")
                    if (isLegacy && (isKokoro || stripped in migratedIds)) stripped else it
                }.toSet()
                if (rewritten != ids) prefs[VoiceKeys.INSTALLED_IDS] = rewritten
            }
        }
    }

    /** Catalog projected as [UiVoiceInfo]. Static — never changes at runtime. */
    val availableVoices: List<UiVoiceInfo>
        get() = VoiceCatalog.voices.map { it.toUiVoiceInfo(installed = false) }

    /** Hot Flow of installed voices, derived from the DataStore-backed installed-id set.
     *  All 53 Kokoro speakers report installed once the shared model has been
     *  downloaded — they all share one set of files, the speaker id is just
     *  metadata baked into a generate() call.
     *
     *  Reads `_int8`-suffixed legacy IDs through [normalizeId] so users
     *  upgrading from v0.4.5–v0.4.13 don't briefly see "no voices installed"
     *  if the async [migrateInt8VoiceIds] hasn't completed by the time the
     *  first collector observes this Flow. */
    val installedVoices: Flow<List<UiVoiceInfo>> = store.data.map { prefs ->
        val installedIds = prefs[VoiceKeys.INSTALLED_IDS].orEmpty().map(::normalizeId).toSet()
        val kokoroReady = isKokoroSharedModelInstalled()
        VoiceCatalog.voices
            .filter { it.id in installedIds || (it.engineType is EngineType.Kokoro && kokoroReady) }
            .map { it.toUiVoiceInfo(installed = true) }
    }

    /** Hot Flow of the active voice (or null if nothing chosen yet).
     *  Same legacy-ID normalization as [installedVoices]. */
    val activeVoice: Flow<UiVoiceInfo?> = store.data.map { prefs ->
        val activeId = prefs[VoiceKeys.ACTIVE_ID]?.let(::normalizeId) ?: return@map null
        val installed = prefs[VoiceKeys.INSTALLED_IDS].orEmpty().map(::normalizeId).toSet()
        val entry = VoiceCatalog.byId(activeId) ?: return@map null
        val isInstalled = activeId in installed ||
            (entry.engineType is EngineType.Kokoro && isKokoroSharedModelInstalled())
        entry.toUiVoiceInfo(installed = isInstalled)
    }

    /** Strip the historical `_int8` suffix so legacy stored IDs resolve
     *  against the current catalog. Used on every read; the async migration
     *  rewrites the persisted store eventually but reads must succeed before
     *  it lands. */
    private fun normalizeId(id: String): String =
        if (id.endsWith("_int8")) id.removeSuffix("_int8") else id

    private fun isKokoroSharedModelInstalled(): Boolean {
        val dir = kokoroSharedDir()
        return File(dir, "model.onnx").exists() &&
            File(dir, "voices.bin").exists() &&
            File(dir, "tokens.txt").exists()
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
            is EngineType.Azure -> {
                // Cloud voice — nothing to download. The "install" step
                // for an Azure voice is BYOK key entry in Settings, which
                // PR-3 wires up. PR-4 lights the activation path.
                //
                // Currently this branch is a guarded no-op: VoiceManager
                // surfaces Azure entries in `availableVoices` (so they
                // appear greyed out in the picker per Solara's spec) but
                // never reports them as installed and refuses to "download"
                // them. Calling code in this PR shouldn't reach here yet
                // — PR-4 will replace this guard with the real activation
                // flow.
                error("Azure voices have no downloadable assets — " +
                    "credential-keyed activation arrives in PR-4. (#85)")
            }
            is EngineType.Kokoro -> {
                // Kokoro speakers all share one ~168MB multi-speaker model
                // (model.onnx + tokens.txt + voices.bin). The first
                // Kokoro pick downloads it; every subsequent one just flips
                // the active speaker id with no additional payload.
                val sharedDir = kokoroSharedDir()
                val onnxFile = File(sharedDir, "model.onnx")
                val tokensFile = File(sharedDir, "tokens.txt")
                val voicesFile = File(sharedDir, "voices.bin")
                if (!onnxFile.exists() || !voicesFile.exists() || !tokensFile.exists()) {
                    sharedDir.mkdirs()
                    try {
                        // Kokoro v1_1 fp32 sizes (vs voices-v1's int8: 114M / 53M).
                        // Total ~379MB on first install — bigger than int8 but
                        // produces clean speech (no quantization fuzz).
                        downloadFile(
                            url = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/kokoro-model.onnx",
                            target = onnxFile,
                            knownTotalBytes = 325_631_784L,
                        ) { read, _ -> emit(DownloadProgress.Downloading(read, 379_423_615L)) }
                        downloadFile(
                            url = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/kokoro-voices.bin",
                            target = voicesFile,
                            knownTotalBytes = 53_790_720L,
                        ) { read, _ ->
                            // Continue progress where the model left off so the bar keeps moving.
                            emit(DownloadProgress.Downloading(325_631_784L + read, 379_423_615L))
                        }
                        downloadFile(
                            url = "https://github.com/jphein/VoxSherpa-TTS/releases/download/voices-v2/kokoro-tokens.txt",
                            target = tokensFile,
                            knownTotalBytes = 0L,
                        ) { _, _ -> }
                    } catch (ce: CancellationException) {
                        // Caller cancelled (collector dropped, ViewModel
                        // cancelled the job). Re-throw so structured
                        // concurrency sees the cancel — emitting Failed
                        // here would race with the cancel and surface a
                        // phantom error in the UI. Don't wipe the shared
                        // dir: any sibling file that finished before the
                        // cancel (e.g. the 325 MB model.onnx when the user
                        // cancelled mid voices.bin) survives the outer
                        // `if (!exists())` checks above on retry, so only
                        // the still-incomplete target is re-fetched. The
                        // mid-flight file itself is rewritten from byte 0
                        // — `downloadFile()` doesn't do HTTP Range — but
                        // the completed sibling is the big saving.
                        throw ce
                    } catch (t: Throwable) {
                        // Per #28: wipe shared dir on real failure to keep retries deterministic.
                        // Trade-off: re-download cost (≤325 MB) vs. avoiding a corrupted partial onnx.
                        // Cancel path keeps partials (#20/#26) — user expectation.
                        sharedDir.deleteRecursively()
                        emit(DownloadProgress.Failed(t.message ?: t::class.java.simpleName))
                        return@flow
                    }
                }
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
                } catch (ce: CancellationException) {
                    // User-driven cancel. Re-throw to honour structured
                    // concurrency — emitting Failed would surface a
                    // phantom error toast for what was a deliberate
                    // cancel. Skipping the deleteRecursively here is
                    // a no-op net of bytes (downloadFile rewrites the
                    // target from byte 0 on retry — no HTTP Range), but
                    // it keeps the cancel path side-effect-free, which
                    // matches the Kokoro branch's shape.
                    throw ce
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

    /** Shared Kokoro multi-speaker model dir (one install per device, used by all 53 speakers). */
    fun kokoroSharedDir(): File = File(context.filesDir, "voices/_kokoro_shared")

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
