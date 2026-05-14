package `in`.jphe.storyvox.source.discord

import `in`.jphe.storyvox.source.discord.net.DiscordApi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #403 — public-visibility lookup surface for the Settings
 * server-picker dropdown. The actual `DiscordApi` is `internal` to
 * this module (we don't want :app or :feature reaching into the wire
 * shapes); this thin wrapper hands back `(id, displayName)` pairs the
 * UI can render directly.
 *
 * Returns an empty list whenever the API call fails — the UI treats
 * "empty picker" as the unified empty-state across the three failure
 * modes the user can drive (no token, bad token, network out) and
 * surfaces the explanatory copy alongside the picker.
 *
 * Declared as an interface (rather than a class) so test modules in
 * `:app` can supply a no-op fake without reaching across the
 * internal-visibility wall to the production constructor.
 */
interface DiscordGuildDirectory {
    suspend fun listGuilds(): List<Pair<String, String>>

    companion object {
        /** Empty-list fake for tests that don't exercise the
         *  server-picker flow. */
        val Empty: DiscordGuildDirectory = object : DiscordGuildDirectory {
            override suspend fun listGuilds(): List<Pair<String, String>> = emptyList()
        }
    }
}

@Singleton
internal class DiscordGuildDirectoryImpl @Inject constructor(
    private val api: DiscordApi,
) : DiscordGuildDirectory {
    override suspend fun listGuilds(): List<Pair<String, String>> {
        return when (val r = api.listGuilds()) {
            is `in`.jphe.storyvox.data.source.model.FictionResult.Success ->
                r.value.map { it.id to it.name }.sortedBy { it.second.lowercase() }
            is `in`.jphe.storyvox.data.source.model.FictionResult.Failure ->
                emptyList()
        }
    }
}
