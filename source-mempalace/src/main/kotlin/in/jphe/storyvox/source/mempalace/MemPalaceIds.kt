package `in`.jphe.storyvox.source.mempalace

import `in`.jphe.storyvox.data.source.SourceIds

/**
 * Encode + decode the storyvox-side ids that route a palace-backed
 * fiction or chapter. Lives in its own file so the codec stays
 * unit-testable independently of the network layer and can be
 * mocked against in higher-level tests.
 *
 * Format:
 *  - fictionId: `mempalace:<wing>/<room>`
 *  - chapterId: `mempalace:<wing>/<room>:<drawer_id>`
 *
 * Wing and room are lowercase tokens with `_`-separators (palace
 * convention; sometimes contain `.` for domain-style names like
 * `os.realm.watch`). They never contain `/`. Drawer ids are the
 * palace primary key, prefixed `drawer_`, and contain `_` and hex
 * but no `:` or `/`. That keeps the parse unambiguous against the
 * one `:` and one `/` separators we use.
 */
internal object MemPalaceIds {

    /** `mempalace:<wing>/<room>` → `(wing, room)`, or null on malformed input. */
    fun parseFictionId(fictionId: String): Pair<String, String>? {
        val stripped = fictionId.removePrefix("${SourceIds.MEMPALACE}:")
        if (stripped == fictionId) return null
        val slash = stripped.indexOf('/')
        if (slash <= 0 || slash == stripped.length - 1) return null
        val wing = stripped.substring(0, slash)
        val room = stripped.substring(slash + 1)
        // Reject extra `/` in either half — palace wing/room names don't
        // contain `/`, so an extra one means the input is malformed (or
        // that we're parsing a chapter id by accident).
        if ('/' in room) return null
        return wing to room
    }

    /** `mempalace:<wing>/<room>:<drawer_id>` → `(wing, room, drawerId)`, or null. */
    fun parseChapterId(chapterId: String): Triple<String, String, String>? {
        val stripped = chapterId.removePrefix("${SourceIds.MEMPALACE}:")
        if (stripped == chapterId) return null
        val slash = stripped.indexOf('/')
        if (slash <= 0) return null
        val wing = stripped.substring(0, slash)
        val rest = stripped.substring(slash + 1)
        val colon = rest.indexOf(':')
        if (colon <= 0 || colon == rest.length - 1) return null
        val room = rest.substring(0, colon)
        val drawerId = rest.substring(colon + 1)
        if (drawerId.isBlank()) return null
        return Triple(wing, room, drawerId)
    }

    fun fictionId(wing: String, room: String): String =
        "${SourceIds.MEMPALACE}:$wing/$room"

    fun chapterId(wing: String, room: String, drawerId: String): String =
        "${SourceIds.MEMPALACE}:$wing/$room:$drawerId"

    /** Pretty-print a wing/room token for UI display. */
    fun prettify(token: String): String =
        token.split('_', '-')
            .filter { it.isNotEmpty() }
            .joinToString(" ") { word ->
                // Preserve domain-style names like "os.realm.watch" — split
                // on dots too but keep them in the output.
                word.split('.').joinToString(".") { part ->
                    if (part.isEmpty()) part
                    else part.replaceFirstChar { it.uppercaseChar() }
                }
            }
}
