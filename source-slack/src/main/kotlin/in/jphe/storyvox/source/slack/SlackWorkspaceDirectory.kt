package `in`.jphe.storyvox.source.slack

import `in`.jphe.storyvox.data.source.model.FictionResult
import `in`.jphe.storyvox.source.slack.net.SlackApi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #454 — public-visibility lookup surface for Settings.
 *
 * Two roles:
 *  - **Authenticate** — `auth.test` returns the bot identity +
 *    workspace metadata, so the Settings card can confirm
 *    "Authenticated as @bot in <workspace>" after a token paste.
 *  - **Probe** — `conversations.list` reports back the channels the
 *    bot is a member of. The Settings empty state can then surface
 *    a friendly "your bot is in N channels" line vs. the more
 *    anxious "your bot has not been invited to any channels yet."
 *
 * Declared as an interface (rather than a class) so test modules
 * in `:app` can supply a no-op fake without reaching across the
 * internal-visibility wall to the production constructor.
 */
interface SlackWorkspaceDirectory {

    /**
     * Verify the bot token + return the (workspace name, bot
     * user-handle) pair, or null when unset / token invalid /
     * network out. The workspace name is what the Settings card
     * surfaces in its "authenticated as @bot in <workspace>" line.
     */
    suspend fun authenticate(): SlackAuth?

    /**
     * One walk of `conversations.list` filtered to channels the
     * bot is a member of. Returns `(channelId, displayTitle)` pairs
     * sorted by title. Empty on token failure or no joined
     * channels.
     */
    suspend fun listChannels(): List<Pair<String, String>>

    companion object {
        /** Empty / no-op fake for tests that don't exercise the
         *  Settings probe flow. */
        val Empty: SlackWorkspaceDirectory = object : SlackWorkspaceDirectory {
            override suspend fun authenticate(): SlackAuth? = null
            override suspend fun listChannels(): List<Pair<String, String>> = emptyList()
        }
    }
}

/** Workspace authentication snapshot returned by
 *  [SlackWorkspaceDirectory.authenticate]. Both fields may be empty
 *  strings when Slack's `auth.test` doesn't return them (free-tier
 *  workspaces sometimes redact the workspace url for bot tokens).
 *  Carry the values plain so the Settings UI can fall back to
 *  generic copy without a separate "is this populated" flag. */
data class SlackAuth(
    val workspaceName: String,
    val workspaceUrl: String,
    val botUser: String,
)

@Singleton
internal class SlackWorkspaceDirectoryImpl @Inject constructor(
    private val api: SlackApi,
) : SlackWorkspaceDirectory {

    override suspend fun authenticate(): SlackAuth? {
        val resp = when (val r = api.authTest()) {
            is FictionResult.Success -> r.value
            is FictionResult.Failure -> return null
        }
        if (!resp.ok) return null
        return SlackAuth(
            workspaceName = resp.team.orEmpty(),
            workspaceUrl = resp.url.orEmpty(),
            botUser = resp.user.orEmpty(),
        )
    }

    override suspend fun listChannels(): List<Pair<String, String>> {
        val resp = when (val r = api.listConversations()) {
            is FictionResult.Success -> r.value
            is FictionResult.Failure -> return emptyList()
        }
        return resp.channels
            .filter { it.isMember }
            .map { it.id to "#${it.name.ifBlank { it.id }}" }
            .sortedBy { it.second.lowercase() }
    }
}
