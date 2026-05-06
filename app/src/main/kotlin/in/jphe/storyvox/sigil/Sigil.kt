package `in`.jphe.storyvox.sigil

import `in`.jphe.storyvox.BuildConfig

/**
 * realm-sigil port for storyvox.
 *
 * Generates a deterministic magical version name from the build's git hash
 * and the storyvox-chosen realm (`fantasy`). Mirrors the algorithm in
 * `~/Projects/realm-sigil/js/index.js` so the same hash + realm produces
 * the same name across the JS, Go, Python, and Kotlin implementations.
 *
 * Word lists are inlined from `realm-sigil/words/realms.json` (`fantasy`
 * realm). Keep them in sync with that file when realms.json changes —
 * `~/Projects/realm-sigil/sync-words.sh` is the canonical regenerator
 * but we don't depend on it at build time (Android can't readily read
 * external JSON during gradle configure on every build host).
 *
 * The realm choice — `fantasy` — matches storyvox's identity:
 *  - Library Nocturne theme (brass on warm dark, candlelit-leather)
 *  - Open-book launcher icon
 *  - Magical sigil loading skeletons
 *  - Royal Road's primary genre is fantasy / litrpg / progression
 *
 * A typical sigil reads as e.g. `"Blazing Crown · ef6a4cf3"`.
 */
object Sigil {

    /** "Fantasy" realm word lists — keep in sync with realms.json. */
    private val FANTASY_ADJECTIVES = listOf(
        "Arcane", "Blazing", "Celestial", "Draconic", "Eldritch",
        "Fabled", "Gilded", "Hallowed", "Infernal", "Jade",
        "Kindled", "Luminous", "Mythic", "Noble", "Obsidian",
        "Primal", "Radiant", "Spectral", "Twilight", "Valiant",
    )

    private val FANTASY_NOUNS = listOf(
        "Aegis", "Beacon", "Crown", "Dominion", "Ember",
        "Forge", "Grimoire", "Herald", "Insignia", "Jewel",
        "Keystone", "Lantern", "Monolith", "Nexus", "Oracle",
        "Pinnacle", "Quartz", "Relic", "Sigil", "Throne",
    )

    /** Generates `"Adjective Noun · hash"` deterministically from the hash. */
    fun nameFor(hash: String): String {
        val seed = parseHashAsInt(hash)
        val adj = FANTASY_ADJECTIVES[Math.floorMod(seed, FANTASY_ADJECTIVES.size)]
        val noun = FANTASY_NOUNS[Math.floorMod(seed shr 8, FANTASY_NOUNS.size)]
        return "$adj $noun · $hash"
    }

    private fun parseHashAsInt(hash: String): Int =
        runCatching { hash.toLong(radix = 16).toInt() }.getOrDefault(0)

    /** Read-once snapshot of the build's sigil — used by Settings → About. */
    val current: SigilInfo by lazy {
        SigilInfo(
            name = nameFor(BuildConfig.SIGIL_HASH),
            realm = BuildConfig.SIGIL_REALM,
            hash = BuildConfig.SIGIL_HASH,
            branch = BuildConfig.SIGIL_BRANCH,
            dirty = BuildConfig.SIGIL_DIRTY,
            built = BuildConfig.SIGIL_BUILT,
            repo = BuildConfig.SIGIL_REPO,
            versionName = BuildConfig.VERSION_NAME,
        )
    }
}

data class SigilInfo(
    val name: String,
    val realm: String,
    val hash: String,
    val branch: String,
    val dirty: Boolean,
    val built: String,
    val repo: String,
    val versionName: String,
) {
    /** URL to the GitHub commit page for this build, or empty for `dev` builds. */
    val commitUrl: String
        get() = if (hash != "dev" && repo.isNotBlank()) "$repo/commit/$hash" else ""
}
