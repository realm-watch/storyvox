package `in`.jphe.storyvox.source.royalroad.tagsync

import `in`.jphe.storyvox.source.royalroad.model.RoyalRoadIds

/**
 * One-place collection of every URL the Royal Road tag-sync flow
 * touches. Pulled out so a future WebView-driven endpoint
 * verification (run by JP on a real signed-in tablet) can update
 * the literals here without spelunking through
 * [RoyalRoadTagSyncSource].
 *
 * Endpoint inspection 2026-05-15 — see
 * `scratch/rr-tag-sync/api-notes.md` for the full audit. The short
 * version: RR does not publish an "API" for saved-tag preferences,
 * but the search-page form carries the user's saved-filter set in
 * its rendered HTML when `globalFilters=true` is passed. The
 * read direction parses that form; the write direction POSTs back
 * to the same search route with the antiforgery token harvested
 * the same way [`in`.jphe.storyvox.source.royalroad.RoyalRoadSource.setFollowed]
 * harvests its token (issue #178).
 *
 * Every URL is the bare `royalroad.com` route — no `www.` prefix —
 * so the cookie jar (keyed at `royalroad.com` eTLD+1 per
 * [`in`.jphe.storyvox.source.royalroad.auth.RoyalRoadAuthSource])
 * attaches credentials uniformly across read and write.
 */
internal object RoyalRoadTagSyncEndpoints {

    /**
     * The "saved-filter" search page. When passed `globalFilters=true`
     * while authed, the response pre-renders the user's saved tag
     * preferences on the form (`button.search-tag.selected`). The
     * read direction GETs this and parses the selected set.
     */
    val readUrl: String =
        "${RoyalRoadIds.BASE_URL}/fictions/search?globalFilters=true"

    /**
     * The write target — RR's search-filter save endpoint. POST body:
     *   - `tagsAdd=<slug>` repeated for every tag in the user's preferred set
     *   - `globalFilters=true`
     *   - `saveAsFilter=true` (the "save these filters" toggle)
     *   - `__RequestVerificationToken=<csrf>` (harvested from the GET
     *     of [readUrl])
     *
     * Because RR's actual save endpoint is undocumented and may
     * differ from /fictions/search, the writer treats any 2xx/3xx
     * as success and any 4xx (other than the expected 401 unauthed)
     * as "endpoint changed, skip writes until the next sync window."
     */
    val writeUrl: String =
        "${RoyalRoadIds.BASE_URL}/fictions/search"

    /**
     * Referer header for the write POST. Match what a browser would
     * send — the search results page itself. Some ASP.NET Core
     * antiforgery checks require a same-origin Referer.
     */
    val writeReferer: String = readUrl

    /**
     * Sentinel returned by the parser when the response was a 302
     * redirect to /account/login (the unauthed case). The syncer
     * treats this as "no session, skip this round."
     */
    const val UNAUTHED_PATH_PREFIX: String = "/account/login"
}
