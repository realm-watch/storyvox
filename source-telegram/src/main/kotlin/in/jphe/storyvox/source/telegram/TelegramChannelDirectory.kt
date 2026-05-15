package `in`.jphe.storyvox.source.telegram

import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.source.telegram.net.TelegramApi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #462 — public-visibility lookup surface for Settings.
 *
 * Two roles:
 *  - **Authenticate** — `getMe()` returns the bot's @username so the
 *    Settings card can confirm "Authenticated as @storyvox_bot"
 *    after a token paste.
 *  - **Probe** — `listChannels()` polls `getUpdates` once and reports
 *    back which channels the bot has observed activity in. The
 *    Settings empty state can then surface a friendly "your bot
 *    sees N channels" line vs. the more anxious "your bot has not
 *    been invited to any channels yet."
 *
 * Declared as an interface (rather than a class) so test modules
 * in `:app` can supply a no-op fake without reaching across the
 * internal-visibility wall to the production constructor.
 */
interface TelegramChannelDirectory {
    /** Verify the bot token + return the bot's @username (or
     *  null when unset / token invalid / network out). */
    suspend fun authenticate(): String?

    /** One poll of `getUpdates` plus a `getChat` per observed
     *  channel id. Returns `(chatId-as-string, displayTitle)`
     *  pairs sorted by title. Empty on token failure or no
     *  observed channels. */
    suspend fun listChannels(): List<Pair<String, String>>

    companion object {
        /** Empty / no-op fake for tests that don't exercise the
         *  Settings probe flow. */
        val Empty: TelegramChannelDirectory = object : TelegramChannelDirectory {
            override suspend fun authenticate(): String? = null
            override suspend fun listChannels(): List<Pair<String, String>> = emptyList()
        }
    }
}

@Singleton
internal class TelegramChannelDirectoryImpl @Inject constructor(
    private val api: TelegramApi,
) : TelegramChannelDirectory {

    override suspend fun authenticate(): String? {
        return when (val r = api.getMe()) {
            is FictionResult.Success -> r.value.username?.takeIf { it.isNotBlank() }
                ?: r.value.firstName.takeIf { it.isNotBlank() }
            is FictionResult.Failure -> null
        }
    }

    override suspend fun listChannels(): List<Pair<String, String>> {
        val updates = when (val r = api.getUpdates(offset = 0)) {
            is FictionResult.Success -> r.value
            is FictionResult.Failure -> return emptyList()
        }
        val chatIds = updates.mapNotNull { u ->
            (u.channelPost ?: u.editedChannelPost)?.chat?.id
        }.toSet()
        val pairs = mutableListOf<Pair<String, String>>()
        for (chatId in chatIds) {
            val chat = when (val r = api.getChat(chatId)) {
                is FictionResult.Success -> r.value
                is FictionResult.Failure -> continue
            }
            if (chat.type != "channel") continue
            pairs.add(chatId.toString() to chat.title.ifBlank { "Channel $chatId" })
        }
        return pairs.sortedBy { it.second.lowercase() }
    }
}
