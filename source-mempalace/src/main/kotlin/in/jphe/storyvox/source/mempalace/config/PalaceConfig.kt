package `in`.jphe.storyvox.source.mempalace.config

import kotlinx.coroutines.flow.Flow

/**
 * Palace daemon connection config. The host comes from the regular
 * settings DataStore (it's a LAN host, not a secret); the API key
 * comes from EncryptedSharedPreferences (write-capable token, even
 * though v1 doesn't write).
 *
 * The actual implementation lives in `:app` (alongside
 * `SettingsRepositoryUiImpl` which already owns the DataStore
 * instance) — this is the seam the source module consumes. Mirrors
 * how RoyalRoad consumes [`AuthRepository`] from `:core-data` rather
 * than owning its own SharedPreferences instance.
 */
interface PalaceConfig {

    /** Hot stream — emits on every settings change, on app foreground. */
    val state: Flow<PalaceConfigState>

    /** One-shot snapshot. */
    suspend fun current(): PalaceConfigState
}

data class PalaceConfigState(
    /**
     * Empty when the user hasn't configured a host yet. Otherwise the
     * raw value from the settings field — `host[:port]`, no scheme.
     * The source module prepends `http://`.
     */
    val host: String,
    /** API key as stored. Empty = unauthenticated daemon expected. */
    val apiKey: String,
) {
    val isConfigured: Boolean get() = host.isNotBlank()
}
