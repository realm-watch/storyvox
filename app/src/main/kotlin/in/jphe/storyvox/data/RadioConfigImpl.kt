package `in`.jphe.storyvox.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import `in`.jphe.storyvox.source.radio.RadioStation
import `in`.jphe.storyvox.source.radio.config.RadioConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.radioStarredDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "storyvox_radio_starred",
)

private object RadioKeys {
    /**
     * JSON-encoded `List<StarredStationDto>` of stations the user has
     * starred from a Radio Browser search hit. Stored as a single
     * preference key because the list is small (typically <50) and the
     * full-rewrite-on-edit cost is negligible. Same shape rationale as
     * [RssConfigImpl]'s pipe-joined string, but JSON makes sense here
     * because [RadioStation] has nine fields and a delimiter-joined
     * format would be brittle against tags containing punctuation.
     *
     * Empty / missing key = no starred stations.
     */
    val STARRED = stringPreferencesKey("pref_radio_starred_station_ids")
}

/**
 * Issue #417 — production [RadioConfig] backed by a dedicated
 * DataStore (`storyvox_radio_starred`). Kept separate from
 * `storyvox_settings` so the starred-station set can grow without
 * churning that file's preference schema (same pattern as
 * [`RssConfigImpl`]).
 *
 * ## Persistence shape
 *
 * The full [RadioStation] descriptor is stored per starred entry, not
 * just its id. Reason: Radio Browser stations occasionally fall out of
 * the directory (broken-uptime culling, station shut down, etc.). If
 * storyvox only persisted the uuid, those starred entries would
 * silently disappear from the user's Browse list on the next launch.
 * Persisting the full descriptor means a starred station keeps
 * working as long as its stream URL is still alive on the upstream
 * server.
 *
 * ## Forward-compat
 *
 * Unknown JSON fields are tolerated by `Json { ignoreUnknownKeys =
 * true }`, so a future schema extension (per-station bitrate cap,
 * default volume, etc.) won't trip the parser on an older install. A
 * parse failure (corrupted blob) drops back to an empty list — the
 * user can re-star their favourite stations rather than the source
 * failing to load.
 */
@Singleton
class RadioConfigImpl(
    private val store: DataStore<Preferences>,
) : RadioConfig {

    @Inject constructor(@ApplicationContext context: Context) : this(context.radioStarredDataStore)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }
    private val listSerializer = ListSerializer(StarredStationDto.serializer())

    override val starredStations: Flow<List<RadioStation>> = store.data
        .map { prefs -> decode(prefs[RadioKeys.STARRED]) }
        .distinctUntilChanged()

    override suspend fun snapshot(): List<RadioStation> =
        decode(store.data.first()[RadioKeys.STARRED])

    override suspend fun star(station: RadioStation) {
        store.edit { prefs ->
            val existing = decode(prefs[RadioKeys.STARRED])
            if (existing.any { it.id == station.id }) return@edit
            prefs[RadioKeys.STARRED] = encode(existing + station)
        }
    }

    override suspend fun unstar(stationId: String) {
        store.edit { prefs ->
            val existing = decode(prefs[RadioKeys.STARRED])
            val updated = existing.filterNot { it.id == stationId }
            if (updated.size == existing.size) return@edit
            prefs[RadioKeys.STARRED] = encode(updated)
        }
    }

    override suspend fun isStarred(stationId: String): Boolean =
        snapshot().any { it.id == stationId }

    private fun encode(stations: List<RadioStation>): String =
        json.encodeToString(listSerializer, stations.map { StarredStationDto.from(it) })

    private fun decode(raw: String?): List<RadioStation> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString(listSerializer, raw).map { it.toRadioStation() }
        } catch (e: SerializationException) {
            // Corrupted blob — drop back to empty rather than failing
            // the source. User re-stars on next session.
            emptyList()
        }
    }
}

/**
 * Persisted shape of a starred radio station. Decoupled from
 * [RadioStation] so the source-module data class can evolve (add /
 * remove fields, change defaults) without forcing a stored-state
 * migration; the DTO carries explicit defaults for every field so an
 * older persisted blob parses cleanly against a newer DTO.
 */
@Serializable
private data class StarredStationDto(
    val id: String,
    val displayName: String = "",
    val description: String = "",
    val streamUrl: String,
    val streamCodec: String = "",
    val country: String = "",
    val language: String = "",
    val tags: List<String> = emptyList(),
    val homepage: String = "",
) {
    fun toRadioStation(): RadioStation = RadioStation(
        id = id,
        displayName = displayName,
        description = description,
        streamUrl = streamUrl,
        streamCodec = streamCodec,
        country = country,
        language = language,
        tags = tags,
        homepage = homepage,
    )

    companion object {
        fun from(s: RadioStation): StarredStationDto = StarredStationDto(
            id = s.id,
            displayName = s.displayName,
            description = s.description,
            streamUrl = s.streamUrl,
            streamCodec = s.streamCodec,
            country = s.country,
            language = s.language,
            tags = s.tags,
            homepage = s.homepage,
        )
    }
}
