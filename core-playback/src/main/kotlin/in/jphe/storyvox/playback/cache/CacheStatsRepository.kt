package `in`.jphe.storyvox.playback.cache

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow

/**
 * PCM cache size + quota observation for the Settings UI's "Currently
 * used: 1.4 GB / 2 GB" row.
 *
 * Implementation is a polling cold flow rather than an event stream
 * because:
 *  - PR-D's tee-write and PR-F's background worker each finalize /
 *    abandon at different lifetimes; subscribing to either set of
 *    events would miss the eviction calls (which run from the workers
 *    and from EnginePlayer's natural-end branch).
 *  - The user-facing latency we care about is "did Clear cache work?",
 *    which we resolve by re-polling immediately after the action; for
 *    the steady-state indicator a 5 s tick is well within human
 *    perception of "live".
 *  - A polling flow is trivial to test (Robolectric advance time, then
 *    observe the next emission).
 *
 * Settings-screen lifecycle ends the poll: the flow is `cold`, started
 * when the UI subscribes and cancelled when the UI disposes. No
 * background resource use when Settings isn't open.
 *
 * Bypassed by [distinctUntilChanged] so a stable cache state collapses
 * to a single emission (no needless recomposition while the user is
 * just looking at the screen).
 */
@Singleton
class CacheStatsRepository @Inject constructor(
    private val cache: PcmCache,
    private val config: PcmCacheConfig,
) {

    /**
     * Snapshot of the PCM cache size and the user-configured quota.
     *
     * - [usedBytes]: sum of `<sha>.pcm` file sizes under
     *   `${context.cacheDir}/pcm-cache/`. Same number reported by
     *   [PcmCache.totalSizeBytes].
     * - [quotaBytes]: configured upper bound (DataStore-backed via
     *   [PcmCacheConfig.quotaBytes]). [Long.MAX_VALUE] = "Unlimited"
     *   per the four discrete tiers surfaced in Settings.
     */
    data class CacheStats(
        val usedBytes: Long,
        val quotaBytes: Long,
    )

    /**
     * Observe stats as a cold flow. Emits an initial snapshot
     * immediately, then re-snapshots every [pollIntervalMs] until the
     * subscriber cancels. Adjacent equal snapshots are dropped via
     * [distinctUntilChanged].
     *
     * @param pollIntervalMs default 5 s. Visible-for-testing so unit
     *   tests can use a short tick without an [advanceTimeBy] of 5 s.
     */
    fun observe(pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS): Flow<CacheStats> = flow {
        while (true) {
            emit(snapshot())
            delay(pollIntervalMs)
        }
    }.distinctUntilChanged()

    /** One-shot snapshot. Useful after a destructive action ("Clear
     *  cache") where the UI wants immediate confirmation before the
     *  next poll tick fires. */
    suspend fun snapshot(): CacheStats = CacheStats(
        usedBytes = cache.totalSizeBytes(),
        quotaBytes = config.quotaBytes(),
    )

    private companion object {
        const val DEFAULT_POLL_INTERVAL_MS: Long = 5_000L
    }
}
