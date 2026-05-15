package `in`.jphe.storyvox.playback.cache

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PR-H (#86) — read-only inspector over the PCM cache state. Used by
 * the UI layer (FictionDetailViewModel for the per-chapter badge,
 * VoiceLibraryViewModel for the per-voice cached-MB indicator) to
 * surface cache state to the user without coupling the UI to the
 * writer surface of [PcmCache] / [PcmAppender].
 *
 * Why a separate class (vs adding the methods to [PcmCache] directly):
 *  - Keeps the writer surface free of UI-shaped query types. The
 *    `chapterStatesFor` batch shape returns a Map keyed by chapterId —
 *    that's a UI concern, not a cache-internals concern.
 *  - Makes the dependency direction obvious in DI: the inspector
 *    accepts a [PcmCache] but never writes through it. PR-D's tee
 *    appender + PR-F's worker can keep using the writer surface
 *    without seeing the inspector at all.
 *
 * Thread-safety: every method is `withContext(Dispatchers.IO)`-wrapped
 * and reads through [PcmCache]'s already-thread-safe read surface
 * (`isComplete`, `metaFileFor`, `rootDirectory`). Safe to call from any
 * coroutine; the ViewModels' `viewModelScope.launch` is enough.
 *
 * Cost: each call is a small number of `File.exists` syscalls (per-
 * chapter classification) or one directory listing + N tiny JSON
 * parses (per-voice byte sum). For a 5 GB cache that's ≤ 70 entries,
 * single-digit milliseconds on internal flash. No memoisation —
 * `FictionDetailViewModel` recomputes on screen-enter and on voice-
 * swap, `VoiceLibraryViewModel` recomputes on every `installedVoices`
 * emission; the call rate is low enough that a poll loop wouldn't add
 * value.
 *
 * **Pronunciation-dict default.** Cache keys include
 * `pronunciationDictHash` (issue #135) so a dictionary edit invalidates
 * affected entries without a manual sweep. The inspector accepts the
 * caller's current dict hash as a parameter — defaulting to
 * `0` matches `PcmCacheKey`'s historical test usage of the no-dict
 * value. In production the caller passes the live hash from
 * `PronunciationDictRepository.current().contentHash`. For the bulk
 * `bytesUsedByVoice` path the dict hash doesn't matter (we sum across
 * every meta file regardless of dict variant — disk-used is disk-used).
 */
@Singleton
class CacheStateInspector @Inject constructor(
    private val cache: PcmCache,
) {

    /**
     * Classify the cache state of [chapterId] for [voiceId] at the
     * default 1.0× speed / 1.0× pitch under the current chunker version
     * and the caller-supplied pronunciation-dict hash.
     *
     *  - [ChapterCacheState.Complete] — `.idx.json` exists for the key.
     *    Play and the audio is gapless from first byte.
     *  - [ChapterCacheState.Partial] — `.meta.json` exists, `.idx.json`
     *    doesn't. A render is in flight (PR-D's streaming tee or PR-F's
     *    worker) OR a prior render was killed/abandoned before
     *    finalize. UI surfaces as "caching in progress" — replay will
     *    pick up where the previous render left off.
     *  - [ChapterCacheState.None] — no files for this key.
     *
     * The (speed, pitch) tuple is fixed at (100, 100) for the
     * UI-facing badge — a user playing at 1.25× has a separate cache
     * file the badge doesn't see, but the worker (PR-F) renders at
     * 1.0× so the 1.0× state is what the badge most plausibly tracks.
     * See PR-H plan open-question #3 for the decision rationale.
     */
    suspend fun chapterStateFor(
        chapterId: String,
        voiceId: String,
        chunkerVersion: Int,
        pronunciationDictHash: Int = 0,
    ): ChapterCacheState = withContext(Dispatchers.IO) {
        classify(
            key = PcmCacheKey(
                chapterId = chapterId,
                voiceId = voiceId,
                speedHundredths = 100,
                pitchHundredths = 100,
                chunkerVersion = chunkerVersion,
                pronunciationDictHash = pronunciationDictHash,
            ),
        )
    }

    /**
     * Bulk classification for every chapter in [chapterIds] under the
     * same (voice, chunker, dict) tuple. Cheaper than N individual
     * calls because the file dispatch is grouped under a single
     * `withContext(Dispatchers.IO)` so the thread-pool dispatch
     * overhead amortises across the batch.
     *
     * Returns a map keyed by chapterId. Missing entries (no syscalls
     * hit them) map to [ChapterCacheState.None].
     */
    suspend fun chapterStatesFor(
        chapterIds: Collection<String>,
        voiceId: String,
        chunkerVersion: Int,
        pronunciationDictHash: Int = 0,
    ): Map<String, ChapterCacheState> = withContext(Dispatchers.IO) {
        if (chapterIds.isEmpty()) return@withContext emptyMap()
        chapterIds.associateWith { chapterId ->
            classify(
                key = PcmCacheKey(
                    chapterId = chapterId,
                    voiceId = voiceId,
                    speedHundredths = 100,
                    pitchHundredths = 100,
                    chunkerVersion = chunkerVersion,
                    pronunciationDictHash = pronunciationDictHash,
                ),
            )
        }
    }

    /** Synchronous classify for one key. Wrapped in IO dispatch by
     *  every caller; kept private so the dispatcher contract is
     *  consistent at the public surface. */
    private fun classify(key: PcmCacheKey): ChapterCacheState = when {
        cache.isComplete(key) -> ChapterCacheState.Complete
        cache.metaFileFor(key).exists() -> ChapterCacheState.Partial
        else -> ChapterCacheState.None
    }

    /**
     * Sum of `.pcm` file sizes whose `.meta.json` reports
     * `voiceId == voiceId`. O(n) over all cache entries; for a 5 GB
     * cache that's at most ~70 chapters × stat + JSON-parse, single-
     * digit ms on internal flash. Includes Partial (in-flight) renders
     * — `bytesUsed` is "disk consumed by this voice today", not
     * "bytes of fully-cached audio".
     */
    suspend fun bytesUsedByVoice(voiceId: String): Long = withContext(Dispatchers.IO) {
        bytesUsedByEveryVoiceInternal()[voiceId] ?: 0L
    }

    /**
     * Bulk per-voice byte sum. Useful for voice library which has
     * 50+ voices but only a handful with actual cache. Single
     * directory walk + one JSON parse per meta — strictly cheaper
     * than calling [bytesUsedByVoice] in a loop, which would re-walk
     * the directory N times.
     */
    suspend fun bytesUsedByEveryVoice(): Map<String, Long> = withContext(Dispatchers.IO) {
        bytesUsedByEveryVoiceInternal()
    }

    /** Shared body of the two bytes-used paths. Already runs under
     *  [Dispatchers.IO] via its callers; not a suspend fun so the
     *  loop body stays inline-able. */
    private fun bytesUsedByEveryVoiceInternal(): Map<String, Long> {
        val rootDir = cache.rootDirectory()
        val metaFiles = rootDir.listFiles { f -> f.name.endsWith(META_SUFFIX) }
            ?: return emptyMap()
        if (metaFiles.isEmpty()) return emptyMap()
        val byVoice = mutableMapOf<String, Long>()
        for (mf in metaFiles) {
            val meta = runCatching {
                pcmCacheJson.decodeFromString(PcmMeta.serializer(), mf.readText())
            }.getOrNull() ?: continue
            val basename = mf.name.removeSuffix(META_SUFFIX)
            val pcmLen = File(rootDir, "$basename$PCM_SUFFIX").length()
            byVoice[meta.voiceId] = (byVoice[meta.voiceId] ?: 0L) + pcmLen
        }
        return byVoice
    }

    private companion object {
        const val META_SUFFIX = ".meta.json"
        const val PCM_SUFFIX = ".pcm"
    }
}

/**
 * UI-facing classification of one chapter's cache state. Lives in
 * `core-playback` next to [CacheStateInspector] so the inspector's
 * return type and the UI's badge-state type are literally the same
 * value — no feature-layer mapping, no risk of two parallel enums
 * drifting out of sync.
 *
 * `core-ui` pulls `core-playback` as a dependency for this enum
 * (PR-H additive — pre-PR-H core-ui had no core-playback import).
 * Plain enum (no sealed hierarchy) because the three states are flat
 * and add no payload; the badge composable does a `when` on the value
 * and that's it.
 */
enum class ChapterCacheState {
    /** No cache files for this (chapter, voice). Default state.
     *  Badge renders nothing — zero visual footprint. */
    None,

    /** `.meta.json` exists but `.idx.json` doesn't. Either a render
     *  is in flight (PR-D's streaming tee writer, PR-F's WorkManager
     *  pre-render) or a previous render was killed/abandoned.
     *  Either way, replay will resume from the partial bytes —
     *  the badge says "caching in progress" with a pulsing animation. */
    Partial,

    /** `.idx.json` exists — the render finalised. Playback hits
     *  `CacheFileSource` (PR-E) and starts gapless from the first
     *  byte. Badge says "cached, plays instantly" with a solid
     *  filled-disc / lightning-bolt glyph. */
    Complete,
}
