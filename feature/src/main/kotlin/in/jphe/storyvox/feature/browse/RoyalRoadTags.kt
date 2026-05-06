package `in`.jphe.storyvox.feature.browse

/**
 * Royal Road's complete tag taxonomy as exposed by `/fictions/search`.
 * Sourced from the live form HTML — verify with curl when RR ships changes.
 *
 * Slugs are wire-format (`anti-hero_lead`, `summoned_hero`, etc.) so they map
 * directly to RR's `tagsAdd`/`tagsRemove` parameters without translation.
 */
internal object RoyalRoadTags {

    /** The 17 primary genres pinned at the top of the search form. */
    val GENRES: List<String> = listOf(
        "action",
        "adventure",
        "comedy",
        "contemporary",
        "drama",
        "fantasy",
        "historical",
        "horror",
        "mystery",
        "psychological",
        "romance_main",
        "satire",
        "sci_fi",
        "one_shot",
        "thriller",
        "tragedy",
    )

    /** Additional tags from the "Only include matching all tags" picker. */
    val ADDITIONAL: List<String> = listOf(
        "anti-hero_lead",
        "antivillain_lead",
        "apocalypse",
        "artificial_intelligence",
        "attractive_lead",
        "chivalry",
        "competing_love",
        "cozy",
        "crafting",
        "cultivation",
        "cyberpunk",
        "deck_building",
        "dungeon_core",
        "dungeon_crawler",
        "dystopia",
        "female_lead",
        "first_contact",
        "gamelit",
        "gender_bender",
        "genetically_engineered",
        "grimdark",
        "hard_sci-fi",
        "high_fantasy",
        "kingdom_building",
        "lesbian_romance",
        "litrpg",
        "local_protagonist",
        "low_fantasy",
        "magic",
        "magical_girl",
        "magitech",
        "gay_romance",
        "male_lead",
        "martial_arts",
        "mecha",
        "modern_knowledge",
        "monster_evolution",
        "multiple_lead",
        "harem",
        "mythos",
        "non-human_lead",
        "nonhumanoid_lead",
        "otome",
        "summoned_hero",
        "post_apocalyptic",
        "progression",
        "reader_interactive",
        "reincarnation",
        "romance",
        "ruling_class",
        "school_life",
        "secret_identity",
        "slice_of_life",
        "soft_sci-fi",
        "space_opera",
        "sports",
        "steampunk",
        "strategy",
        "strong_lead",
        "super_heroes",
        "supernatural",
        "survival",
        "system_invasion",
        "technologically_engineered",
        "loop",
        "time_travel",
        "tower",
        "urban_fantasy",
        "villainous_lead",
        "virtual_reality",
        "war_and_military",
        "wuxia",
    )

    /** Genres + additional tags in display order. */
    val ALL: List<String> = GENRES + ADDITIONAL
}
