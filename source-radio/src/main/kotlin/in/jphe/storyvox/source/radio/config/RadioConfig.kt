package `in`.jphe.storyvox.source.radio.config

import `in`.jphe.storyvox.source.radio.RadioStation
import kotlinx.coroutines.flow.Flow

/**
 * Issue #417 — abstraction over the radio source's persistent state.
 *
 * The source layer keeps its leaf-source posture (no DataStore imports);
 * the production implementation lives in `:app` on top of a dedicated
 * DataStore (`storyvox_radio_starred`). Same pattern as
 * [`in`.jphe.storyvox.source.rss.config.RssConfig].
 *
 * In v1 the only persisted state is the list of stations the user has
 * "starred" from a Radio Browser search hit. Future settings (default
 * codec preference, per-station bitrate cap, etc.) can land on this
 * interface without each source-layer call site needing to learn about
 * DataStore.
 *
 * Curated stations (KVMR, KQED, KCSB, …) are NOT persisted through
 * this interface — they're built into the in-source [RadioStations]
 * object and surface unconditionally. Only user-curated starred
 * imports go through here.
 */
interface RadioConfig {

    /** Hot stream of starred stations (Radio Browser imports the user
     *  has tapped the star on). Order is the order they were added. */
    val starredStations: Flow<List<RadioStation>>

    /** Synchronous snapshot for code paths that can't suspend. */
    suspend fun snapshot(): List<RadioStation>

    /** Add [station] to the starred set. No-op if the id is already
     *  starred. The full [RadioStation] is persisted (not just the id)
     *  so a starred station survives even if Radio Browser later drops
     *  it from their directory. */
    suspend fun star(station: RadioStation)

    /** Remove [stationId] from the starred set. No-op if not starred. */
    suspend fun unstar(stationId: String)

    /** True iff [stationId] is currently in the starred set. */
    suspend fun isStarred(stationId: String): Boolean
}
