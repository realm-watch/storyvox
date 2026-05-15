package `in`.jphe.storyvox.source.rss.templates

/**
 * Issue #464 — curated "Local marketplace" template surface. Every
 * Craigslist regional subdomain publishes well-known RSS endpoints
 * (`https://<region>.craigslist.org/search/<category>?format=rss`)
 * with no auth, no rate-limits, and a stable feed shape that
 * [`in`.jphe.storyvox.source.rss.parse.RssParser] already understands.
 *
 * The friction the issue calls out is that the user has to type the
 * URL by hand today. This file is the data layer for a UI affordance
 * that composes the URL from a (region, category) picker — implemented
 * inside `:source-rss` per the issue title's ":source-rss enhancement"
 * scope (no new `:source-craigslist` Gradle module).
 *
 * ## Why static Kotlin data instead of a JSON resource
 *
 * The region+category catalogue is small (~50 + 7), changes rarely,
 * and benefits from compile-time typing. A JSON file in
 * `src/main/resources/` would add a parse path with no upside and
 * defeat IDE refactoring. The strategic-context section of #464
 * envisions other "regional template" surfaces beyond Craigslist
 * (local-newspaper feeds, library event calendars); when a second
 * template lands, a generic `RegionalTemplateRegistry` can crystallise
 * out of the shape that emerges from two concrete cases — not from
 * speculation now.
 *
 * ## ToS posture
 *
 * Per the issue's "ToS posture" section: `?format=rss` is published,
 * official, and unrestricted by Craigslist. No scraping, no rate-limit
 * gotchas. The compose function is the single canonical formatter.
 */
object CraigslistTemplates {

    /**
     * Curated US metro list — the 50 largest plus a handful of small
     * regions that JP personally cares about (Nevada City — per the
     * issue body, his Sierra-foothills home). Slugs match the
     * Craigslist subdomain exactly; labels are the human display name.
     *
     * Ordering: alphabetical by [label], with one exception — SF Bay
     * Area sits at the top because that's the issue's canonical
     * "first example" metro and it makes manual QA on the device
     * faster (region picker opens to a recognisable entry).
     */
    val REGIONS: List<CraigslistRegion> = listOf(
        CraigslistRegion("sfbay", "SF Bay Area"),
        CraigslistRegion("newyork", "New York City"),
        CraigslistRegion("losangeles", "Los Angeles"),
        CraigslistRegion("chicago", "Chicago"),
        CraigslistRegion("houston", "Houston"),
        CraigslistRegion("dallas", "Dallas / Fort Worth"),
        CraigslistRegion("philadelphia", "Philadelphia"),
        CraigslistRegion("washingtondc", "Washington, DC"),
        CraigslistRegion("miami", "Miami / Dade"),
        CraigslistRegion("atlanta", "Atlanta"),
        CraigslistRegion("boston", "Boston"),
        CraigslistRegion("phoenix", "Phoenix"),
        CraigslistRegion("seattle", "Seattle"),
        CraigslistRegion("sandiego", "San Diego"),
        CraigslistRegion("minneapolis", "Minneapolis / St Paul"),
        CraigslistRegion("denver", "Denver"),
        CraigslistRegion("detroit", "Detroit Metro"),
        CraigslistRegion("portland", "Portland, OR"),
        CraigslistRegion("orlando", "Orlando"),
        CraigslistRegion("tampa", "Tampa Bay Area"),
        CraigslistRegion("stlouis", "St Louis, MO"),
        CraigslistRegion("baltimore", "Baltimore"),
        CraigslistRegion("charlotte", "Charlotte"),
        CraigslistRegion("sacramento", "Sacramento"),
        CraigslistRegion("austin", "Austin"),
        CraigslistRegion("lasvegas", "Las Vegas"),
        CraigslistRegion("pittsburgh", "Pittsburgh"),
        CraigslistRegion("cincinnati", "Cincinnati"),
        CraigslistRegion("cleveland", "Cleveland"),
        CraigslistRegion("kansascity", "Kansas City, MO"),
        CraigslistRegion("indianapolis", "Indianapolis"),
        CraigslistRegion("columbus", "Columbus, OH"),
        CraigslistRegion("nashville", "Nashville"),
        CraigslistRegion("milwaukee", "Milwaukee"),
        CraigslistRegion("raleigh", "Raleigh / Durham"),
        CraigslistRegion("jacksonville", "Jacksonville"),
        CraigslistRegion("oklahomacity", "Oklahoma City"),
        CraigslistRegion("memphis", "Memphis"),
        CraigslistRegion("louisville", "Louisville"),
        CraigslistRegion("richmond", "Richmond, VA"),
        CraigslistRegion("neworleans", "New Orleans"),
        CraigslistRegion("buffalo", "Buffalo, NY"),
        CraigslistRegion("hartford", "Hartford"),
        CraigslistRegion("albuquerque", "Albuquerque"),
        CraigslistRegion("tucson", "Tucson"),
        CraigslistRegion("saltlakecity", "Salt Lake City"),
        CraigslistRegion("honolulu", "Honolulu"),
        CraigslistRegion("anchorage", "Anchorage / Mat-Su"),
        CraigslistRegion("birmingham", "Birmingham, AL"),
        CraigslistRegion("desmoines", "Des Moines"),
        // Small region — JP's home turf per issue #464 body.
        CraigslistRegion("nevadacity", "Nevada City (Sierra foothills)"),
    )

    /**
     * Curated category list — the seven slugs the issue's v1 scope
     * names plus "Furniture" and "Electronics" because they're the
     * two most-browsed for-sale sub-categories and adding them costs
     * nothing.
     *
     * Slug values are Craigslist's two- or three-letter category
     * codes that go into the `/search/<slug>` path segment. Order
     * mirrors the issue's chip-strip ordering: "all for sale" first
     * (the broadest), free stuff second (JP's stated use case).
     */
    val CATEGORIES: List<CraigslistCategory> = listOf(
        CraigslistCategory("sss", "All for sale", "All for-sale listings — broadest catch-all"),
        CraigslistCategory("zip", "Free stuff", "Free / give-away listings (the curbside-pickup feed)"),
        CraigslistCategory("cta", "Cars + trucks", "All cars + trucks (dealer + by-owner)"),
        CraigslistCategory("fua", "Furniture", "Furniture for sale"),
        CraigslistCategory("ela", "Electronics", "Electronics for sale"),
        CraigslistCategory("apa", "Apartments", "Apartments / housing for rent"),
        CraigslistCategory("jjj", "Jobs", "Job postings"),
    )

    /**
     * Compose the canonical RSS feed URL for a (region, category)
     * pair. Format is `https://<region>.craigslist.org/search/<category>?format=rss`
     * — verified via the issue body's example URLs and Craigslist's
     * documented `?format=rss` parameter.
     *
     * No URL-encoding needed: both slugs are constrained to
     * `[a-z0-9]` by data shape, and `?format=rss` is a fixed literal.
     */
    fun composeFeedUrl(region: CraigslistRegion, category: CraigslistCategory): String =
        "https://${region.slug}.craigslist.org/search/${category.slug}?format=rss"

    /**
     * Friendly title for an auto-generated subscription. The RSS
     * source uses the feed's own `<channel><title>` once parsed, but
     * the URL host fallback (`sfbay.craigslist.org`) shown in the
     * subscription summary list before hydration is uninformative —
     * surfacing a richer label here is what the UI binds to when
     * displaying the "what you just subscribed to" affordance.
     *
     * Format: `Craigslist <region label> — <category label>`,
     * e.g. `Craigslist SF Bay Area — Free stuff`.
     */
    fun friendlyTitle(region: CraigslistRegion, category: CraigslistCategory): String =
        "Craigslist ${region.label} — ${category.label}"

    /**
     * Recover a region from a host string. Used by the magic-link URL
     * matcher (#472) when the user pastes a Craigslist URL — we want
     * to surface the region in the route label so the chooser modal
     * reads "Craigslist (SF Bay Area)" rather than "RSS / Atom feed".
     *
     * Returns null if the host doesn't end in `.craigslist.org` or
     * the subdomain isn't a curated region.
     */
    fun regionFromHost(host: String): CraigslistRegion? {
        val normalised = host.lowercase().removePrefix("www.")
        if (!normalised.endsWith(".craigslist.org")) return null
        val sub = normalised.removeSuffix(".craigslist.org")
        if (sub.isEmpty() || sub.contains('.')) return null
        return REGIONS.firstOrNull { it.slug == sub }
    }

    /**
     * Quick host-only check used by the magic-link URL matcher. True
     * when [host] is a `<known-region>.craigslist.org`. Faster than
     * [regionFromHost] when the caller only needs a boolean (e.g.
     * the URL-match confidence ranker).
     */
    fun isCraigslistHost(host: String): Boolean =
        regionFromHost(host) != null
}

/**
 * One curated Craigslist regional subdomain.
 *
 * @property slug Subdomain — what goes before `.craigslist.org`.
 *   Must match `[a-z0-9]+` (DNS label semantics, no punctuation).
 * @property label Human display name shown in the region chip.
 */
data class CraigslistRegion(
    val slug: String,
    val label: String,
)

/**
 * One curated Craigslist category.
 *
 * @property slug Path segment after `/search/` — e.g. `zip` for free
 *   stuff, `sss` for all-for-sale, `cta` for cars + trucks.
 * @property label Short display name for the category chip.
 * @property description One-line helper text shown beneath the chip
 *   strip to clarify scope (e.g. "All for-sale listings — broadest
 *   catch-all").
 */
data class CraigslistCategory(
    val slug: String,
    val label: String,
    val description: String,
)
