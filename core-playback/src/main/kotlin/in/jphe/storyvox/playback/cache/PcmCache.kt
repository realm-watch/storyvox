package `in`.jphe.storyvox.playback.cache

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Filesystem-backed PCM cache. Owns `${context.cacheDir}/pcm-cache/`
 * and the `<sha>.pcm` / `<sha>.idx.json` / `<sha>.meta.json` triple
 * per cached chapter.
 *
 * The cache root lives in [Context.getCacheDir] (vs `filesDir`) on
 * purpose: Android may evict `cacheDir` under storage pressure even
 * without our help, and our LRU is conservative on top of that. A
 * surprise OS-level wipe is recoverable — next play re-renders. If
 * we'd put the cache in `filesDir` we'd be promising durability we
 * don't actually want.
 *
 * Concurrency:
 *  - Reads ([isComplete], [pcmFileFor], [indexFileFor], [totalSizeBytes]) are
 *    safe to call from any thread.
 *  - Writes ([appender], [evictTo], [delete]) are also thread-safe
 *    individually, but at most one [PcmAppender] should be open per
 *    key at a time. PR-D enforces that mutual exclusion at the
 *    streaming-source / render-job boundary; PR-C trusts the caller.
 *
 * No integration in this PR — `EnginePlayer` doesn't reference this
 * class. PR-D wires the [appender] into `EngineStreamingSource`'s
 * producer loop; PR-E adds a `CacheFileSource` that reads via
 * [pcmFileFor] + [indexFileFor].
 */
@Singleton
class PcmCache(
    private val rootDir: File,
    private val config: PcmCacheConfig,
) {
    init {
        rootDir.mkdirs()
    }

    /** Hilt entry point — anchors the cache root at
     *  `${context.cacheDir}/pcm-cache/`. The primary constructor takes a
     *  bare [File] so JVM unit tests can point at a temp folder without
     *  bootstrapping Robolectric. Same seam as [PcmCacheConfig]. */
    @Inject constructor(
        @ApplicationContext context: Context,
        config: PcmCacheConfig,
    ) : this(File(context.cacheDir, ROOT_DIR_NAME), config)

    /** Root directory exposed for tests + the future "Clear cache" UI. */
    fun rootDirectory(): File = rootDir

    fun pcmFileFor(key: PcmCacheKey): File =
        File(rootDir, "${key.fileBaseName()}$PCM_SUFFIX")

    fun indexFileFor(key: PcmCacheKey): File =
        File(rootDir, "${key.fileBaseName()}$INDEX_SUFFIX")

    fun metaFileFor(key: PcmCacheKey): File =
        File(rootDir, "${key.fileBaseName()}$META_SUFFIX")

    /** True iff the index sidecar exists (i.e. a finalized render
     *  landed for this key). Cheap — single `File.exists` syscall. */
    fun isComplete(key: PcmCacheKey): Boolean = indexFileFor(key).exists()

    /**
     * Open a writer for [key]. If a previous render for the same key
     * was abandoned, leftover `.pcm` / `.meta.json` are NOT auto-deleted
     * here (the appender's `init` block treats existing pcm length as
     * the resume offset). PR-D decides resume vs restart; if it picks
     * restart, it should call [delete] first to wipe the partial entry.
     */
    fun appender(
        key: PcmCacheKey,
        sampleRate: Int,
    ): PcmAppender = PcmAppender(
        pcmFile = pcmFileFor(key),
        metaFile = metaFileFor(key),
        indexFile = indexFileFor(key),
        sampleRate = sampleRate,
        chapterId = key.chapterId,
        voiceId = key.voiceId,
        chunkerVersion = key.chunkerVersion,
        speedHundredths = key.speedHundredths,
        pitchHundredths = key.pitchHundredths,
    )

    /** Touch the pcm file's mtime — call on every successful play of
     *  a cached chapter so LRU eviction prefers genuinely-cold entries. */
    suspend fun touch(key: PcmCacheKey) = withContext(Dispatchers.IO) {
        pcmFileFor(key).setLastModified(System.currentTimeMillis())
        Unit
    }

    /** Delete pcm + idx + meta for [key]. Idempotent. */
    suspend fun delete(key: PcmCacheKey) = withContext(Dispatchers.IO) {
        val basename = key.fileBaseName()
        runCatching { pcmFileFor(key).delete() }
        runCatching { indexFileFor(key).delete() }
        runCatching { metaFileFor(key).delete() }
        runCatching { File(rootDir, "$basename$INDEX_SUFFIX$TMP_SUFFIX").delete() }
        Unit
    }

    /**
     * Delete every cached entry whose `.meta.json` references [chapterId].
     * Used when chapter text changes (re-imported, edited in source) —
     * the byte offsets in the index are wrong for the new text, so all
     * voice variants must go.
     */
    suspend fun deleteAllForChapter(chapterId: String) = withContext(Dispatchers.IO) {
        val metaFiles = rootDir.listFiles { f -> f.name.endsWith(META_SUFFIX) }
            ?: return@withContext
        for (mf in metaFiles) {
            val basename = mf.name.removeSuffix(META_SUFFIX)
            val meta = runCatching {
                pcmCacheJson.decodeFromString(PcmMeta.serializer(), mf.readText())
            }.getOrNull() ?: continue
            if (meta.chapterId == chapterId) {
                runCatching { File(rootDir, "$basename$PCM_SUFFIX").delete() }
                runCatching { File(rootDir, "$basename$INDEX_SUFFIX").delete() }
                runCatching { mf.delete() }
            }
        }
    }

    /** Sum of `.pcm` file sizes under the cache root (idx + meta are
     *  trivially small; spec budget is dominated by audio). */
    suspend fun totalSizeBytes(): Long = withContext(Dispatchers.IO) {
        (rootDir.listFiles { f -> f.name.endsWith(PCM_SUFFIX) } ?: emptyArray())
            .sumOf { it.length() }
    }

    /**
     * LRU-evict (by .pcm mtime, oldest first) until total .pcm size ≤
     * [quotaBytes]. Entries whose key SHA matches [pinnedBasenames] are
     * skipped — caller passes basenames for currently-playing + next-
     * in-sequence chapters per spec.
     *
     * **Azure-survives-longer ordering (#186, PR-7).** Azure-rendered
     * entries cost real money to re-create; local engine entries are
     * free to re-render. The eviction order pins local entries to
     * earlier in the LRU list — non-Azure first (by mtime ascending),
     * then Azure (by mtime ascending). A 1980s Piper render gets
     * thrown away before a yesterday's Azure render. Azure detection
     * via the `.meta.json` file's voiceId — Azure ids start with
     * `azure_`. Meta-read failures default to non-Azure (we'd rather
     * over-evict than under-evict on a corrupt file; the cache
     * tolerates rebuilding any entry).
     *
     * Returns the number of entries evicted.
     */
    suspend fun evictTo(
        quotaBytes: Long,
        pinnedBasenames: Set<String> = emptySet(),
    ): Int = withContext(Dispatchers.IO) {
        var total = totalSizeBytes()
        if (total <= quotaBytes) return@withContext 0

        // Read each candidate's meta-file once to learn its voiceId.
        // The (isAzure, mtime) compound key sorts non-Azure-then-Azure,
        // mtime-ascending within each group. List sort is by file
        // mtime; the meta read amortizes across one eviction cycle.
        val candidates = (rootDir.listFiles { f -> f.name.endsWith(PCM_SUFFIX) } ?: emptyArray())
            .filter { it.name.removeSuffix(PCM_SUFFIX) !in pinnedBasenames }
            .map { pcm ->
                val basename = pcm.name.removeSuffix(PCM_SUFFIX)
                val isAzure = readMetaIsAzure(basename)
                Triple(pcm, isAzure, pcm.lastModified())
            }
            .sortedWith(compareBy({ it.second }, { it.third }))

        var evicted = 0
        for ((pcm, _, _) in candidates) {
            if (total <= quotaBytes) break
            val basename = pcm.name.removeSuffix(PCM_SUFFIX)
            val sz = pcm.length()
            runCatching { pcm.delete() }
            runCatching { File(rootDir, "$basename$INDEX_SUFFIX").delete() }
            runCatching { File(rootDir, "$basename$META_SUFFIX").delete() }
            total -= sz
            evicted++
        }
        evicted
    }

    /** PR-7 (#186) — true if the cache entry's meta-file declares an
     *  Azure voiceId. Azure voice ids ship with the `azure_` prefix
     *  per [VoiceCatalog.azureEntries]; future cloud providers should
     *  use a similarly distinguishable prefix if they want the same
     *  paid-renders-survive-longer treatment. */
    private fun readMetaIsAzure(basename: String): Boolean {
        val mf = File(rootDir, "$basename$META_SUFFIX")
        if (!mf.isFile) return false
        return runCatching {
            pcmCacheJson.decodeFromString(PcmMeta.serializer(), mf.readText())
                .voiceId.startsWith("azure_")
        }.getOrDefault(false)
    }

    /** Convenience overload — reads quota from [PcmCacheConfig]. */
    suspend fun evictToQuota(pinnedBasenames: Set<String> = emptySet()): Int =
        evictTo(config.quotaBytes(), pinnedBasenames)

    /** Wipe everything under the cache root. PR-G's "Clear cache" button. */
    suspend fun clearAll() = withContext(Dispatchers.IO) {
        rootDir.listFiles()?.forEach { runCatching { it.delete() } }
        Unit
    }

    private companion object {
        const val ROOT_DIR_NAME = "pcm-cache"
        const val PCM_SUFFIX = ".pcm"
        const val INDEX_SUFFIX = ".idx.json"
        const val META_SUFFIX = ".meta.json"
        const val TMP_SUFFIX = ".tmp"
    }
}
