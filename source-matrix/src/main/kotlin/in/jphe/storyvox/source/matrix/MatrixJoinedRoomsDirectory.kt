package `in`.jphe.storyvox.source.matrix

import `in`.jphe.storyvox.source.matrix.net.MatrixApi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #457 — public-visibility lookup surface for the Settings
 * room-picker (when a future PR wires it up) + the
 * "verify token via whoami" Settings confirmation. The actual
 * [`in`.jphe.storyvox.source.matrix.net.MatrixApi] is
 * `internal` to this module (we don't want :app or :feature reaching
 * into the wire shapes); this thin wrapper hands back simple
 * `List<Pair<String, String>>` (id, displayName) tuples the UI can
 * render directly.
 *
 * Mirrors [`in`.jphe.storyvox.source.discord.DiscordGuildDirectory]
 * and [`in`.jphe.storyvox.source.telegram.TelegramChannelDirectory]
 * — the wider plugin-config UI pattern.
 *
 * Returns an empty list whenever the API call fails. The UI treats
 * "empty picker" as the unified empty-state across the three
 * failure modes the user can drive (no token, bad token, network
 * out) and surfaces the explanatory copy alongside the picker.
 *
 * Declared as an interface (rather than a class) so test modules in
 * `:app` can supply a no-op fake without reaching across the
 * internal-visibility wall to the production constructor.
 */
interface MatrixJoinedRoomsDirectory {
    /**
     * Joined rooms on the configured homeserver as
     * `(roomId, displayName)` pairs. The display name is the room's
     * `m.room.name` state value when set; otherwise the bare room
     * id as a placeholder.
     *
     * Empty list on auth failure / network failure / no homeserver
     * configured — the UI shows the same "configure Matrix in
     * Settings" empty state across all three.
     */
    suspend fun listJoinedRooms(): List<Pair<String, String>>

    /**
     * Verify the configured access token is live and return the
     * resolved `@user:homeserver` Matrix id behind it. Null on
     * auth failure / network failure / no homeserver configured.
     * Drives the Settings "Signed in as …" confirmation row.
     */
    suspend fun whoami(): String?

    companion object {
        /** Empty-list fake for tests that don't exercise the
         *  room-picker / whoami flow. */
        val Empty: MatrixJoinedRoomsDirectory = object : MatrixJoinedRoomsDirectory {
            override suspend fun listJoinedRooms(): List<Pair<String, String>> = emptyList()
            override suspend fun whoami(): String? = null
        }
    }
}

@Singleton
internal class MatrixJoinedRoomsDirectoryImpl @Inject constructor(
    private val api: MatrixApi,
) : MatrixJoinedRoomsDirectory {

    override suspend fun listJoinedRooms(): List<Pair<String, String>> {
        val joined = when (val r = api.listJoinedRooms()) {
            is `in`.jphe.storyvox.data.source.model.FictionResult.Success -> r.value.joinedRooms
            is `in`.jphe.storyvox.data.source.model.FictionResult.Failure -> return emptyList()
        }
        // For each room, try to fetch the m.room.name state event.
        // Failures fall through to the room id as a placeholder so
        // one bad room doesn't drop the whole list.
        return joined.map { roomId ->
            val name = when (val r = api.getRoomName(roomId)) {
                is `in`.jphe.storyvox.data.source.model.FictionResult.Success ->
                    r.value.name.ifBlank { roomId }
                is `in`.jphe.storyvox.data.source.model.FictionResult.Failure -> roomId
            }
            roomId to name
        }.sortedBy { it.second.lowercase() }
    }

    override suspend fun whoami(): String? {
        return when (val r = api.whoami()) {
            is `in`.jphe.storyvox.data.source.model.FictionResult.Success ->
                r.value.userId.takeIf { it.isNotBlank() }
            is `in`.jphe.storyvox.data.source.model.FictionResult.Failure -> null
        }
    }
}
