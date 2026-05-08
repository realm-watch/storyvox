package `in`.jphe.storyvox.llm

import kotlinx.coroutines.flow.Flow

/**
 * Source of [LlmConfig] for the provider classes. Implemented in
 * `:app` (or a settings impl module) by reading DataStore + the
 * encrypted-prefs presence flag.
 *
 * The [config] flow is hot — provider classes call `.first()` on it
 * at request time, so the flow needs to emit the current value
 * promptly. DataStore-backed flows already satisfy this.
 *
 * Kept as a tiny single-method interface (rather than passing a
 * `Flow<LlmConfig>` directly) so that implementations can also
 * persist updates via [setConfig] without `:app`'s settings impl
 * needing to expose half a dozen narrow setters into `:core-llm`.
 */
interface LlmConfigProvider {
    val config: Flow<LlmConfig>
    suspend fun setConfig(config: LlmConfig)
}
